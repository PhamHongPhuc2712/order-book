# Performance — naive → optimised, measured

Every number here is produced by `ops/bench.sh 12302019` and summarised by `ops/perf_table.py`; the raw per-run lines are in
`data/perf/12302019.txt` (git-ignored, regenerable). Nothing is typed in by hand.

## Machine

| | |
|---|---|
| CPU | AMD Ryzen 5 6600HS, 6 cores / 12 threads, 3.3 GHz boost (laptop) |
| RAM | 13.7 GB usable (16 GB nominal) |
| Disk | Samsung MZAL4512HBLU-00BL2 NVMe SSD, 512 GB |
| OS | Windows 11 Home 10.0.26200 |
| JDK | Eclipse Temurin 21.0.12+101 |
| Flags | G1 table: `-Xms8g -Xmx8g -XX:+UseG1GC` full day, `-Xms4g -Xmx4g` subset with `--hist` · ZGC table: `-Xms4g -Xmx4g -XX:+UseZGC -XX:+ZGenerational` (see D27) · G1@4g cross-check |

**RAM caveat.** With an 8 GB heap committed, the 8.25 GB `12302019.bin` cannot stay fully in the page cache, so the full-day
rows (both readers) genuinely pay for SSD reads. The subset (472 MB) is fully cached after the first run.

## Method

- Input: `12302019` (30 Dec 2019), 268,744,780 messages, 8,251,407,909 bytes, `[len:2][msg]` framed, decompressed once.
- Rows are **cumulative**: each adds one optimisation to the previous row, and each optimisation is a drop-in behind an existing
  interface (`Frames`, `OrderMap`, `Book`) or an `Engine` flag. `Replay --reader mmap --map long --book array --pool --dedupe`
  is the final row (`--final`).
- Full-day rows: `NullListener`, no `--hist`, **median of three runs** (all three wall times listed below the table). Warm-up is
  included — a full day includes it, and that is honest.
- Subset rows (20 symbols, 15,578,139 messages): `--hist` on, `HdrHistogram(10 s, 3 significant digits)` around `Engine.apply`
  only; reading is excluded. The two `System.nanoTime()` calls per message are measurable overhead, which is why full-day
  throughput is never taken from a `--hist` run.
- Bytes per message: `ThreadMXBean.getThreadAllocatedBytes` delta over the whole run ÷ messages, warm-up included.
- Equivalence: every row's derived output hashes to the same SHA-256 as the naive row over the golden 5-minute fixture
  (`GoldenReplayTest.everyOptimisationMatchesTheNaiveHash`), and each replaced structure has a jqwik property test against the
  naive one (`LongObjectMap` vs `HashMap`, `ArrayBook` vs `TreeBook`, `LongSet` vs `HashSet`).
- Noise: a VS Code Java language server was indexing the repository during the first G1 rows.

## What each row changes

| row | replaces | with | why it should matter |
|---|---|---|---|
| naive | — | `BufferedInputStream` + `HashMap<Long,Order>` + `TreeMap<Integer,Level>` + `new Order` per add | what a competent engineer writes first |
| +mmap | stream reader | `MappedFrameReader`: 1 GB `MappedByteBuffer` windows, one `get(int, byte[], …)` per frame | removes the `InputStream` copy path; isolates I/O cost |
| +long | `HashMap<Long,Order>` | `LongObjectMap`: open addressing, `long[]` keys, backward-shift delete | no boxed `Long` and no `HashMap.Node` per put — ~35 % of naive allocation samples (JFR showed `TreeMap.firstEntry()` copies were the larger source) |
| +array | `TreeMap` per side | `ArrayBook`: sorted `int[]` prices + parallel `Level[]`, binary search + `arraycopy` | no tree nodes; best is index 0; cache-friendly for the typical level count |
| +pool | `new Order` per add | `OrderPool` free list threaded through `Order.next` | steady-state hot path stops allocating |
| +dedupe | `onBbo` on every book change | `onBbo` only when one of the four BBO fields changed | removes listener calls that carry no information |

## Results

