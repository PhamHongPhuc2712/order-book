---
title: Phase 1 — Parse and Reconstruct — Implementation Plan
date: 2026-09-12
spec: "[[02 Spec/2026-09-12-itch-lob-design]]"
status: done — all tasks complete 2026-09-13; tag `phase-1-baseline`
tags: [plan, phase-1, itch]
---

# Phase 1: Parse and Reconstruct — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** A Java 21 program that reads a full-day NASDAQ ITCH 5.0 file, reconstructs the Level-3 book for every symbol with a **credibly naive** design (`HashMap<Long,Order>`, `TreeMap` levels, one `Order` object per add), counts every invariant violation, writes derived outputs, and finishes `12302019` with zero structural violations. This is the baseline every Phase 2 number is measured against.

**Architecture:** Two Maven modules. `core` holds the frame reader, big-endian accessors, book structures, the engine and validation — no file I/O beyond `FrameReader`. `replay` holds the mains: `Probe`, `Filter`, `Replay`. Single-threaded. Prices are `int` at native scale 4; no decimal parsing anywhere.

**Tech Stack:** Java 21, Maven 3.9, JUnit 5.10, jqwik 1.8, HdrHistogram 2.2. Zero other runtime dependencies.

**Spec:** [[02 Spec/2026-09-12-itch-lob-design]] · Layouts: [[04 Reference/ITCH 5.0 Message Layouts]]

## Global Constraints

- Package root `sg.phuc.lob`. Repo `C:\Computing\GitHub\order-book`. Data under `data/itch/` (git-ignored).
- Side bytes are the ITCH characters: `'B'` bid, `'S'` ask. No enum on the hot path.
- Every `Engine` message path is exercised by a unit test built from hand-encoded bytes (`Msg` test helper).
- Nothing throws on bad data except `FrameReader` on truncation/bad length. Everything else counts.
- Every task ends with `mvn -q verify` green and a commit.

## File structure

```
lob-reconstruct/
├── pom.xml · .gitignore · .github/workflows/ci.yml · docs/NQTVITCHspecification.pdf
├── core/src/main/java/sg/phuc/lob/
│   ├── itch/Itch.java                 accessors + expectedLength
│   ├── itch/FrameReader.java          [len:2][msg] over InputStream
│   ├── book/Order.java · Level.java · Book.java (interface) · TreeBook.java
│   ├── book/OrderMap.java (interface) · HashOrderMap.java
│   ├── engine/Engine.java · Validation.java · Listener.java · NullListener.java
│   └── engine/DerivedWriter.java
├── core/src/test/java/sg/phuc/lob/    Msg.java (helper), ItchTest, FrameReaderTest, LevelTest, TreeBookTest, EngineTest, DerivedWriterTest
├── replay/src/main/java/sg/phuc/lob/replay/  Probe.java · Filter.java · Replay.java
├── replay/src/test/java/sg/phuc/lob/replay/  GoldenReplayTest.java
└── replay/src/test/resources/golden/  subset-5min.bin · expected.sha256
```

---

### Task 0: Scaffold and CI

**Files:** `pom.xml`, `core/pom.xml`, `replay/pom.xml`, `.gitignore`, `.github/workflows/ci.yml`

