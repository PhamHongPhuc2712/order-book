---
title: Phase 2 — Optimise and Measure — Implementation Plan
date: 2026-09-12
spec: "[[02 Spec/2026-09-12-itch-lob-design]]"
status: done — 2026-09-14; tag `phase-2-optimised`; tables in `docs/perf.md`
depends: phase-1-baseline
tags: [plan, phase-2, itch, performance]
---

# Phase 2: Optimise and Measure — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Replace each naive component with an allocation-free one, **one at a time, each behind an equivalence property test, each re-measured on the subset and the full day**, producing the table that is the centrepiece of the write-up: `naive → +mmap → +LongObjectMap → +ArrayBook → +OrderPool → +BBO-dedupe`, with msgs/s, wall, p50/p99/p99.9, bytes allocated per message, and GC pause correlation via JFR.

**Architecture:** Every optimisation is a drop-in behind an existing interface (`FrameReader` contract, `OrderMap`, `Book`) or a flag on `Engine` (pool, BBO dedupe). `Replay` gains `--reader stream|mmap --map hash|long --book tree|array --pool --dedupe` so any combination runs from one command. Nothing in `core`'s public API changes.

**Tech Stack:** Java 21 `java.lang.foreign` (`Arena`, `MemorySegment`) for mmap; JFR (built in); jqwik for equivalence.

**Spec:** [[02 Spec/2026-09-12-itch-lob-design]]

## Global Constraints

- Measure with **identical JVM flags** across rows: `-Xms8g -Xmx8g -XX:+UseG1GC` first; a second table with `-XX:+UseZGC`. Record CPU model, RAM, disk, OS.
- Subset numbers use `--hist`; full-day throughput numbers run **without** `--hist` (the two `nanoTime` calls per message are measurable overhead — say so).
- Every row: three runs, report the median. Warm-up is the first ~10 M messages; do not exclude it — full-day includes it and that's honest.
- Property tests compare the new structure against the naive one on random operation sequences of length ≤ 2000, ≥ 300 tries.
- Steady-state allocation target: **0 bytes/message** after the order pool. Remaining allocations must be named (e.g. `alpha()` on `R` messages, listener strings).

## File structure

```
core/src/main/java/sg/phuc/lob/
├── itch/MappedFrameReader.java         mmap via MemorySegment, same next()/buf() contract
├── book/LongObjectMap.java             open addressing, long keys, backward-shift delete
├── book/LongOrderMap.java              OrderMap over LongObjectMap<Order>
├── book/LongSet.java                   same probing, for Phase 3 match tracking
├── book/ArrayBook.java                 sorted int[] prices + Level[] per side
├── book/OrderPool.java                 free list of Order
└── engine/Engine.java                  + pool, + dedupe flags
core/src/test/java/sg/phuc/lob/book/    LongObjectMapPropertyTest, ArrayBookPropertyTest, OrderPoolTest
replay/src/main/java/sg/phuc/lob/replay/Replay.java   + flags
docs/perf.md                            the table, filled from numbers.md
```

---

### Task 1: `MappedFrameReader` (I/O)

**Files:** `core/src/main/java/sg/phuc/lob/itch/MappedFrameReader.java`; test `MappedFrameReaderTest.java`

**Interfaces:** `MappedFrameReader(Path)`, same `int next()`, `byte[] buf()`, `frames()`, `bytes()`, `close()`. Copies each frame into `buf` with `MemorySegment.copy` (keeps `Engine` on `byte[]`).

- [x] **Step 1: Failing test** — write a framed temp file (reuse `FrameReaderTest.framed`), read with both readers, assert identical sequences of `(len, bytes)`; assert truncated file throws with offset.

- [x] **Step 2: Implement** — *done 2026-09-13 with 1 GB `MappedByteBuffer` windows instead of `MemorySegment`: `java.lang.foreign` is still a preview API on JDK 21 (final in 22) and would force `--enable-preview` everywhere*