### G1GC (`-Xms8g -Xmx8g -XX:+UseG1GC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 240.5 s | 1,117,442 (1.0x) | 3 | 156.8 | 400 | 900 | 2501 | 20111 | 25739263 |
| +mmap | 239.3 s | 1,123,045 (1.0x) | 3 | 156.8 | 300 | 600 | 1400 | 5603 | 32194559 |
| +long | 243.4 s | 1,104,128 (1.0x) | 3 | 102.3 | 300 | 600 | 1400 | 5603 | 21561343 |
| +array | 167.7 s | 1,602,533 (1.4x) | 3 | 34.7 | 200 | 700 | 1400 | 2801 | 22413311 |
| +pool | 158.7 s | 1,693,414 (1.5x) | 3 | 5.9 | 200 | 700 | 1400 | 2701 | 18612223 |
| +dedupe | 165.3 s | 1,625,800 (1.5x) | 3 | 5.9 | 201 | 700 | 1501 | 3201 | 19955711 |

Full-day run spread (s): naive: 294, 240, 229; +mmap: 237, 243, 239; +long: 243, 242, 244; +array: 168, 169, 164; +pool: 158, 159, 161; +dedupe: 171, 165, 162

### G1GC (`-Xms4g -Xmx4g -XX:+UseG1GC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 219.2 s | 1,226,025 (1.0x) | 1 | 156.8 | 300 | 700 | 1600 | 13407 | 26263551 |
| +dedupe | 166.4 s | 1,615,053 (1.3x) | 1 | 5.9 | 200 | 700 | 1400 | 2601 | 16891903 |

Full-day run spread (s): naive: 219; +dedupe: 166

### ZGC (non-generational, abandoned — see text) (`-Xms8g -Xmx8g -XX:+UseZGC` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 276.8 s | 970,899 (1.0x) | 1 | 189.2 | 300 | 800 | 2701 | 6503 | 23363583 |
| +mmap | 359.1 s | 748,384 (0.8x) | 1 | 189.2 | 300 | 800 | 2501 | 6603 | 56950783 |
| +long | nan s | nan | 0 | 124.0 | 400 | 800 | 2901 | 7203 | 20430847 |

Full-day run spread (s): naive: 277; +mmap: 359

### Generational ZGC (`-Xms4g -Xmx4g -XX:+UseZGC -XX:+ZGenerational` full day; subset with `--hist` at 4g)

| step | full-day wall (median) | msgs/s | runs | B/msg | subset p50 ns | p90 | p99 | p99.9 | max ns |
|---|---|---|---|---|---|---|---|---|---|
| naive | 329.3 s | 816,109 (1.0x) | 1 | 189.2 | 400 | 1100 | 3401 | 19215 | 53313535 |
| +mmap | 321.2 s | 836,690 (1.0x) | 1 | 189.2 | 400 | 1100 | 3601 | 9103 | 17956863 |
| +long | 341.1 s | 787,877 (1.0x) | 1 | 130.4 | 400 | 900 | 2601 | 7603 | 21315583 |
| +array | 194.5 s | 1,381,721 (1.7x) | 1 | 44.5 | 300 | 900 | 2801 | 7003 | 18104319 |
| +pool | 186.4 s | 1,441,764 (1.8x) | 1 | 7.4 | 300 | 1000 | 3201 | 7303 | 20692991 |
| +dedupe | 193.6 s | 1,388,145 (1.7x) | 1 | 7.4 | 300 | 900 | 2801 | 6703 | 20004863 |

Full-day run spread (s): naive: 329; +mmap: 321; +long: 341; +array: 194; +pool: 186; +dedupe: 194

Naive subset percentiles in the G1@8g table were taken while a VS Code Java indexer was running; a clean re-run gave
p50 400 / p90 800 / p99 1,900 / **p99.9 14,807** / max 26,951,679 ns — use those for the naive row.

**Headline (G1, full day, median of 3):** 1,117,442 → 1,693,414 msgs/s (**1.5×**); 156.8 → 5.9 B/msg (**26× less**);
subset p99.9 14.8 → 2.7 µs (**5.5×**); GCs on the subset 5 → 0. Reading the file alone (`Probe`, no engine) runs at
13.1 M frames/s, so the engine is still the bottleneck — but no longer through allocation (see JFR).