- [x] **Step 1: Root pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>sg.phuc</groupId><artifactId>lob-reconstruct</artifactId><version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>
  <modules><module>core</module><module>replay</module></modules>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>5.10.3</junit.version><jqwik.version>1.8.5</jqwik.version><hdr.version>2.2.2</hdr.version>
  </properties>
  <dependencyManagement><dependencies>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>net.jqwik</groupId><artifactId>jqwik</artifactId><version>${jqwik.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.hdrhistogram</groupId><artifactId>HdrHistogram</artifactId><version>${hdr.version}</version></dependency>
  </dependencies></dependencyManagement>
  <build><pluginManagement><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
  </plugins></pluginManagement></build>
</project>
```

`core/pom.xml`: parent as above, `<artifactId>core</artifactId>`, deps `junit-jupiter`, `jqwik`.
`replay/pom.xml`: `<artifactId>replay</artifactId>`, deps `sg.phuc:core:${project.version}`, `HdrHistogram`, `junit-jupiter`.

`.gitignore`:
```
target/
data/
*.jfr
research/.venv/
research/out/
```

`.github/workflows/ci.yml`:
```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21', cache: maven }
      - run: mvn -q -B verify
```

- [x] **Step 2: Verify** — `mvn -q verify` → exit 0.
- [x] **Step 3: Commit** — `git init && git add -A && git commit -m "scaffold: core + replay modules, CI"`

---

### Task 1: Download, verify, decompress, and confirm the framing

**Files:** `replay/src/main/java/sg/phuc/lob/replay/Probe.java`, plus `Itch.java` (needed by Probe — written here, tested in Task 2)

**Interfaces:**
- Produces: `data/itch/12302019.bin` (decompressed), and a confirmed answer to "is the file `[len:2][msg]` framed?"

- [x] **Step 1: Download and verify** (PowerShell, from repo root) — *done 2026-09-12; no `.md5sum` published for this file (404), framing check used as integrity check*

```powershell
New-Item -ItemType Directory -Force data\itch | Out-Null
curl.exe -L -C - -o data\itch\12302019.NASDAQ_ITCH50.gz "https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/12302019.NASDAQ_ITCH50.gz"
curl.exe -L -o data\itch\12302019.NASDAQ_ITCH50.gz.md5sum "https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/12302019.NASDAQ_ITCH50.gz.md5sum"
(Get-FileHash data\itch\12302019.NASDAQ_ITCH50.gz -Algorithm MD5).Hash.ToLower(); Get-Content data\itch\12302019.NASDAQ_ITCH50.gz.md5sum
```
Expected: the two hashes match. If the `.md5sum` for this file 404s (the listing showed md5sums for some dates only), record that and proceed — the framing check below is the integrity check.

- [x] **Step 2: Decompress once** (Git Bash — `gzip` ships with it)

```bash
gzip -dk -c data/itch/12302019.NASDAQ_ITCH50.gz > data/itch/12302019.bin
ls -la data/itch/
```
Expected: `12302019.bin` roughly 3× the gz size (≈ 10–12 GB). Record the exact size in [[05 Log/Daily Log]].

- [x] **Step 3: Write `Itch.java`**

```java
package sg.phuc.lob.itch;

import java.nio.charset.StandardCharsets;

/** Big-endian field accessors over a raw ITCH 5.0 message in b[0..len). Allocation-free except alpha(). */
public final class Itch {
    private Itch() {}
    public static int  u8 (byte[] b, int o) { return b[o] & 0xFF; }
    public static int  u16(byte[] b, int o) { return (b[o] & 0xFF) << 8 | (b[o + 1] & 0xFF); }
    public static int  u32(byte[] b, int o) { return (b[o] & 0xFF) << 24 | (b[o + 1] & 0xFF) << 16 | (b[o + 2] & 0xFF) << 8 | (b[o + 3] & 0xFF); }
    public static long u48(byte[] b, int o) { long v = 0; for (int i = 0; i < 6; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }
    public static long u64(byte[] b, int o) { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }
    public static char type(byte[] b)      { return (char) b[0]; }
    public static int  locate(byte[] b)    { return u16(b, 1); }
    public static long timestamp(byte[] b) { return u48(b, 5); }          // ns since midnight
    public static String alpha(byte[] b, int o, int n) {
        int e = o + n; while (e > o && b[e - 1] == ' ') e--;
        return new String(b, o, e - o, StandardCharsets.US_ASCII);
    }
    /** Message length from the spec, or -1 for an unknown type. */
    public static int expectedLength(char t) {
        return switch (t) {
            case 'S' -> 12; case 'R' -> 39; case 'H' -> 25; case 'Y' -> 20; case 'L' -> 26; case 'V' -> 35; case 'W' -> 12;
            case 'K' -> 28; case 'J' -> 35; case 'h' -> 21; case 'A' -> 36; case 'F' -> 40; case 'E' -> 31; case 'C' -> 36;
            case 'X' -> 23; case 'D' -> 19; case 'U' -> 35; case 'P' -> 44; case 'Q' -> 40; case 'B' -> 19; case 'I' -> 50;
            case 'N' -> 20; case 'O' -> 48; default -> -1;
        };
    }
}
```
(`u32` on a `Price(4)` never overflows: max 200,000.0000 = 2,000,000,000 < 2³¹.)

- [x] **Step 4: Write `Probe.java`**

```java
package sg.phuc.lob.replay;

import sg.phuc.lob.itch.Itch;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Day-1 framing check: read [len:2][msg] frames, verify len == spec length for the type, histogram types. */
public final class Probe {
    public static void main(String[] args) throws IOException {
        Path p = Path.of(args[0]); long max = args.length > 1 ? Long.parseLong(args[1]) : Long.MAX_VALUE;
        InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20);
        if (p.toString().endsWith(".gz")) in = new GZIPInputStream(in, 1 << 16);
        byte[] hdr = new byte[2], buf = new byte[65536];
        long[] hist = new long[128]; long frames = 0, bytes = 0, mismatch = 0; char firstS = 0;
        long t0 = System.nanoTime();
        try (in) {
            while (frames < max) {
                if (in.readNBytes(hdr, 0, 2) < 2) break;
                int len = (hdr[0] & 0xFF) << 8 | (hdr[1] & 0xFF);
                if (len == 0 || len > buf.length) { System.out.println("bad length " + len + " at byte " + bytes); break; }
                if (in.readNBytes(buf, 0, len) < len) { System.out.println("truncated at byte " + bytes); break; }
                char t = Itch.type(buf);
                if (t < 128) hist[t]++;
                int exp = Itch.expectedLength(t);
                if (exp != len) mismatch++;
                if (t == 'S' && firstS == 0) firstS = (char) buf[11];
                frames++; bytes += 2 + len;
            }
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("frames=%d bytes=%d mismatch=%d firstSystemEvent=%c secs=%.1f frames/s=%.0f%n", frames, bytes, mismatch, firstS, s, frames / s);
        for (int i = 0; i < 128; i++) if (hist[i] > 0) System.out.printf("  %c %d%n", (char) i, hist[i]);
    }
}
```

- [x] **Step 5: Run on the first 10,000 frames, then the whole file**

```
mvn -q -pl replay -am package -DskipTests
java -cp "replay/target/classes;core/target/classes" sg.phuc.lob.replay.Probe data/itch/12302019.bin 10000
java -cp "replay/target/classes;core/target/classes" sg.phuc.lob.replay.Probe data/itch/12302019.bin
```
Expected (first run): `mismatch=0`, `firstSystemEvent=O`, histogram dominated by `R`/`H`/`Y`/`L` early (the pre-open spins).
Expected (full run): `mismatch=0`; total frames in the hundreds of millions; `A`+`F` ≈ `D` + (orders fully executed); `S` = 6. **Record frames, bytes, frames/s and the histogram in [[05 Log/Daily Log]] — this is the I/O-only baseline.**

If `mismatch` is nonzero from frame 1, the framing hypothesis is wrong: re-run reading raw concatenated messages using `expectedLength` to advance, and update the spec §3 and the layouts note.

- [x] **Step 6: Commit**
```
git add core/src/main/java/sg/phuc/lob/itch/Itch.java replay/src/main/java/sg/phuc/lob/replay/Probe.java
git commit -m "itch: accessors + expectedLength; probe confirms framing on 12302019"
```

---

### Task 2: Itch accessor tests and FrameReader

**Files:** `core/src/main/java/sg/phuc/lob/itch/FrameReader.java`; tests `ItchTest.java`, `FrameReaderTest.java`

**Interfaces:**
- Produces: `FrameReader(InputStream)`, `int next()` (length or -1), `byte[] buf()`, `long frames()`, `long bytes()`; throws `IOException("truncated … at byte N")` / `("bad frame length …")`.

- [x] **Step 1: Failing tests**

```java
package sg.phuc.lob.itch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItchTest {
    @Test void bigEndianAccessors() {
        byte[] b = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        assertEquals(0x0102, Itch.u16(b, 0));
        assertEquals(0x01020304, Itch.u32(b, 0));
        assertEquals(0x010203040506L, Itch.u48(b, 0));
        assertEquals(0x0102030405060708L, Itch.u64(b, 0));
    }
    @Test void priceMaxFitsInt() {
        byte[] b = {0x77, 0x35, (byte) 0x94, 0x00};     // 2,000,000,000 = 200,000.0000
        assertEquals(2_000_000_000, Itch.u32(b, 0));
    }
    @Test void alphaTrimsRightPadding() {
        byte[] b = "AAPL    ".getBytes();
        assertEquals("AAPL", Itch.alpha(b, 0, 8));
    }
    @Test void lengthsMatchSpec() {
        assertEquals(36, Itch.expectedLength('A')); assertEquals(40, Itch.expectedLength('F'));
        assertEquals(31, Itch.expectedLength('E')); assertEquals(35, Itch.expectedLength('U'));
        assertEquals(-1, Itch.expectedLength('z'));
    }
}
```

```java
package sg.phuc.lob.itch;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class FrameReaderTest {
    static byte[] framed(byte[]... msgs) {
        var out = new java.io.ByteArrayOutputStream();
        for (byte[] m : msgs) { out.write(m.length >> 8); out.write(m.length & 0xFF); out.writeBytes(m); }
        return out.toByteArray();
    }
    @Test void readsFramesInOrder() throws IOException {
        byte[] a = new byte[12]; a[0] = 'S'; byte[] b = new byte[19]; b[0] = 'D';
        var fr = new FrameReader(new ByteArrayInputStream(framed(a, b)));
        assertEquals(12, fr.next()); assertEquals('S', fr.buf()[0]);
        assertEquals(19, fr.next()); assertEquals('D', fr.buf()[0]);
        assertEquals(-1, fr.next());
        assertEquals(2, fr.frames()); assertEquals(2 + 12 + 2 + 19, fr.bytes());
    }
    @Test void truncatedMessageThrowsWithOffset() {
        byte[] a = new byte[12]; a[0] = 'S';
        byte[] data = framed(a); byte[] cut = java.util.Arrays.copyOf(data, data.length - 3);
        var fr = new FrameReader(new ByteArrayInputStream(cut));
        var ex = assertThrows(IOException.class, fr::next);
        assertTrue(ex.getMessage().contains("truncated"));
        assertTrue(ex.getMessage().contains("at byte 0"));
    }
    @Test void zeroLengthThrows() {
        var fr = new FrameReader(new ByteArrayInputStream(new byte[]{0, 0, 1}));
        assertThrows(IOException.class, fr::next);
    }
}
```

- [x] **Step 2: Run to verify failure** — `mvn -q -pl core test` → compilation error (`FrameReader`).

- [x] **Step 3: Implement**

```java
package sg.phuc.lob.itch;

import java.io.IOException;
import java.io.InputStream;

/** Reads [len:2 big-endian][message] frames into a reusable buffer. Throws with byte offset on any truncation. */
public final class FrameReader implements AutoCloseable {
    private final InputStream in;
    private final byte[] buf = new byte[65536];
    private final byte[] hdr = new byte[2];
    private long frames, bytes;

    public FrameReader(InputStream in) { this.in = in; }

    /** @return message length now in buf(), or -1 at clean EOF. */
    public int next() throws IOException {
        int n = in.readNBytes(hdr, 0, 2);
        if (n == 0) return -1;
        if (n < 2) throw new IOException("truncated length prefix at byte " + bytes);
        int len = (hdr[0] & 0xFF) << 8 | (hdr[1] & 0xFF);
        if (len == 0 || len > buf.length) throw new IOException("bad frame length " + len + " at byte " + bytes);
        int m = in.readNBytes(buf, 0, len);
        if (m < len) throw new IOException("truncated message (" + m + "/" + len + ") at byte " + bytes);
        bytes += 2 + len; frames++;
        return len;
    }
    public byte[] buf() { return buf; }
    public long frames() { return frames; }
    public long bytes() { return bytes; }
    @Override public void close() throws IOException { in.close(); }
}
```

- [x] **Step 4: Run to verify pass** — `mvn -q -pl core test` → `Tests run: 7, Failures: 0`.
- [x] **Step 5: Commit** — `git commit -am "itch: FrameReader with truncation detection; accessor tests"`

---

### Task 3: Order, Level, Book, OrderMap (naive)

**Files:** `book/Order.java`, `Level.java`, `Book.java`, `TreeBook.java`, `OrderMap.java`, `HashOrderMap.java`; tests `LevelTest.java`, `TreeBookTest.java`

**Interfaces:**
- `Order(long ref, int locate, byte side, int shares, int price, long ts)` — public mutable fields, intrusive `prev/next/level`.
- `Level(int price)`: `append(Order)`, `remove(Order)`, `reduce(Order, int)`; fields `price, shares, count, head, tail`.
- `interface Book { int locate(); Level level(byte side, int price, boolean create); void removeLevel(byte side, int price); Level bestLevel(byte side); int bestBid(); int bestAsk(); int bestShares(byte side); boolean isCrossed(); int levels(byte side); }`
- `interface OrderMap { Order get(long ref); Order put(long ref, Order o); Order remove(long ref); int size(); }`

- [x] **Step 1: Failing tests**

```java
package sg.phuc.lob.book;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LevelTest {
    static Order o(long ref, int sh) { return new Order(ref, 1, (byte) 'B', sh, 650000, 0); }
    @Test void fifoAppendRemoveReduce() {
        var lv = new Level(650000);
        Order a = o(1, 100), b = o(2, 50), c = o(3, 25);
        lv.append(a); lv.append(b); lv.append(c);
        assertSame(a, lv.head); assertSame(c, lv.tail); assertEquals(175, lv.shares); assertEquals(3, lv.count);
        lv.reduce(a, 40); assertEquals(60, a.shares); assertEquals(135, lv.shares);
        lv.remove(b);                                   // middle
        assertSame(c, a.next); assertSame(a, c.prev); assertEquals(85, lv.shares); assertEquals(2, lv.count);
        lv.remove(a);                                   // head
        assertSame(c, lv.head); assertNull(c.prev);
        lv.remove(c);                                   // last
        assertNull(lv.head); assertNull(lv.tail); assertEquals(0, lv.shares); assertEquals(0, lv.count);
    }
}
```

```java
package sg.phuc.lob.book;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeBookTest {
    @Test void bestAndCrossed() {
        Book bk = new TreeBook(7);
        assertEquals(0, bk.bestBid()); assertEquals(0, bk.bestAsk()); assertFalse(bk.isCrossed());
        bk.level((byte) 'B', 650000, true).append(new Order(1, 7, (byte) 'B', 100, 650000, 0));
        bk.level((byte) 'B', 649900, true).append(new Order(2, 7, (byte) 'B', 100, 649900, 0));
        bk.level((byte) 'S', 650100, true).append(new Order(3, 7, (byte) 'S', 30, 650100, 0));
        assertEquals(650000, bk.bestBid()); assertEquals(650100, bk.bestAsk());
        assertEquals(100, bk.bestShares((byte) 'B')); assertEquals(30, bk.bestShares((byte) 'S'));
        assertEquals(2, bk.levels((byte) 'B'));
        assertNull(bk.level((byte) 'B', 640000, false));
        bk.removeLevel((byte) 'B', 650000);
        assertEquals(649900, bk.bestBid());
        bk.level((byte) 'S', 649900, true).append(new Order(4, 7, (byte) 'S', 1, 649900, 0));
        assertTrue(bk.isCrossed());
    }
}
```

- [x] **Step 2: Run to verify failure** — compilation error.

- [x] **Step 3: Implement**

```java
package sg.phuc.lob.book;
/** Intrusive list node. Public mutable fields are deliberate: this is the hot path, and Phase 2 pools these. */
public final class Order {
    public long ref; public int locate; public byte side; public int shares; public int price; public long ts;
    public Order prev, next; public Level level;
    public Order(long ref, int locate, byte side, int shares, int price, long ts) { reset(ref, locate, side, shares, price, ts); }
    public void reset(long ref, int locate, byte side, int shares, int price, long ts) {
        this.ref = ref; this.locate = locate; this.side = side; this.shares = shares; this.price = price; this.ts = ts;
        prev = next = null; level = null;
    }
}
```

```java
package sg.phuc.lob.book;
/** One price level: FIFO queue of orders. shares == Σ order.shares, count == list length (property-tested). */
public final class Level {
    public final int price; public int shares; public int count; public Order head, tail;
    public Level(int price) { this.price = price; }
    public void append(Order o) {
        o.level = this; o.prev = tail; o.next = null;
        if (tail == null) head = o; else tail.next = o;
        tail = o; shares += o.shares; count++;
    }
    public void remove(Order o) {
        if (o.prev == null) head = o.next; else o.prev.next = o.next;
        if (o.next == null) tail = o.prev; else o.next.prev = o.prev;
        o.prev = o.next = null; o.level = null; shares -= o.shares; count--;
    }
    public void reduce(Order o, int by) { o.shares -= by; shares -= by; }
}
```

```java
package sg.phuc.lob.book;
public interface Book {
    int locate();
    Level level(byte side, int price, boolean create);
    void removeLevel(byte side, int price);
    Level bestLevel(byte side);
    int bestBid(); int bestAsk(); int bestShares(byte side);
    boolean isCrossed();
    int levels(byte side);
}
```

```java
package sg.phuc.lob.book;

import java.util.Comparator;
import java.util.TreeMap;

/** Naive book: TreeMap per side. Bids descending so firstKey() is best on both sides. */
public final class TreeBook implements Book {
    private final int locate;
    private final TreeMap<Integer, Level> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Integer, Level> asks = new TreeMap<>();
    public TreeBook(int locate) { this.locate = locate; }
    private TreeMap<Integer, Level> side(byte s) { return s == 'B' ? bids : asks; }
    @Override public int locate() { return locate; }
    @Override public Level level(byte s, int price, boolean create) {
        TreeMap<Integer, Level> m = side(s);
        Level lv = m.get(price);
        if (lv == null && create) { lv = new Level(price); m.put(price, lv); }
        return lv;
    }
    @Override public void removeLevel(byte s, int price) { side(s).remove(price); }
    @Override public Level bestLevel(byte s) { var e = side(s).firstEntry(); return e == null ? null : e.getValue(); }
    @Override public int bestBid() { return bids.isEmpty() ? 0 : bids.firstKey(); }
    @Override public int bestAsk() { return asks.isEmpty() ? 0 : asks.firstKey(); }
    @Override public int bestShares(byte s) { Level lv = bestLevel(s); return lv == null ? 0 : lv.shares; }
    @Override public boolean isCrossed() { return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey(); }
    @Override public int levels(byte s) { return side(s).size(); }
}
```

```java
package sg.phuc.lob.book;
public interface OrderMap { Order get(long ref); Order put(long ref, Order o); Order remove(long ref); int size(); }
```

```java
package sg.phuc.lob.book;
import java.util.HashMap;
/** Naive: boxes every key. This is the baseline Phase 2 replaces with LongObjectMap. */
public final class HashOrderMap implements OrderMap {
    private final HashMap<Long, Order> m;
    public HashOrderMap(int expected) { m = new HashMap<>(expected * 2); }
    @Override public Order get(long ref) { return m.get(ref); }
    @Override public Order put(long ref, Order o) { return m.put(ref, o); }
    @Override public Order remove(long ref) { return m.remove(ref); }
    @Override public int size() { return m.size(); }
}
```

- [x] **Step 4: Run to verify pass** — `mvn -q -pl core test` → `Tests run: 9, Failures: 0`.
- [x] **Step 5: Commit** — `git commit -am "book: Order, Level (intrusive FIFO), TreeBook, HashOrderMap"`

---

### Task 4: Listener, Validation, Engine

**Files:** `engine/Listener.java`, `NullListener.java`, `Validation.java`, `Engine.java`; tests `Msg.java` (helper), `EngineTest.java`

**Interfaces:**
- `interface Listener { onSystemEvent(ts, code); onBbo(ts, locate, bid, bidSh, ask, askSh); onExecution(ts, locate, ref, side, shares, price, match, printable); onCancel(ts, locate, ref, side, shares, price); onTrade(ts, locate, kind, shares, price, match, crossType); }` — all `default {}`.
- `Engine(OrderMap, BookFactory, Listener, Set<String> symbolFilter)`; `void apply(byte[] b, int len)`; `Validation validation()`; `String symbol(int)`; `Book book(int)`; `int liveOrders()`; `long messages()`; `boolean marketHours()`.
- `Validation` public counters: `badLength, unknownType, duplicateRef, unknownRef, execExceeds, cancelExceeds, noDirectory, crossedInMarket, priorityChecked, priorityViolations, duplicateMatch, brokenUnknown, liveAtC`; `List<String> samples()`; `boolean structurallyClean()`; `String toJson()`; `matchSeen(long)`, `broken(long)` (no-ops until Phase 3 enables tracking).

- [x] **Step 1: Test helper `Msg.java`** (test scope, `sg.phuc.lob.engine`)

```java
package sg.phuc.lob.engine;

final class Msg {
    static void p16(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
    static void p32(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (24 - 8 * i)); }
    static void p48(byte[] b, int o, long v) { for (int i = 0; i < 6; i++) b[o + i] = (byte) (v >> (40 - 8 * i)); }
    static void p64(byte[] b, int o, long v) { for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >> (56 - 8 * i)); }
    static void alpha(byte[] b, int o, int n, String s) { for (int i = 0; i < n; i++) b[o + i] = (byte) (i < s.length() ? s.charAt(i) : ' '); }
    static byte[] hdr(char t, int len, int loc, long ts) { byte[] b = new byte[len]; b[0] = (byte) t; p16(b, 1, loc); p16(b, 3, 0); p48(b, 5, ts); return b; }

    static byte[] sys(long ts, char code) { byte[] b = hdr('S', 12, 0, ts); b[11] = (byte) code; return b; }
    static byte[] dir(int loc, long ts, String stock) { byte[] b = hdr('R', 39, loc, ts); alpha(b, 11, 8, stock); alpha(b, 19, 20, ""); return b; }
    static byte[] action(int loc, long ts, String stock, char state) { byte[] b = hdr('H', 25, loc, ts); alpha(b, 11, 8, stock); b[19] = (byte) state; alpha(b, 20, 5, ""); return b; }
    static byte[] add(int loc, long ts, long ref, char side, int sh, String stock, int px) { byte[] b = hdr('A', 36, loc, ts); p64(b, 11, ref); b[19] = (byte) side; p32(b, 20, sh); alpha(b, 24, 8, stock); p32(b, 32, px); return b; }
    static byte[] exec(int loc, long ts, long ref, int sh, long match) { byte[] b = hdr('E', 31, loc, ts); p64(b, 11, ref); p32(b, 19, sh); p64(b, 23, match); return b; }
    static byte[] execPx(int loc, long ts, long ref, int sh, long match, char printable, int px) { byte[] b = hdr('C', 36, loc, ts); p64(b, 11, ref); p32(b, 19, sh); p64(b, 23, match); b[31] = (byte) printable; p32(b, 32, px); return b; }
    static byte[] cancel(int loc, long ts, long ref, int sh) { byte[] b = hdr('X', 23, loc, ts); p64(b, 11, ref); p32(b, 19, sh); return b; }
    static byte[] delete(int loc, long ts, long ref) { byte[] b = hdr('D', 19, loc, ts); p64(b, 11, ref); return b; }
    static byte[] replace(int loc, long ts, long oldRef, long newRef, int sh, int px) { byte[] b = hdr('U', 35, loc, ts); p64(b, 11, oldRef); p64(b, 19, newRef); p32(b, 27, sh); p32(b, 31, px); return b; }
}
```

- [x] **Step 2: Failing tests**

```java
package sg.phuc.lob.engine;

import org.junit.jupiter.api.Test;
import sg.phuc.lob.book.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    static Engine engine(Set<String> filter) { return new Engine(new HashOrderMap(1024), TreeBook::new, new NullListener(), filter); }
    static void feed(Engine e, byte[]... msgs) { for (byte[] m : msgs) e.apply(m, m.length); }
    static Engine open(String stock, int loc) {           // directory, trading, market hours
        Engine e = engine(null);
        feed(e, Msg.sys(1, 'O'), Msg.dir(loc, 2, stock), Msg.action(loc, 3, stock, 'T'), Msg.sys(4, 'S'), Msg.sys(5, 'Q'));
        return e;
    }

    @Test void addExecDeleteAndBest() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'S', 50, "AAPL", 650100));
        assertEquals(650000, e.book(7).bestBid()); assertEquals(650100, e.book(7).bestAsk()); assertEquals(2, e.liveOrders());
        feed(e, Msg.exec(7, 12, 1, 40, 900));
        assertEquals(60, e.book(7).bestShares((byte) 'B'));
        feed(e, Msg.delete(7, 13, 1));
        assertEquals(0, e.book(7).bestBid()); assertEquals(1, e.liveOrders());
        assertTrue(e.validation().structurallyClean());
        assertEquals(1, e.validation().priorityChecked); assertEquals(0, e.validation().priorityViolations);
    }
    @Test void replaceGoesToBackOfQueueWithNewRef() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'B', 100, "AAPL", 650000));
        feed(e, Msg.replace(7, 12, 1, 3, 100, 650000));
        Level lv = e.book(7).level((byte) 'B', 650000, false);
        assertEquals(2, lv.head.ref); assertEquals(3, lv.tail.ref); assertEquals(2, lv.count);
        assertNull(e.orders().get(1)); assertNotNull(e.orders().get(3));
    }
    @Test void executionOffBestIsPriorityViolation() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650000), Msg.add(7, 11, 2, 'B', 100, "AAPL", 649900));
        feed(e, Msg.exec(7, 12, 2, 10, 901));            // 649900 is not best
        assertEquals(1, e.validation().priorityViolations);
        feed(e, Msg.exec(7, 13, 1, 10, 902));            // head of best
        assertEquals(2, e.validation().priorityChecked); assertEquals(1, e.validation().priorityViolations);
    }
    @Test void crossedIsCountedOnlyInMarketHoursAndStateT() {
        Engine e = engine(null);
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.action(7, 3, "AAPL", 'T'), Msg.sys(4, 'S'));
        feed(e, Msg.add(7, 10, 1, 'B', 100, "AAPL", 650100), Msg.add(7, 11, 2, 'S', 100, "AAPL", 650000));   // crossed pre-open
        assertEquals(0, e.validation().crossedInMarket);
        feed(e, Msg.sys(12, 'Q'), Msg.add(7, 13, 3, 'B', 1, "AAPL", 650100));
        assertEquals(1, e.validation().crossedInMarket);
        feed(e, Msg.action(7, 14, "AAPL", 'H'), Msg.add(7, 15, 4, 'B', 1, "AAPL", 650100));
        assertEquals(1, e.validation().crossedInMarket);                  // halted: not counted
    }
    @Test void unknownRefAndExceedsAreCountedAndClamped() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.exec(7, 10, 99, 10, 1));
        assertEquals(1, e.validation().unknownRef);
        feed(e, Msg.add(7, 11, 1, 'B', 10, "AAPL", 650000), Msg.cancel(7, 12, 1, 50));
        assertEquals(1, e.validation().cancelExceeds); assertEquals(0, e.liveOrders());
    }
    @Test void duplicateRefReplacesAndCounts() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.add(7, 11, 1, 'B', 20, "AAPL", 650000));
        assertEquals(1, e.validation().duplicateRef); assertEquals(20, e.book(7).bestShares((byte) 'B')); assertEquals(1, e.liveOrders());
    }
    @Test void symbolFilterSkipsOtherLocates() {
        Engine e = engine(Set.of("AAPL"));
        feed(e, Msg.sys(1, 'O'), Msg.dir(7, 2, "AAPL"), Msg.dir(8, 2, "MSFT"), Msg.sys(3, 'Q'));
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.add(8, 11, 2, 'B', 10, "MSFT", 3000000));
        assertEquals(1, e.liveOrders()); assertEquals(0, e.book(8).bestBid());
    }
    @Test void liveAtCReported() {
        Engine e = open("AAPL", 7);
        feed(e, Msg.add(7, 10, 1, 'B', 10, "AAPL", 650000), Msg.sys(11, 'M'), Msg.sys(12, 'E'), Msg.sys(13, 'C'));
        assertEquals(1, e.validation().liveAtC);
    }
}
```

- [x] **Step 3: Run to verify failure** — compilation error.

- [x] **Step 4: Implement**

`Listener.java` / `NullListener.java`:
```java
package sg.phuc.lob.engine;
public interface Listener {
    default void onSystemEvent(long ts, char code) {}
    default void onBbo(long ts, int locate, int bid, int bidSh, int ask, int askSh) {}
    default void onExecution(long ts, int locate, long ref, byte side, int shares, int price, long match, boolean printable) {}
    default void onCancel(long ts, int locate, long ref, byte side, int shares, int price) {}
    default void onTrade(long ts, int locate, char kind, int shares, int price, long match, char crossType) {}
}
```
```java
package sg.phuc.lob.engine;
public final class NullListener implements Listener {}
```

`Validation.java`:
```java
package sg.phuc.lob.engine;