```java
package sg.phuc.lob.itch;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Memory-mapped [len:2][msg] reader. Same contract as FrameReader; the whole file is one MemorySegment. */
public final class MappedFrameReader implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final FileChannel ch;
    private final MemorySegment seg;
    private final long size;
    private final byte[] buf = new byte[65536];
    private long pos, frames;

    public MappedFrameReader(Path p) throws IOException {
        ch = FileChannel.open(p, StandardOpenOption.READ);
        size = ch.size();
        seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
    }
    public int next() throws IOException {
        if (pos == size) return -1;
        if (size - pos < 2) throw new IOException("truncated length prefix at byte " + pos);
        int len = (seg.get(ValueLayout.JAVA_BYTE, pos) & 0xFF) << 8 | (seg.get(ValueLayout.JAVA_BYTE, pos + 1) & 0xFF);
        if (len == 0 || len > buf.length) throw new IOException("bad frame length " + len + " at byte " + pos);
        if (size - pos - 2 < len) throw new IOException("truncated message at byte " + pos);
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, pos + 2, buf, 0, len);
        pos += 2 + len; frames++;
        return len;
    }
    public byte[] buf() { return buf; }
    public long frames() { return frames; }
    public long bytes() { return pos; }
    @Override public void close() throws IOException { arena.close(); ch.close(); }
}
```
Extract a tiny interface `Frames { int next() throws IOException; byte[] buf(); }` implemented by both readers; `Replay.run` takes a `Frames`.

- [x] **Step 3: Pass, then measure** — `Replay --reader mmap` on subset (`--hist`) and full day. Record row `+mmap`. Expected: modest gain on wall time; **no change** in alloc/msg or apply latency (this row isolates I/O). — *G1: 239.3 s vs 240.5 s naive on the full day; alloc unchanged (152/156.8 B/msg) as predicted; subset 11.3 -> 8.0 s*

- [x] **Step 4: Commit** — `git commit -am "perf: MappedFrameReader (MemorySegment); Frames interface; --reader flag"` — *all Phase 2 code landed in one commit `e7da2bb` (every piece is behind an interface; no intermediate state compiled alone)*

---

### Task 2: `LongObjectMap` and `LongOrderMap` (boxing)

**Files:** `book/LongObjectMap.java`, `LongOrderMap.java`, `LongSet.java`; test `LongObjectMapPropertyTest.java`

**Interfaces:** `LongObjectMap<V>(int expected)`: `V get(long)`, `V put(long, V)`, `V remove(long)`, `int size()`. Key `0` is reserved as EMPTY — `put(0, …)` throws (ITCH order references are ≥ 1; `P` messages carry ref 0 but never reach the map). `LongSet(int expected)`: `boolean add(long)`, `boolean contains(long)`.

- [x] **Step 1: Failing property test**

```java
package sg.phuc.lob.book;

import net.jqwik.api.*;
import java.util.HashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LongObjectMapPropertyTest {
    @Provide Arbitrary<List<long[]>> ops() {
        // [op 0=put 1=remove 2=get, key 1..64]  — small key space forces collisions and re-puts
        return Combinators.combine(Arbitraries.integers().between(0, 2), Arbitraries.longs().between(1, 64))
            .as((op, k) -> new long[]{op, k}).list().ofMinSize(1).ofMaxSize(2000);
    }
    @Property(tries = 400) void matchesHashMap(@ForAll("ops") List<long[]> ops) {
        var ref = new HashMap<Long, String>(); var m = new LongObjectMap<String>(4);
        for (long[] o : ops) {
            long k = o[1]; String v = "v" + k;
            switch ((int) o[0]) {
                case 0 -> assertEquals(ref.put(k, v), m.put(k, v));
                case 1 -> assertEquals(ref.remove(k), m.remove(k));
                default -> assertEquals(ref.get(k), m.get(k));
            }
            assertEquals(ref.size(), m.size());
        }
    }
    @Property(tries = 100) void growsBeyondInitialCapacity(@ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 5000) int n) {
        var m = new LongObjectMap<Integer>(2);
        for (long k = 1; k <= n; k++) m.put(k * 7919, (int) k);
        assertEquals(n, m.size());
        for (long k = 1; k <= n; k++) assertEquals((int) k, m.get(k * 7919));
    }
}
```

- [x] **Step 2: Implement**

