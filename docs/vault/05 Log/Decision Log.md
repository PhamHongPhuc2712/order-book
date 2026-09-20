---
title: Decision Log
revised: 2026-09-12
tags: [log, adr]
---

# Decision log

Context → options → decision → consequences. Superseded decisions stay, marked, so the reasoning trail is visible.

## Active

### D1 — Java 21 for the hot path (2026-09-11, kept)
BofA 14365 names Java/EJB, C++, .NET, Python. Rust/Go would miss the stack. Java makes GC a first-class latency concern — deliberately. Maven, JUnit 5, jqwik, HdrHistogram; nothing else at runtime.

### D7′ — Scaled `int` at native scale 4 (2026-09-12, revised from 10⁸)
ITCH prices are already `Price(4)` integers. No decimal parsing; `650000` = $65.0000. Shares `int`, references and match numbers `long`.

### D8 — Naive first, then measured optimisation (2026-09-11, kept — now the spine of Phase 2)
The baseline must be what a competent engineer writes first: `HashMap<Long,Order>`, `TreeMap` levels, an object per order. Each replacement is behind an interface, property-tested for equivalence, and re-measured. The before/after is only a story if the "before" is credible.

### D15 — NASDAQ TotalView-ITCH 5.0 sample files over Binance L2 live capture (2026-09-12)
**Options.** Keep Binance (live, L2, crypto); Tardis historical crypto L2; NASDAQ ITCH (files, L3, US equities).
**Decision.** ITCH.
**Gained.** Real equities; order-level data → exact queue attribution; hundreds of millions of messages/day → throughput and memory are real engineering; crosses/halts/LULD; full determinism; the same subject matter as the target team.
**Lost.** Live stream, reconnect/gap-recovery, always-on VM, ops layer, external snapshot oracle, wire-to-local staleness.
**Consequence.** Correctness by invariants + priority check + reference implementation (D17). The APS&E mapping weakens; the SWE mapping strengthens.

### D16 — Decompress once; memory-map in Phase 2 (2026-09-12)
`GZIPInputStream` caps throughput well below disk and hides the parser's real cost. Decompressed `.bin` on disk (~3× gz). `FrameReader` over `BufferedInputStream` for the naive row; `MappedFrameReader` (`MemorySegment`) as the first optimisation row, so the I/O gain is measured separately.

### D17 — Correctness by invariants, priority check, and an independent implementation (2026-09-12)
No external snapshot exists. Checks: structural invariants; time-gated crossed-book; match-number uniqueness and `B` integrity; **every market-hours `E` hits the head of the best level on its side**; message counts and one symbol's minute-by-minute top-of-book vs MeatPy. Agreement with MeatPy is recorded as *agreement*, not ground truth.

### D18 — Single-threaded v1 (2026-09-12)
Clean measurement and simple correctness first. Symbol-partitioned parallelism is the named extension (locate → shard).

### D19 — Hard gate before the stretch (2026-09-12)
No matching engine, risk checks or FIX until `v0.1.0` (README with numbers, demo). A half-built FIX gateway is worse than none.

### D20 — Three days: `12302019`, `01302020`, `S120825` (2026-09-12)
Smallest 2019 file for development (3.52 GB gz); a normal busy 2020 day; one recent day (Dec 2025, 8.8 GB) so numbers aren't six years stale. Cross-day results reported as ranges.

### D21 — Subset file for development, full file for numbers (2026-09-12)
`Filter` writes every locate-0 message plus all messages for ~20 chosen symbols, preserving order and framing. Iteration in seconds. Numbers only from full-day runs.

### D22 — `U` replace: remove old, add new ref at the back of the level (2026-09-12)
Per spec §1.4.5: the original's remaining shares "must be removed"; new reference number thereafter; side/stock/MPID inherited. Time priority is lost. This directly affects queue-position correctness.

### D23 — `P` trades are unsigned; signed metrics use `E`/`C` only (2026-09-12)
Spec: `P` Buy/Sell "will always be 'B' regardless of resting side" since 14 Jul 2014. `P` and `E/C` are disjoint sets of executions. `P` counts toward volume; never toward effective spread, impact or OFI's trade component. `C` non-printable excluded from both.

### D24 — Queue episodes computed in Java, aggregated in Python (2026-09-12)
Python over 300 M messages is impractical. `QueueProbe` is a `Listener`; Python reads `episodes.ndjson`.

### D25 — Match-number tracking is a validation mode (2026-09-12)
A `LongSet` of ~10⁸ entries costs gigabytes; enabled with `--validate` for the correctness run, off for perf rows.