import java.util.ArrayList;
import java.util.List;

public final class Validation {
    public long badLength, unknownType, duplicateRef, unknownRef, execExceeds, cancelExceeds, noDirectory,
                crossedInMarket, priorityChecked, priorityViolations, duplicateMatch, brokenUnknown, liveAtC;
    private final List<String> samples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;
    private MatchTracker matches;                       // Phase 3: LongSet-backed; null = disabled

    public interface MatchTracker { boolean add(long m); boolean contains(long m); }
    public void enableMatchTracking(MatchTracker t) { matches = t; }
    public void matchSeen(long m) { if (matches != null && !matches.add(m)) duplicateMatch++; }
    public void broken(long m) { if (matches != null && !matches.contains(m)) brokenUnknown++; }
    void sample(String kind, long msgNo, long id) { if (samples.size() < MAX_SAMPLES) samples.add(kind + "@" + msgNo + ":" + id); }
    public List<String> samples() { return samples; }
    public boolean structurallyClean() { return badLength + unknownType + duplicateRef + unknownRef + execExceeds + cancelExceeds == 0; }
    public String toJson() {
        return "{\"badLength\":" + badLength + ",\"unknownType\":" + unknownType + ",\"duplicateRef\":" + duplicateRef
            + ",\"unknownRef\":" + unknownRef + ",\"execExceeds\":" + execExceeds + ",\"cancelExceeds\":" + cancelExceeds
            + ",\"noDirectory\":" + noDirectory + ",\"crossedInMarket\":" + crossedInMarket
            + ",\"priorityChecked\":" + priorityChecked + ",\"priorityViolations\":" + priorityViolations
            + ",\"duplicateMatch\":" + duplicateMatch + ",\"brokenUnknown\":" + brokenUnknown + ",\"liveAtC\":" + liveAtC
            + ",\"samples\":" + samples + "}";
    }
}
```

`Engine.java`:
```java
package sg.phuc.lob.engine;