```java
package sg.phuc.lob.book;

/** Open-addressing hash map with primitive long keys, linear probing, backward-shift deletion. Key 0 is EMPTY. */
public final class LongObjectMap<V> {
    private long[] keys; private Object[] vals; private int mask, size, resizeAt;

    public LongObjectMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2) - 1) << 1;
        keys = new long[cap]; vals = new Object[cap]; mask = cap - 1; resizeAt = cap / 2;
    }
    private static int mix(long k) { k *= 0x9E3779B97F4A7C15L; return (int) (k ^ (k >>> 32)); }

    @SuppressWarnings("unchecked")
    public V get(long k) {
        int i = mix(k) & mask;
        while (true) { long c = keys[i]; if (c == k) return (V) vals[i]; if (c == 0) return null; i = (i + 1) & mask; }
    }
    @SuppressWarnings("unchecked")
    public V put(long k, V v) {
        if (k == 0) throw new IllegalArgumentException("key 0 is reserved");
        if (size >= resizeAt) grow();
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == k) { V old = (V) vals[i]; vals[i] = v; return old; }
            if (c == 0) { keys[i] = k; vals[i] = v; size++; return null; }
            i = (i + 1) & mask;
        }
    }
    @SuppressWarnings("unchecked")
    public V remove(long k) {
        int i = mix(k) & mask;
        while (true) {
            long c = keys[i];
            if (c == 0) return null;
            if (c == k) {
                V old = (V) vals[i];
                // backward shift: move later entries in the same probe chain up
                int j = i;
                while (true) {
                    j = (j + 1) & mask;
                    long cj = keys[j];
                    if (cj == 0) break;
                    int home = mix(cj) & mask;
                    // entry at j may move to i if its home is not in (i, j]
                    boolean inRange = (i <= j) ? (home > i && home <= j) : (home > i || home <= j);
                    if (!inRange) { keys[i] = cj; vals[i] = vals[j]; i = j; }
                }
                keys[i] = 0; vals[i] = null; size--;
                return old;
            }
            i = (i + 1) & mask;
        }
    }
    public int size() { return size; }
    private void grow() {
        long[] ok = keys; Object[] ov = vals;
        int cap = ok.length << 1; keys = new long[cap]; vals = new Object[cap]; mask = cap - 1; resizeAt = cap / 2; size = 0;
        for (int i = 0; i < ok.length; i++) if (ok[i] != 0) put(ok[i], (V) ov[i]);
    }
}
```

`LongOrderMap`:
```java
package sg.phuc.lob.book;
public final class LongOrderMap implements OrderMap {
    private final LongObjectMap<Order> m;
    public LongOrderMap(int expected) { m = new LongObjectMap<>(expected); }
    @Override public Order get(long ref) { return m.get(ref); }
    @Override public Order put(long ref, Order o) { return m.put(ref, o); }
    @Override public Order remove(long ref) { return m.remove(ref); }
    @Override public int size() { return m.size(); }
}
```

`LongSet` — same probing without values; `add` returns false if present; used by Phase 3 (`Validation.MatchTracker`).

- [x] **Step 3: Pass, measure** — `--map long`. Record row `+LongObjectMap`. Expected: alloc/msg drops (no boxed `Long` per op); apply p50 drops; GC frequency in JFR drops. — *alloc 156.8 -> 102.3 B/msg; full-day wall unchanged (243.4 s): memory-latency bound, not allocation bound*

- [x] **Step 4: Commit** — `git commit -am "perf: LongObjectMap (open addressing, backward-shift delete), LongOrderMap, LongSet; property-tested"` — *in `e7da2bb`*

---

### Task 3: `ArrayBook` (level lookup)

**Files:** `book/ArrayBook.java`; test `ArrayBookPropertyTest.java`

**Interfaces:** implements `Book`. Per side: `int[] prices` (bids descending, asks ascending), `Level[] levels`, `int n`. `level(create)` = binary search + `System.arraycopy` insert; `removeLevel` = shift left; best = index 0.

- [x] **Step 1: Failing property test** — random ops `[side, price 1..300 (×100 so ticks are cents), qty 0..5]` applied to `TreeBook` and `ArrayBook` through a tiny helper that mirrors `Engine`'s level/append/remove logic; after every op assert `bestBid`, `bestAsk`, `bestShares` both sides, `levels()` both sides, `isCrossed()` agree; at the end assert full level sequences (price, shares, count) agree.

- [x] **Step 2: Implement**