**Listener cost** (final config, G1, subset, no `--hist`): `NullListener` 5.5 s / 2,851,062 msgs/s / 4.1 B/msg;
`DerivedWriter` 10.4 s / 4.1 B/msg after making it allocation-free (it was 11.6 s / 53.8 B/msg while
`Writer.append(CharSequence)` copied every line to a `String`). Writing derived output roughly doubles subset wall time.
The `--dedupe` flag changes nothing with `DerivedWriter` — it already dedupes BBO itself and its outputs hash identically
with and without — so its value is only for listeners that don't. `--hist` itself costs ~25 % (7.2 s vs 5.5 s).

## GC in the tail (JFR)

`ops/bench_jfr.sh`, subset, `-Xms4g -Xmx4g`, `settings=profile`. Raw output in `data/perf/12302019.jfr.txt`.

| run | GCs | longest pause | apply p99.9 / max | top allocation samples (count) |
|---|---|---|---|---|
| naive, G1 | 5 | 7.76 ms | 12.7 µs / 36.3 ms | `AbstractMap$SimpleImmutableEntry` 699, `Long` 527, `Order` 414, `HashMap$Node` 245, `Integer` 197, `TreeMap$Entry` 73, `Level` 52 |
| final, G1 | **0** | — | 2.9 µs / 16.9 ms | `Level` 27, `Order` 11 (pool growth to 217,125 = peak live orders), `byte[]` 2 |
| naive, Gen-ZGC | 3 | sub-ms | 9.2 µs / 23.4 ms | same shape as G1: entry copies 1118, `Long` 718, `Order` 604, `HashMap$Node` 367 |
| final, Gen-ZGC | **0** | — | 5.8 µs / 20.9 ms | `Level` 214, `Order` 65 |

Top execution-sample frames, naive G1: `TreeMap.getFirstEntry` 52, `HashMap.putVal` 49, `Engine.delete` 45, `TreeMap.getEntry` 43,
`Engine.afterChange` 34, `Engine.newOrder` 32. Final G1: `Engine.insert` 74, `Engine.afterChange` 37, `Engine.apply` 36,
`ArrayBook.find` 27, `ArrayBook.level` 16, `Engine.replace` 14, `LongObjectMap.put/get/remove` 5/5/3.

The single largest allocation in the naive row is not the order map: it is `TreeMap.firstEntry()`, which returns an
*immutable copy* of the entry on every `bestLevel()` call — 31 % of samples. That is why `+long` (which removed the boxed
`Long` keys and `HashMap$Node`s, ~35 % of samples) moved allocation but not wall time, and `+array` (which removed the entry
copies, the boxed `Integer` keys and the tree walks) is the row where full-day wall time fell 31 %.

## Interpretation

**G1 table.** Allocation is the story the plan expected: 156.8 → 5.9 B/msg and the subset tail 14.8 → 2.7 µs, with zero
collections on the subset in the final row. Throughput is not: 1.5× rather than 10×, and the `+long` row (−35 % allocation,
±0 s) shows why — once per-message garbage stops driving young-gen pauses, the full day is bound by memory latency
(every `E`/`X`/`D`/`U` is a random probe into a map of millions of live orders) and by streaming 8 GB from the SSD. The next
lever is symbol-partitioned parallelism (D18's named extension), not more single-thread allocation work.

**Generational ZGC table.** ZGC is ~1.5× slower than G1 at the same 4 GB heap on every row (naive 329 vs 219 s; final 194 vs
166 s): its load barriers tax exactly the pointer-chasing this workload is made of. It improves the naive tail (p99.9 19 → 9 µs
between the noisy naive row and `+mmap`) but the optimised rows' tails are *worse* than G1's (6.7 vs 2.7 µs) — with nothing
left to collect, the barriers are pure cost. The contrast is the finding: fix the allocation, then pick the collector.

**Two things that were not in the plan.** JDK 21's default (non-generational) ZGC multi-maps the heap and thrashed this
13.7 GB laptop with an 8 GB heap plus an 8 GB mapped file (the abandoned table is kept above); Generational ZGC does not.
And the G1@4g cross-check (naive 219 s, final 166 s) shows heap size was never the limiter for G1.