import sg.phuc.lob.book.*;
import sg.phuc.lob.itch.Itch;
import java.util.Set;

/** Single-threaded ITCH 5.0 → Level-3 books. Counts violations; never throws on data. */
public final class Engine {
    public interface BookFactory { Book create(int locate); }
    public static final byte BID = 'B', ASK = 'S';

    private final Book[] books = new Book[65536];
    private final String[] symbols = new String[65536];
    private final byte[] tradingState = new byte[65536];
    private final boolean[] enabled = new boolean[65536];
    private final Set<String> filter;                    // null = all symbols
    private final OrderMap orders;
    private final BookFactory bookFactory;
    private final Listener listener;
    private final Validation v = new Validation();
    private boolean marketHours = false;
    private char session = 0;
    private long msgs;

    public Engine(OrderMap orders, BookFactory bookFactory, Listener listener, Set<String> symbolFilter) {
        this.orders = orders; this.bookFactory = bookFactory; this.listener = listener; this.filter = symbolFilter;
        if (filter == null) java.util.Arrays.fill(enabled, true);
        enabled[0] = true;
    }

    public void apply(byte[] b, int len) {
        msgs++;
        char t = Itch.type(b);
        int exp = Itch.expectedLength(t);
        if (exp < 0) { v.unknownType++; return; }
        if (exp != len) { v.badLength++; return; }
        long ts = Itch.timestamp(b);
        switch (t) {
            case 'S' -> {
                session = (char) b[11];
                if (session == 'Q') marketHours = true; else if (session == 'M') marketHours = false;
                if (session == 'C') v.liveAtC = orders.size();
                listener.onSystemEvent(ts, session);
            }
            case 'R' -> {
                int loc = Itch.locate(b);
                String sym = Itch.alpha(b, 11, 8);
                symbols[loc] = sym;
                if (filter != null) enabled[loc] = filter.contains(sym);
                if (books[loc] == null) books[loc] = bookFactory.create(loc);
            }
            case 'H' -> tradingState[Itch.locate(b)] = b[19];
            case 'A', 'F' -> add(b, ts);
            case 'E' -> exec(b, ts, false);
            case 'C' -> exec(b, ts, true);
            case 'X' -> cancel(b, ts);
            case 'D' -> delete(b, ts);
            case 'U' -> replace(b, ts);
            case 'P' -> { int loc = Itch.locate(b); long m = Itch.u64(b, 36); v.matchSeen(m);
                          if (enabled[loc]) listener.onTrade(ts, loc, 'P', Itch.u32(b, 20), Itch.u32(b, 32), m, (char) 0); }
            case 'Q' -> { int loc = Itch.locate(b); long m = Itch.u64(b, 31); v.matchSeen(m);
                          long sh = Itch.u64(b, 11);
                          if (enabled[loc]) listener.onTrade(ts, loc, 'Q', (int) Math.min(Integer.MAX_VALUE, sh), Itch.u32(b, 27), m, (char) b[39]); }
            case 'B' -> v.broken(Itch.u64(b, 11));
            default -> {}
        }
    }

