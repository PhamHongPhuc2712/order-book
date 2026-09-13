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
| Flags | `-Xms8g -Xmx8g -XX:+UseG1GC` (full day) · `-Xms4g -Xmx4g` (subset with `--hist`) · second table with `-XX:+UseZGC` |

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
| +long | `HashMap<Long,Order>` | `LongObjectMap`: open addressing, `long[]` keys, backward-shift delete | no boxed `Long` and no `HashMap.Node` per put; the biggest allocation source |
| +array | `TreeMap` per side | `ArrayBook`: sorted `int[]` prices + parallel `Level[]`, binary search + `arraycopy` | no tree nodes; best is index 0; cache-friendly for the typical level count |
| +pool | `new Order` per add | `OrderPool` free list threaded through `Order.next` | steady-state hot path stops allocating |
| +dedupe | `onBbo` on every book change | `onBbo` only when one of the four BBO fields changed | removes listener calls that carry no information |

## Results

_(tables from `ops/perf_table.py data/perf/12302019.txt` go here)_

## GC in the tail (JFR)

_(Task 6: naive vs final on the subset, G1 and ZGC — GC count, longest pause, alongside p99.9/max)_

## Interpretation

_(three sentences per table, written only after the numbers exist)_