### D26 — Crossed book within 1 s of a trading resumption is `crossedAtResume`, not an error (2026-09-13)
**Context.** Full-day naive run showed `crossedInMarket` 642; all at halt/IPO resumptions (`H` state T received during market hours), where the halt cross's `C` fills and stale-order `D`s keep arriving after the state flip while the displayed book is still crossed (observed max lag 1.21 ms; window set to 1 s and named `Engine.RESUME_WINDOW_NS`).
**Options.** Ignore crossed checks entirely after a resumption; require the `Q` cross message to gate (it arrives *before* the trailing fills, so it can't); time window after `H` T.
**Decision.** Time window, reported as a separate counter with its max observed lag, so a future day where the lag grows is visible rather than hidden. `crossedInMarket` stays the "unexplained" bucket and is expected to be 0.
**Consequence.** Spec §9 item 2 reads: `crossedInMarket` 0; `crossedAtResume` reported with max lag.

### D16′ — `MappedByteBuffer` windows instead of `MemorySegment` (2026-09-13, amends D16)
`java.lang.foreign` is still a preview API on JDK 21 (final in 22); it would force `--enable-preview` on every build and run. `MappedFrameReader` maps 1 GB `MappedByteBuffer` windows and re-maps at a frame start when a frame would cross the end. Same measurement, no preview flags.

### D27 — Perf runs at 4 GB heap; the ZGC table uses Generational ZGC (2026-09-14)
**Context.** 13.7 GB usable RAM, 8.25 GB input file, ~6 GB of desktop apps. JDK 21's default ZGC multi-maps the heap (3 virtual views), which Windows counts 3x in the working set; the 8g ZGC pass thrashed for 90 min on `+long` and the memory watchdog killed the wrapper twice.
**Options.** Keep 8g and accept thrash; drop to 4g with default ZGC; drop to 4g with `-XX:+ZGenerational`.
**Decision.** 4g + Generational ZGC for the ZGC table, with a G1@4g cross-check (naive 219 s, final 166 s) proving the G1 table's 8g heap was not what limited it. The abandoned 8g ZGC rows stay in the results file labelled as such.
**Consequence.** `ops/bench.sh` takes heap and GC parameters and tags every record `gc=<GC>@<heap>`; tables at different heaps never mix.

### D28 — A match number may carry one printable and one non-printable execution leg (2026-09-14, refines §9 item 3)
**Context.** Match tracking on the 9:30–9:35 fixture reported 14 "duplicate" match numbers. Every one is two `C` messages at the same nanosecond, opposite sides, one `Printable=N` and one `Printable=Y`: a trade between two *resting* displayed orders, reported once per leg, with one leg flagged non-printable so the tape is not double-counted (spec §4.6).
**Decision.** Track printable legs (`E`, printable `C`, `P`, `Q`) and non-printable legs in separate sets. A repeat within either set is `duplicateMatch`; a match present in both is counted as `twoSidedMatches` and reported. `B` may reference either.
**Consequence.** `duplicateMatch` is expected to be 0 on all days; `twoSidedMatches` is a new reported number (14 on the fixture).

### D29 — Queue episodes distinguish `exhausted` from `moved`; P(fill) is reported as a range (2026-09-16)
**Context.** The reconstructed book does not contain the hypothetical joiner. When every order ahead of it has executed or cancelled and nobody joined behind it, the level disappears and the side's best price moves — under the plan's rule that is `censor=moved`, indistinguishable from the price moving away while orders were still ahead. On the 5-minute fixture that case was 37 % of episodes against 7 % observed fills, so the plan's "lower bound" would have been far below the truth.
**Options.** Keep one `moved` bucket (badly biased lower bound); simulate the joiner as a resting order and treat the next contra event as its fill (a matching model, not an observation); split the bucket and report both bounds.
**Decision.** New censor kind `exhausted` (ahead reached 0, then the level vanished) with `endTs` on every episode. `p_fill` counts observed fills only; `p_fill_upper` also counts exhausted episodes at their exhaustion time. Both are reported everywhere the curve appears.
**Consequence.** The headline conditional curve is a band, not a line. Sampling is also pinned precisely: the first top-of-book change at or after each grid point, joiner arriving immediately after it, starting at the first grid point after `Q`.

### D30 — Parquet partitioned by date only, rows ordered by (symbol, ts) (2026-09-16)
**Context.** The plan said `symbol=/date=` partitions. A full day has ~8,900 symbols; per-symbol partitions of a 130 M-row quote table mean thousands of tiny files and a partitioned writer holding thousands of open handles on a 13.7 GB machine.
**Decision.** One file per table per day (`data/parquet/<table>/date=<day>/data.parquet`, ZSTD, 1 M-row row groups), written by DuckDB streaming from NDJSON with rows ordered by (sym, ts). Row-group statistics make a symbol filter skip almost everything; `spreads.py` and `ofi.py` run per batch of 250 symbols against literal `IN` lists so the ASOF and window joins never see the whole day at once.
**Consequence.** `convert.py` is a single COPY per table; the 10.6 GB `bbo.ndjson` never has to fit in memory. The research layer module is `queues.py`, not `queue.py`, because the latter shadows the standard library.

### D31 — One quote row per (symbol, nanosecond) in the Parquet quote table (2026-09-17)
**Context.** Re-running the day-1 pipeline changed the effective spread of top100/open from 24.28 to 24.35 bps. `bbo.ndjson` carries several lines with the same timestamp (several messages at one ns, and a message's BBO reported after each step); the ASOF join `b.ts < e.ts` picks among equal-ts rows arbitrarily, and DuckDB's unordered sort had already shuffled them.
**Decision.** `convert.py` keeps, per (sym, ts), the last line in file order — the state after everything at that instant — using `row_number() over ()` with insertion order preserved (unit-tested). Bootstrap blocks and per-symbol OFI rows are sorted before use.
**Consequence.** Two full runs now agree to floating-point rounding (spreads 4e-14 bps, OFI 1e-20; queue tables byte-identical). 124,767,189 → 122,694,731 quote rows on `12302019`. Within-nanosecond intermediate states are not observable to the research layer, which is the right reading of a nanosecond timestamp.

### D32 — A pipeline step that dies fails the day; the priority dump covers the population (2026-09-17)
**Context.** The overnight `01302020` chain printed `01302020 complete` while `report.py` had in fact died inside `spreads` after 6,702 s: `make.ps1` piped every step through `Tee-Object` and never looked at an exit code, and the process left no traceback (it was killed, not thrown), so the log looked like a success with no `numbers_01302020.md` behind it. Separately, `--dump-priority 500` classified 500 violations per day, which cannot support the claim that *no* violation is off the best price.
**Decision.** Every step runs through `Start-Process` with stdout and stderr redirected to `data/derived/<day>/<step>.txt[.err]`, its exit code checked, its stderr echoed into the chain log, and the closing "complete" line asserts the numbers file exists. The dump cap is 50,000 — above any day's violation count (33,674 on `S120825` is the largest of the three) — so the classification is of the whole population, and `classify_priority.py` now prints `N of M` so a cap can never be mistaken for completeness.
**Consequence.** A stage that is killed stops the day instead of poisoning the write-up; `bash ops/run_days.sh` stops at the first bad day and names its log. Re-dumping the two days that ran under the old cap costs one `--no-derived` replay each, which writes `priority.ndjson` and `validation.json` only and leaves the 10-24 GB of derived NDJSON untouched.

## Superseded (Binance era, 2026-09-11) — kept for the record

- **D2/D3/D4** Binance spot, separate `/ws/` connections, `@depth@100ms` + `@trade`, BTCUSDT/ETHUSDT/thin pair → superseded by D15/D20.
- **D5** Raw NDJSON before parsing, hourly rotation, zstd → no live capture; the analogue is "read before parse, never a partial book" in `FrameReader`.
- **D6** Two clocks, never mixed → only exchange timestamps exist now; `nanoTime` for durations only.
- **D10** Record snapshots to raw for deterministic replay → files are inherently deterministic.
- **D11** Oracle reports tolerance → no oracle; D17.
- **D12** Oracle Cloud Always Free VM → no 24/7 process.
- **D13** Queue model as bounds → exact under Level 3.
- **D14** `synchronized` `BookEngine` → single-threaded, no locks (D18).

## Rejected (keep rejected)

- **Add a paper-trading strategy.** Turns a systems project into a trading project; invites "what's your Sharpe."
- **Multi-day continuous history from a paid vendor.** The free sample days are enough for the claims made; say "sample days" plainly.
- **Multi-threaded parse in v1.** Measure single-threaded first; the partition-by-locate design is described in the README.
- **Tick-indexed flat array book in v1.** Sorted arrays first; tick array only if level counts justify it (record the measurement either way).
- **Kafka / any broker between stages.** Files are the right boundary at this scale.
- **Consolidated (all-exchange) volume as a check.** ITCH is NASDAQ-only; the comparison is wrong, not just imprecise.

### D33 — A re-run on other hardware may supply research rows, never the timing rows (2026-09-20)
**Context.** All three days were re-run on a second machine (Linux, 12 cores, 23 GB RAM) to compute day 3's spread and OFI, which the reference laptop never had the disk to finish. The re-run reproduced every correctness counter byte-identically, but it also produced its own throughput numbers — 743,982 / 1,667,196 / 1,240,794 msgs/s against the laptop's 890,659 / 768,144 / 653,025 — and `crossday.py` writes both kinds of row into the same table.
**Alternatives.** (a) Publish the whole regenerated table, and relabel README and `docs/perf.md` to say which machine each number came from. (b) Hand-edit `crossday.md` back. (c) Regenerate against the preserved run artifacts.
**Decision.** (c). `crossday.py --derived results/runs` reads `probe.txt`, `replay.txt`, `validation.json` and `daily.ndjson` from `research/results/runs/<day>/` — the copies kept when `data/` was cleared — so scale and timing come from the reference laptop's own logs while spreads and OFI come from the freshly computed CSVs. (a) was rejected because `docs/perf.md`'s whole argument is one machine held constant across six cumulative rows, and mixing hardware into the comparison table weakens it for no gain. (b) was rejected outright: nothing in `crossday.md` is typed by hand.
**Consequence.** The day-3 cells are filled and the file is still fully generated; README, `crossday.md` and `docs/perf.md` stay consistent. The fallback added on 2026-09-18 so the write-up could regenerate without `data/` turned out to be the mechanism for keeping provenance straight, which was not why it was built. Performance on other hardware is recorded in the daily log, not published.