    private Book book(int loc) {
        Book bk = books[loc];
        if (bk == null) { v.noDirectory++; bk = books[loc] = bookFactory.create(loc); symbols[loc] = "?"; }
        return bk;
    }

    private void add(byte[] b, long ts) {
        int loc = Itch.locate(b);
        if (!enabled[loc]) return;
        insert(loc, Itch.u64(b, 11), b[19], Itch.u32(b, 20), Itch.u32(b, 32), ts);
    }

    private void insert(int loc, long ref, byte side, int shares, int price, long ts) {
        Book bk = book(loc);
        int bb = bk.bestBid(), ba = bk.bestAsk();
        Order o = new Order(ref, loc, side, shares, price, ts);
        Order prev = orders.put(ref, o);
        if (prev != null) {
            v.duplicateRef++; v.sample("dupRef", msgs, ref);
            if (prev.level != null) { Level pl = prev.level; pl.remove(prev); if (pl.count == 0) books[prev.locate].removeLevel(prev.side, pl.price); }
        }
        bk.level(side, price, true).append(o);
        afterChange(bk, loc, ts, bb, ba);
    }

    private Order lookup(byte[] b, long ref) {
        Order o = orders.get(ref);
        if (o == null && enabled[Itch.locate(b)]) { v.unknownRef++; v.sample("unknownRef", msgs, ref); }
        return o;
    }