```java
package sg.phuc.lob.book;

import java.util.Arrays;

/** Sorted-array book: per side, prices[] (best at index 0) and parallel levels[]. Binary search + arraycopy. */
public final class ArrayBook implements Book {
    private final int locate;
    private int[] bp = new int[64], ap = new int[64]; private Level[] bl = new Level[64], al = new Level[64]; private int nb, na;
    public ArrayBook(int locate) { this.locate = locate; }
    @Override public int locate() { return locate; }

    /** index of price on side, or -(insertion point)-1. Bids descending, asks ascending. */
    private int find(byte s, int price) {
        int[] p = s == 'B' ? bp : ap; int n = s == 'B' ? nb : na;
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1, v = p[mid];
            int cmp = s == 'B' ? Integer.compare(price, v) : Integer.compare(v, price);   // bids: larger price sorts first
            if (cmp == 0) return mid;
            if (cmp > 0) hi = mid - 1; else lo = mid + 1;
        }
        return -(lo + 1);
    }
    @Override public Level level(byte s, int price, boolean create) {
        int i = find(s, price);
        if (i >= 0) return (s == 'B' ? bl : al)[i];
        if (!create) return null;
        int ins = -i - 1;
        if (s == 'B') {
            if (nb == bp.length) { bp = Arrays.copyOf(bp, nb * 2); bl = Arrays.copyOf(bl, nb * 2); }
            System.arraycopy(bp, ins, bp, ins + 1, nb - ins); System.arraycopy(bl, ins, bl, ins + 1, nb - ins);
            Level lv = new Level(price); bp[ins] = price; bl[ins] = lv; nb++; return lv;
        } else {
            if (na == ap.length) { ap = Arrays.copyOf(ap, na * 2); al = Arrays.copyOf(al, na * 2); }
            System.arraycopy(ap, ins, ap, ins + 1, na - ins); System.arraycopy(al, ins, al, ins + 1, na - ins);
            Level lv = new Level(price); ap[ins] = price; al[ins] = lv; na++; return lv;
        }
    }
    @Override public void removeLevel(byte s, int price) {
        int i = find(s, price); if (i < 0) return;
        if (s == 'B') { System.arraycopy(bp, i + 1, bp, i, nb - i - 1); System.arraycopy(bl, i + 1, bl, i, nb - i - 1); bl[--nb] = null; }
        else          { System.arraycopy(ap, i + 1, ap, i, na - i - 1); System.arraycopy(al, i + 1, al, i, na - i - 1); al[--na] = null; }
    }
    @Override public Level bestLevel(byte s) { return s == 'B' ? (nb == 0 ? null : bl[0]) : (na == 0 ? null : al[0]); }
    @Override public int bestBid() { return nb == 0 ? 0 : bp[0]; }
    @Override public int bestAsk() { return na == 0 ? 0 : ap[0]; }
    @Override public int bestShares(byte s) { Level lv = bestLevel(s); return lv == null ? 0 : lv.shares; }
    @Override public boolean isCrossed() { return nb > 0 && na > 0 && bp[0] >= ap[0]; }
    @Override public int levels(byte s) { return s == 'B' ? nb : na; }
}
```
(Note: `Level` objects are still allocated per new price level. Levels churn far less than orders; pool them in Task 4 if JFR shows them.)

- [x] **Step 3: Pass, measure** — `--book array`. Record row `+ArrayBook`. Expected: apply p50 drops again; TreeMap entry allocation gone. — *alloc 102.3 -> 34.7 B/msg; full-day 243.4 -> 167.7 s (the one big wall-time win); subset p99.9 5.6 -> 2.8 us*

- [x] **Step 4: Commit** — `git commit -am "perf: ArrayBook (sorted arrays, best at index 0); equivalence property vs TreeBook"` — *in `e7da2bb`*

---

### Task 4: `OrderPool` (per-order allocation)

**Files:** `book/OrderPool.java`; `Engine` gains `Engine(…, OrderPool pool)` (null = allocate); test `OrderPoolTest.java` + an `EngineTest` variant running every existing engine test with a pool.

**Interfaces:** `OrderPool(int initial)`: `Order take(long ref, int locate, byte side, int shares, int price, long ts)`, `void give(Order)`, `int free()`, `int created()`.

- [x] **Step 1: Test** — take 3, give 3, take 3 → `created()==3`; a given order's fields are reset on next take; `EngineTest` suite passes with pool.

- [x] **Step 2: Implement** — singly-linked free list through `Order.next`; `take` pops or `new`; `give` resets pointers and pushes. `Engine.insert`/`replace` use `pool.take`; `removeOrder` calls `pool.give(o)` **after** the listener has seen it. Also pool `Level` (`LevelPool`) if Task 3's JFR shows level churn > 1 % of allocations.