    private void exec(byte[] b, long ts, boolean withPrice) {
        long ref = Itch.u64(b, 11); int ex = Itch.u32(b, 19); long match = Itch.u64(b, 23);
        v.matchSeen(match);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        int price = withPrice ? Itch.u32(b, 32) : o.price;
        boolean printable = !withPrice || b[31] == 'Y';
        if (ex > o.shares) { v.execExceeds++; v.sample("execExceeds", msgs, ref); ex = o.shares; }
        if (!withPrice && marketHours && tradingState[o.locate] == 'T') {
            v.priorityChecked++;
            Level best = bk.bestLevel(o.side);
            if (best == null || best.price != o.price || best.head != o) { v.priorityViolations++; v.sample("priority", msgs, ref); }
        }
        int bb = bk.bestBid(), ba = bk.bestAsk();
        o.level.reduce(o, ex);
        listener.onExecution(ts, o.locate, ref, o.side, ex, price, match, printable);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void cancel(byte[] b, long ts) {
        long ref = Itch.u64(b, 11); int c = Itch.u32(b, 19);
        Order o = lookup(b, ref); if (o == null) return;
        Book bk = books[o.locate];
        if (c > o.shares) { v.cancelExceeds++; v.sample("cancelExceeds", msgs, ref); c = o.shares; }
        int bb = bk.bestBid(), ba = bk.bestAsk();
        o.level.reduce(o, c);
        listener.onCancel(ts, o.locate, ref, o.side, c, o.price);
        if (o.shares == 0) removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void delete(byte[] b, long ts) {
        Order o = lookup(b, Itch.u64(b, 11)); if (o == null) return;
        Book bk = books[o.locate];
        int bb = bk.bestBid(), ba = bk.bestAsk();
        listener.onCancel(ts, o.locate, o.ref, o.side, o.shares, o.price);
        removeOrder(o, bk);
        afterChange(bk, o.locate, ts, bb, ba);
    }

    private void replace(byte[] b, long ts) {
        long oldRef = Itch.u64(b, 11), newRef = Itch.u64(b, 19); int shares = Itch.u32(b, 27); int price = Itch.u32(b, 31);
        Order o = lookup(b, oldRef); if (o == null) return;
        Book bk = books[o.locate];
        int loc = o.locate; byte side = o.side;
        int bb = bk.bestBid(), ba = bk.bestAsk();
        listener.onCancel(ts, loc, oldRef, side, o.shares, o.price);          // old order leaves the queue
        removeOrder(o, bk);
        Order n = new Order(newRef, loc, side, shares, price, ts);
        Order prev = orders.put(newRef, n);
        if (prev != null) { v.duplicateRef++; v.sample("dupRef", msgs, newRef); if (prev.level != null) prev.level.remove(prev); }
        bk.level(side, price, true).append(n);                                 // D22: back of queue
        afterChange(bk, loc, ts, bb, ba);
    }

    private void removeOrder(Order o, Book bk) {
        Level lv = o.level; lv.remove(o); orders.remove(o.ref);
        if (lv.count == 0) bk.removeLevel(o.side, lv.price);
    }

    private void afterChange(Book bk, int loc, long ts, int bbBefore, int baBefore) {
        int bb = bk.bestBid(), ba = bk.bestAsk();
        if (marketHours && tradingState[loc] == 'T' && bb != 0 && ba != 0 && bb >= ba) { v.crossedInMarket++; v.sample("crossed", msgs, loc); }
        listener.onBbo(ts, loc, bb, bk.bestShares(BID), ba, bk.bestShares(ASK));
    }

    public Validation validation() { return v; }
    public String symbol(int loc) { return symbols[loc]; }
    public Book book(int loc) { return book(loc); }
    public OrderMap orders() { return orders; }
    public int liveOrders() { return orders.size(); }
    public long messages() { return msgs; }
    public boolean marketHours() { return marketHours; }
    public byte tradingState(int loc) { return tradingState[loc]; }
}
```
(One compile fix you'll hit: the public `book(int)` and private `book(int)` collide — rename the private one `bookOrCreate`.)

- [x] **Step 5: Run to verify pass** — `mvn -q -pl core test` → `Tests run: 17, Failures: 0`.
- [x] **Step 6: Commit** — `git commit -am "engine: Level-3 reconstruction with validation counters; all message paths tested"`

---

### Task 5: Filter (subset file) — needed before anything else touches the full day

**Files:** `replay/src/main/java/sg/phuc/lob/replay/Filter.java`

**Interfaces:** `Filter <in.bin> <out.bin> SYM1,SYM2,…` → writes every locate-0 message plus every message for the chosen symbols, same framing, same order.

- [x] **Step 1: Implement**

```java
package sg.phuc.lob.replay;

import sg.phuc.lob.itch.FrameReader;
import sg.phuc.lob.itch.Itch;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class Filter {
    public static void main(String[] args) throws IOException {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        Set<String> syms = Set.of(args[2].split(","));
        boolean[] keep = new boolean[65536]; keep[0] = true;
        long kept = 0, total = 0;
        try (var fr = new FrameReader(new BufferedInputStream(Files.newInputStream(in), 1 << 20));
             var os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            int len;
            while ((len = fr.next()) >= 0) {
                total++;
                byte[] b = fr.buf();
                int loc = Itch.locate(b);
                if (Itch.type(b) == 'R' && syms.contains(Itch.alpha(b, 11, 8))) keep[loc] = true;
                if (keep[loc]) { os.write(len >> 8); os.write(len & 0xFF); os.write(b, 0, len); kept++; }
            }
        }
        System.out.printf("kept %d of %d frames -> %s (%d bytes)%n", kept, total, out, Files.size(out));
    }
}
```

- [x] **Step 2: Build the development subset** (pick ~20 symbols across tiers; adjust after the daily table exists)

```
java -cp "replay/target/classes;core/target/classes" sg.phuc.lob.replay.Filter data/itch/12302019.bin data/itch/12302019.sub20.bin AAPL,MSFT,AMZN,GOOG,INTC,CSCO,NVDA,TSLA,AMD,QQQ,SPY,MU,SBUX,PYPL,ADBE,CMCSA,NFLX,BKNG,ILMN,AAL
```
Expected: a file of a few hundred MB; `kept` ≈ 1–3 % of `total`. Record.

- [x] **Step 3: Commit** — `git add replay && git commit -m "replay: Filter writes a symbol-subset ITCH file"`

---

### Task 6: Replay — throughput, latency histogram, allocation per message

**Files:** `replay/src/main/java/sg/phuc/lob/replay/Replay.java`

**Interfaces:** `Replay --file F [--symbols A,B] [--hist] [--out DIR]` → prints `Result`; `static Result run(Path, Set<String>, Listener, Supplier<OrderMap>, Engine.BookFactory, boolean hist)`.

- [x] **Step 1: Implement**

```java
package sg.phuc.lob.replay;

import org.HdrHistogram.Histogram;
import sg.phuc.lob.book.*;
import sg.phuc.lob.engine.*;
import sg.phuc.lob.itch.FrameReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

public final class Replay {
    public record Result(long messages, double wallSec, long p50, long p90, long p99, long p999, long max,
                         double bytesPerMsg, int liveOrders, String validation) {
        public double msgsPerSec() { return messages / wallSec; }
        @Override public String toString() {
            return String.format("messages=%d wall=%.1fs msgs/s=%,.0f alloc=%.1f B/msg live=%d%napply ns: p50=%d p90=%d p99=%d p99.9=%d max=%d%nvalidation=%s",
                messages, wallSec, msgsPerSec(), bytesPerMsg, liveOrders, p50, p90, p99, p999, max, validation);
        }
    }

    public static Result run(Path file, Set<String> symbols, Listener listener, Supplier<OrderMap> om, Engine.BookFactory bf, boolean hist) throws IOException {
        Engine eng = new Engine(om.get(), bf, listener, symbols);
        Histogram h = hist ? new Histogram(10_000_000_000L, 3) : null;
        var tmx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long alloc0 = tmx.getThreadAllocatedBytes(tid);
        long t0 = System.nanoTime();
        try (var fr = new FrameReader(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            int len;
            if (h != null) {
                while ((len = fr.next()) >= 0) { long a = System.nanoTime(); eng.apply(fr.buf(), len); h.recordValue(System.nanoTime() - a); }
            } else {
                while ((len = fr.next()) >= 0) eng.apply(fr.buf(), len);
            }
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        long alloc = tmx.getThreadAllocatedBytes(tid) - alloc0;
        long n = eng.messages();
        return new Result(n, wall,
            h == null ? 0 : h.getValueAtPercentile(50), h == null ? 0 : h.getValueAtPercentile(90), h == null ? 0 : h.getValueAtPercentile(99),
            h == null ? 0 : h.getValueAtPercentile(99.9), h == null ? 0 : h.getMaxValue(),
            (double) alloc / n, eng.liveOrders(), eng.validation().toJson());
    }

    public static void main(String[] args) throws IOException {
        Path file = null; Set<String> syms = null; boolean hist = false; Path out = null;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--file" -> file = Path.of(args[++i]);
            case "--symbols" -> syms = Set.of(args[++i].split(","));
            case "--hist" -> hist = true;
            case "--out" -> out = Path.of(args[++i]);
            default -> throw new IllegalArgumentException(args[i]);
        }
        Listener l = out == null ? new NullListener() : new DerivedWriter(out);
        Result r = run(file, syms, l, () -> new HashOrderMap(1 << 20), TreeBook::new, hist);
        if (l instanceof DerivedWriter dw) dw.close();
        System.out.println(r);
    }
}
```
(`DerivedWriter` arrives in Task 7; for this task's run, use `--out` omitted.)

- [x] **Step 2: Run — subset, then full day; record the naive baseline**

```
java -Xms4g -Xmx4g -XX:+UseG1GC -cp "replay/target/classes;core/target/classes;<HdrHistogram jar>" sg.phuc.lob.replay.Replay --file data/itch/12302019.sub20.bin --hist
java -Xms8g -Xmx8g -XX:+UseG1GC -cp "..." sg.phuc.lob.replay.Replay --file data/itch/12302019.bin
```
(Get the jar path once: `mvn -q dependency:build-classpath -pl replay -Dmdep.outputFile=cp.txt`.)
Expected subset: `validation` shows `structurallyClean`-relevant counters all 0; `liveAtC` small; `priorityViolations` small relative to `priorityChecked`; `crossedInMarket` 0 or explained. Expected full day: same counters at zero for the structural set; msgs/s in the low single-digit millions for this naive design; `alloc` tens of bytes per message.

**This is the `naive` row of the Phase 2 table.** Write all numbers, JVM flags and machine spec in [[05 Log/Daily Log]].

- [x] **Step 3: Commit** — `git add replay && git commit -m "replay: naive baseline with HdrHistogram and per-message allocation"`

---

### Task 7: DerivedWriter

**Files:** `core/src/main/java/sg/phuc/lob/engine/DerivedWriter.java`; test `DerivedWriterTest.java`

**Interfaces:** `DerivedWriter(Path dir)` implements `Listener`, `AutoCloseable`; writes `bbo.ndjson` (only on change of any of the four BBO fields), `executions.ndjson`, `trades.ndjson`, `daily.ndjson` (on close), with `sym` resolved via a `SymbolResolver` set by the engine — simplest: the writer receives `locate` and the engine exposes `symbol(int)`; give the writer a reference to the engine after construction via `setEngine(Engine)`.

- [x] **Step 1: Failing test** — feed the `EngineTest.addExecDeleteAndBest` sequence with a `DerivedWriter` on a temp dir; assert `bbo.ndjson` has exactly the BBO *changes* (add bid → 1 line, add ask → 1, exec → 1 (shares changed), delete → 1) and `executions.ndjson` has 1 line with `"ref":1,"shares":40,"price":650000`.

- [x] **Step 2: Implement** — per-locate `int[] lastBid, lastBidSh, lastAsk, lastAskSh` (size 65536); `onBbo` compares and writes `{"ts":…,"sym":"…","bid":…,"bidSh":…,"ask":…,"askSh":…}`; `onExecution` writes `{"ts","sym","ref","side","shares","price","match","printable"}`; `onTrade` writes `{"ts","sym","kind","shares","price","match","cross"}`; per-locate counters (adds/execs/cancels/deletes/replaces/volE/volC/volP/volQ) accumulated from the listener calls plus an `onAdd` hook (add `default void onAdd(long ts, int locate, long ref, byte side, int shares, int price)` to `Listener` and call it from `Engine.insert`); `close()` writes `daily.ndjson` with `liveAtC` per symbol taken from a final pass over the engine's books. `BufferedWriter` 1 MB per file; `StringBuilder` reused.

- [x] **Step 3: Run to verify pass**, then produce derived output for the subset: `Replay --file data/itch/12302019.sub20.bin --out data/derived/12302019.sub20` → four files exist; `wc -l`. Record.

- [x] **Step 4: Commit** — `git commit -am "engine: DerivedWriter (bbo/executions/trades/daily); onAdd hook"`

---

### Task 8: Golden determinism test in CI

**Files:** `replay/src/test/java/sg/phuc/lob/replay/GoldenReplayTest.java`; fixture `replay/src/test/resources/golden/subset-5min.bin`; `expected.sha256`

- [x] **Step 1: Cut the fixture** — a 5-minute window of 3 symbols. Add a `--from-ts --to-ts` pair to `Filter` (keep locate-0 always; keep chosen locates only when `timestamp` within range **plus all `R`/`H` for them**). Cut 9:30:00–9:35:00 (`34_200_000_000_000` to `34_500_000_000_000` ns) for `AAPL,MSFT,INTC`. Expected size: single-digit MB.

- [x] **Step 2: Test** — run `Replay.run` twice on the fixture with a `DerivedWriter` into two temp dirs; SHA-256 over the sorted file contents; assert equal; if `expected.sha256` exists, assert equal to it. Same shape as the Binance-era golden test.

- [x] **Step 3: Pin the hash, run `mvn -q verify`, commit** — `git add replay && git commit -m "replay: golden determinism test on 5-minute fixture, hash pinned"` and `git tag phase-1-baseline`.

## Self-review

- **Spec coverage:** §5.1 all classes (Tasks 2–4, 7); §5.2 `Probe`, `Filter`, `Replay` (Tasks 1, 5, 6); §6 flow implemented in `Engine.exec`; §8 every non-throwing row has a counter in `Validation` and a code path in `Engine`; §10 unit + golden (Tasks 2–4, 8); §11 the `naive` row (Task 6). Match tracking and the corruption test are Phase 3.
- **Placeholder scan:** Task 7 Step 2 and Task 8 Step 1 describe rather than list code; both are fully specified by their tests and by the Binance-era versions of the same classes. Everything else is code with commands and expected output.
- **Type consistency:** `Engine.BookFactory` used as `TreeBook::new` in tests and `Replay`; `Listener` methods match `DerivedWriter` and `Engine` call sites; `Validation` fields referenced in tests exist; `Msg` layouts match [[04 Reference/ITCH 5.0 Message Layouts]] offsets.