- [x] **Step 3: Measure** — `--pool`. Record row `+OrderPool`. Expected: **alloc/msg → single digits or 0** on the subset (steady state). Any remaining allocation: identify with JFR `jdk.ObjectAllocationSample` (`jfr print --events jdk.ObjectAllocationSample run.jfr | grep -A3 objectClass | sort | uniq -c | sort -rn | head`) and name it in the write-up. — *alloc 34.7 -> 5.9 B/msg full day, 4.1 on the subset; 158.7 s; remaining allocation named via JFR in Task 6*

- [x] **Step 4: Commit** — `git commit -am "perf: OrderPool; engine steady-state allocation-free"` — *in `e7da2bb`*

---

### Task 5: BBO dedupe and listener cost

**Files:** `engine/Engine.java` (`--dedupe`: keep last BBO per locate in four `int[65536]`; call `onBbo` only on change)

- [x] **Step 1: Test** — with `DerivedWriter` the output is identical (the writer already dedupes); with a counting listener, calls drop.
- [x] **Step 2: Measure** — row `+dedupe`. Also measure the cost of `DerivedWriter` versus `NullListener` and report it separately ("derived output costs X % of wall"). — *flat with `NullListener` (165.3 s vs 158.7 s); `DerivedWriter` costs 5.5 -> 10.4 s on the subset (writer made allocation-free, 53.8 -> 4.1 B/msg); dedupe is a no-op for a self-deduping listener, hashes identical*
- [x] **Step 3: Commit** — `git commit -am "perf: BBO change detection in engine"` — *in `e7da2bb`*

---

### Task 6: GC in the tail — JFR before/after, G1 vs ZGC

**Files:** `docs/perf.md`

- [x] **Step 1: Naive with JFR** — *5 GCs, longest 7.76 ms; top allocation `TreeMap.firstEntry()` entry copies (31 %), boxed `Long`, `Order`, `HashMap$Node`*
```
java -Xms8g -Xmx8g -XX:+UseG1GC -XX:StartFlightRecording=filename=data/naive.jfr,settings=profile -cp "..." sg.phuc.lob.replay.Replay --file data/itch/12302019.sub20.bin --hist
jfr summary data/naive.jfr | grep -E "jdk.GarbageCollection|jdk.ObjectAllocationSample"
jfr print --events jdk.GarbageCollection data/naive.jfr | grep -E "sumOfPauses|longestPause" | sort | uniq -c | sort -rn | head
```
Record: GC count, longest pause, and p99.9/max from the histogram. Expected: longest pause of several ms sits at or near `max`.

- [x] **Step 2: Final with JFR** — same command with `--reader mmap --map long --book array --pool --dedupe`. Expected: GC count near zero after warm-up; p99.9 collapses; `max` may still show one or two warm-up pauses — say so. — *0 GCs; remaining `Level` + pool growth (217,125 peak live orders)*

- [x] **Step 3: ZGC table** — repeat the full row set with `-XX:+UseZGC`. Expected: naive tail improves a lot (ZGC's point), final row barely changes (nothing left to collect). That contrast *is* the finding: "the optimisation removed the problem the collector was papering over." — *Generational ZGC at 4g (D27): naive 329 s, final 194 s; ~1.5x slower than G1 same-heap; tail 6.7 us vs G1 2.7 us*

- [x] **Step 4: Write `docs/perf.md`** — the two tables (G1, ZGC) with all rows, machine spec, flags, and three sentences of interpretation per table. Numbers copied from `numbers.md` only. — *from `ops/perf_table.py` only*

- [x] **Step 5: Commit and tag** — `git add docs/perf.md && git commit -m "perf: before/after tables, G1 vs ZGC, JFR correlation" && git tag phase-2-optimised` — *tag `phase-2-optimised`*

## Self-review

- **Spec coverage:** §11 performance table rows map to Tasks 1–5; JFR/GC to Task 6; §10 property tests for `LongObjectMap` and `ArrayBook` (Tasks 2–3); §12 D16 (mmap), D18 (single-thread — unchanged).
- **Placeholder scan:** Task 4 Step 2 and Task 5 describe rather than list code; both are small and fully specified by the tests and the Phase 1 `Engine`. `LongSet` body is "same as the map without values" — acceptable given the map is listed in full.
- **Type consistency:** `Frames` interface introduced in Task 1 is what `Replay.run` consumes from Task 1 onward; `OrderMap` and `Book` signatures unchanged from Phase 1; `Engine` constructor gains one trailing `OrderPool` parameter — update `EngineTest.engine()` and `Replay.main`.
