---
title: ITCH 5.0 Level-3 Order Book Reconstruction — Design Spec
date: 2026-09-12
status: draft — awaiting approval
supersedes: 2026-09-11-orderbook-design (Binance L2 live capture)
tags: [spec, design, itch]
---

# ITCH 5.0 Level-3 Order Book Reconstruction — Design Spec

> **Approval gate.** Per the superpowers `brainstorming` skill: plans execute only after this spec is reviewed. Self-review is at the bottom and has been completed. Read, edit, then execute [[03 Plans/2026-09-12-phase-1-parse-and-reconstruct]].

## 1. Goal

Build a Java system that (a) parses full-day NASDAQ TotalView-ITCH 5.0 binary files, (b) reconstructs the Level-3 limit order book for every symbol with **correctness established by invariants and a price-time-priority check against the feed's own executions**, (c) is optimised in measured steps from a credible naive baseline to an allocation-free hot path, and (d) produces one exact microstructure result — **queue-position fill probability** — on real US equities.

Audience: a Global Markets Technology interviewer at BofA (req 14365). Every decision below is one to defend in that room.

## 2. Non-goals

- Not a live feed handler. No network, no MoldUDP64, no GLIMPSE. (Stated as the obvious next step.)
- Not a trading system. The stretch matching engine is a *validated model of NASDAQ's*, not a venue.
- Not multi-threaded in v1. Single-threaded replay; symbol-partitioned parallelism is a listed extension.
- Not a full market-structure model. Crosses and halts are *tracked* for correctness gating; auction dynamics are optional research.
- No UI beyond a static replay demo of one symbol through the open.

## 3. Data

**Source:** `https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/` — NASDAQ's public sample server. One file = one full trading day, every message, every symbol (~8,000 instruments, including NYSE-listed names traded on NASDAQ).

**Files used (D20):**

| File | Date | gz | Role |
|---|---|---|---|
| `12302019.NASDAQ_ITCH50.gz` | 30 Dec 2019 | 3.52 GB | Development + primary numbers |
| `01302020.NASDAQ_ITCH50.gz` | 30 Jan 2020 | 5.60 GB | Cross-day consistency |
| `S120825-v50.txt.gz` | 8 Dec 2025 | 8.78 GB | Recency; run last, full-day only |

Each has a `.md5sum`. Verify before use.

**Format (from the spec, verified):** a sequence of messages; all integers big-endian unsigned; alpha fields ASCII left-justified space-padded; **timestamps 6 bytes = nanoseconds since midnight**; **prices 4 bytes with 4 implied decimals** (`Price(4)`, max 200,000.0000). Common header on every message: `type[1] locate[2] tracking[2] timestamp[6]`. Full layouts in [[04 Reference/ITCH 5.0 Message Layouts]].

**Framing of the sample files (unverified until Phase 1 Task 1):** each message preceded by a 2-byte big-endian length. Task 1 confirms by checking that the prefix equals the spec length for the type byte over the first 10,000 frames.

**Scale (estimate, measured in Task 1):** 2019 files ≈ 300–400 M messages, 10–12 GB decompressed. Dec 2025 file likely > 1 B messages.

## 4. Architecture

```
  <day>.NASDAQ_ITCH50.gz ──(gunzip once, Phase 4)──► <day>.bin      ──(Filter, Phase 4)──► <day>.subset.bin
                                                        │
                                                        ▼
   ┌───────────────────────── Java 21 — single thread ─────────────────────────┐
   │  FrameReader  [len:2][msg] → byte[] buf, int len        (no allocation)   │
   │       │                                                                   │
   │       ▼                                                                   │
   │  Engine.apply(buf, len)                                                   │
   │    ├─ 'R' → symbols[locate], books[locate] = new Book                     │
   │    ├─ 'S' → session state  O S Q M E C                                     │
   │    ├─ 'H' → tradingState[locate]                                          │
   │    ├─ 'A','F' → OrderMap.put(ref) ; Book.add → Level.append (FIFO)        │
   │    ├─ 'E','C' → OrderMap.get(ref) ; shares -= ; remove if 0 ; Validation   │
   │    ├─ 'X' → shares -= ; 'D' → remove ; 'U' → remove old, add new (back)   │
   │    ├─ 'P','Q','B' → trade / cross / broken (no book effect) ; Validation   │
   │    └─ others → skip                                                       │
   │       │                                                                   │
   │       ├─► Validation counters (structural, crossed-gate, match ids,       │
   │       │   priority, live-at-C)  + first-N offender samples                │
   │       └─► Listener → derived/ bbo, executions, episodes, daily            │
   │  Metrics: HdrHistogram(apply ns), ThreadMXBean allocated bytes, wall time │
   └───────────────────────────────────────────────────────────────────────────┘
                                                        │
                                                        ▼
   ┌────────────────────── Python research layer ──────────────────────────────┐
   │  convert.py  derived ndjson → Parquet (symbol=/date=)                     │
   │  DuckDB views: bbo, executions_signed, spreads ; ofi.py ; queue.py ;      │
   │  meatpy_diff.py ; report.py → numbers.md + charts                         │
   └───────────────────────────────────────────────────────────────────────────┘
```

**Why decompress once:** `GZIPInputStream` tops out well below disk speed and hides the parser's real throughput. Decompressed once, the reader can be memory-mapped (Phase 2) and the I/O cost is measurable separately.

**Why a subset file:** iterating on 12 GB takes minutes. `Filter` writes a file containing every locate-0 message (system events, MWCB) plus every message for the chosen locates, preserving order. Development runs read ~1 % of the data in seconds; the full day is for numbers.

## 5. Components

### 5.1 `core` — pure logic, no file I/O beyond `FrameReader`
- `itch.Itch` — static big-endian accessors over `byte[]`: `u16/u32/u48/u64`, `type`, `locate`, `timestamp`, `alpha`, `expectedLength(type)`.
- `itch.FrameReader` — `[len:2][msg]` frames from an `InputStream` into a reusable 64 KB buffer; throws with byte offset on truncation or bad length.
- `book.Order` — `ref, locate, side, shares, price, ts, prev, next, level`. Intrusive list node.
- `book.Level` — `price, shares, count, head, tail`; `append`, `remove`.
- `book.Book` — per-locate; naive: `TreeMap<Integer,Level>` per side (bids descending). Phase 2 swaps in `TickBook`. Interface `BookOps { level(side,price,create) ; bestBid() ; bestAsk() ; … }`.
- `book.OrderMap` — naive: `HashMap<Long,Order>`. Phase 2: `LongObjectMap` open addressing.
- `engine.Engine` — dispatch by type; owns `books[65536]`, `symbols[65536]`, `tradingState[65536]`, `OrderMap`, session state, `Validation`, `Listener`; optional locate filter.
- `engine.Validation` — counters + first-20 offender samples; `toJson()`.
- `engine.Listener` — `onBbo`, `onExecution`, `onTrade`, `onCross`, `onSystemEvent`; `NullListener`, `DerivedWriter`.
- `engine.QueueProbe` (Phase 3) — exact joiner episodes.

### 5.2 `replay` — mains and measurement
- `Replay` — `--file --symbols --listener --hist --alloc`; prints `Result` (messages, wall, msgs/s, p50/p90/p99/p99.9/max apply ns, bytes allocated per message, validation JSON).
- `Probe` — Task 1 framing check + type histogram + first `S` event.
- `Filter` — subset writer.
- `MeatPyExport` (Phase 3) — top-of-book at 1-minute marks for one symbol, for diffing.

### 5.3 `research` — Python 3.11
- `convert.py`, `db.py`, `metrics.sql`, `ofi.py`, `queue.py` (aggregation only — episodes computed in Java), `meatpy_diff.py`, `report.py`.

### 5.4 `ops` — scripts only
- `download.ps1` / `download.sh` (resume + md5), `gunzip.ps1`, `filter.ps1` wrapper.

### 5.5 `demo` — static HTML replay of one symbol 9:28–9:32 through the opening cross.

## 6. Data flow (one `E` message)

1. `FrameReader.next()` fills `buf`, returns `len`. Assert `len == Itch.expectedLength(type)` (else `Validation.badLength++`).
2. `Engine.apply(buf, len)`: type `'E'` → `ref = u64(buf,11)`, `exec = u32(buf,19)`, `match = u64(buf,23)`.
3. `o = orders.get(ref)`. If null → `Validation.unknownRef++`, sample, return.
4. If `exec > o.shares` → `Validation.execExceeds++`, clamp. `o.shares -= exec`; `o.level.shares -= exec`.
5. **Priority check** (if `marketHours && tradingState[o.locate]=='T'`): `checked++`; if `o.price != book.best(o.side)` or `o.level.head != o` → `priorityViolations++`, sample.
6. `Validation.matchSeen(match)` (dup check; optional full-day).
7. `listener.onExecution(ts, locate, ref, side, exec, o.price, match, printable=true)`.
8. If `o.shares == 0` → `level.remove(o)`; if `level.count == 0` → `book.removeLevel`; `orders.remove(ref)`.
9. If BBO changed → `listener.onBbo(...)`. Crossed-gate check.

`'U'` semantics (spec §1.4.5): remove the original entirely; the replacement gets a **new** reference and goes to the **back** of its level's queue (replace loses time priority). Side and locate are inherited.

`'C'` differs from `'E'` only in carrying its own execution price and a `Printable` flag; book effect identical.

## 7. Data model

**Scaled integers everywhere.** Price as `int` at native scale 4 (`650000` = $65.0000). Shares as `int`. Timestamps `long` ns since midnight. Reference and match numbers `long`.

**Derived outputs (NDJSON, regenerable):**

| file | one line per | fields |
|---|---|---|
| `bbo.ndjson` | change of best price or best shares, per symbol | `ts, sym, bid, bidSh, ask, askSh` |
| `executions.ndjson` | `E`/`C` | `ts, sym, ref, side (resting), shares, price, match, printable` |
| `trades.ndjson` | `P`/`Q` | `ts, sym, kind (P/Q), shares, price, match, crossType` |
| `episodes.ndjson` | queue probe | `t0, sym, side, price, aheadShares0, aheadOrders0, fillTs, censor (none/moved/eod/T), execAhead, cancelAhead, tier` |
| `daily.ndjson` | symbol | `sym, msgs, adds, execs, cancels, deletes, replaces, volE, volC, volP, volQ, liveAtC` |
| `validation.json` | run | all counters + samples |

**Parquet tables** mirror these, partitioned `symbol=/date=`.

## 8. Error handling — every failure named

| Failure | Detection | Response | Recorded |
|---|---|---|---|
| Truncated file / bad length prefix | `FrameReader` | throw `IOException` with byte offset — **never** a partial book | run fails loudly |
| Length ≠ spec length for type | `Engine.apply` | count; skip message | `badLength` |
| Unknown message type | dispatch | count; skip | `unknownType` |
| Add with existing ref (incl. `U` new ref) | `OrderMap.put` | count; sample; **replace** the stale entry | `duplicateRef` |
| `E/C/X/D/U` on unknown ref | `OrderMap.get` | count; sample; skip | `unknownRef` |
| Exec/cancel shares > remaining | apply | count; clamp to remaining | `execExceeds`, `cancelExceeds` |
| Message for locate with no `R` | `books[locate]==null` | count; create book lazily with symbol `"?"` | `noDirectory` |
| Crossed book during market hours, state `T` | after book change | count; sample | `crossedInMarket` |
| Execution not at best / not head | priority check | count; sample | `priorityViolations` / `priorityChecked` |
| Duplicate match number | `matchSeen` (validation mode) | count | `duplicateMatch` |
| `B` referencing unseen match | validation mode | count | `brokenUnknown` |
| Orders live at `C` | end of run | report count | `liveAtC` |
| `P` message side field | — | ignore side (always `B` post-2014); mark unsigned | — |

Nothing above throws except the first row. A validation run's success criterion is the *counters*, not the absence of exceptions.

## 9. Validation strategy (the correctness story)

| # | Check | Kind | Expected |
|---|---|---|---|
| 1 | Structural invariants (rows 4–6 above) | internal | **0** on all three days |
| 2 | Crossed book, gated `Q`→`M` and state `T` | internal | 0; any nonzero is investigated and explained |
| 3 | Match-number uniqueness; `B` refers to a seen match | internal | 0 dups; 0 unknown |
| 4 | **Price-time priority**: every `E` in market hours hits the head of the best level on its side | against the feed's own executions | violation rate reported; expect ≈ 0; nonzero cases classified (nanosecond ties, ISO sweeps, odd lots) |
| 5 | Message counts by type vs. MeatPy on the same file | independent implementation | equal |
| 6 | Top-of-book at 1-minute marks vs. MeatPy, one symbol, full day | independent implementation | equal; documented as *agreement*, not ground truth |
| 7 | Live orders at `C` | internal | reported; expected small |

**What is *not* claimed:** equality with consolidated (all-exchange) volume — ITCH is NASDAQ-only. Per-symbol NASDAQ-only daily volume isn't freely published, so external volume reconciliation is limited to any market-wide NASDAQ figure available for the date, and is reported as such.

## 10. Testing

| Layer | Tool | What |
|---|---|---|
| Unit | JUnit 5 | `Itch` accessors on hand-built byte arrays; `FrameReader` truncation/bad-length; `Level` append/remove; `Book` best/crossed; each `Engine` message path incl. `U` back-of-queue |
| Property | jqwik | random add/exec/cancel/delete/replace sequences: `TreeMap` book == `TickBook` (Phase 2); `HashMap` == `LongObjectMap`; level `shares == Σ order shares`, `count == list length` |
| Golden | JUnit | 5-minute subset → derived output byte-identical across runs; hash pinned; runs in CI |
| Corruption | JUnit | truncate mid-message → `IOException` with offset; flipped length prefix → detected |
| Validation | full-day run | counters (§9) reported in `validation.json`; CI runs the subset |
| Research | pytest + hypothesis | spread/OFI on fixtures; episode aggregation |

## 11. Success criteria

**Correctness**
- §9 items 1–3: zero on all three days. Item 4: rate reported with classification. Items 5–6: equal. Item 7: reported.

**Performance (full day, single thread, laptop — state CPU/RAM/JVM)**
- Table: `naive → +mmap → +raw accessors → +LongObjectMap → +TickBook → +pool` with msgs/s, wall, p50/p99/p99.9 apply ns, **bytes allocated per message**.
- Target (not promise): ≥ 10× naive→final on msgs/s; allocations/message → 0 on the steady-state hot path.

**Research**
- Exact `P(fill within T)` for T ∈ {1, 5, 30, 60} s at the touch, by liquidity tier (top-100 / 101–1000 / rest by daily volume) and session (open 9:30–10:00, midday, close 15:30–16:00); cancellation share ahead of joiners; per day.
- Spread decomposition (effective/realised/impact at 1 s, 5 s, 30 s) from `E`/`C`, volume-weighted, by tier.
- OFI β and out-of-sample R² by tier and session.

**Delivery**
- `README.md` with the tables; `numbers.md` as the only source; demo page; 3-minute screencast; tag `v0.1.0`.

## 12. Decisions (details in [[05 Log/Decision Log]])

- **D1 — Java 21** (kept). Matches 14365's stack; allocation-free Java and GC-tail work are a specific bank skill.
- **D7′ — scaled `int` at native scale 4** (revised from 10⁸). ITCH already gives integers; no parsing of decimals anywhere.
- **D8 — naive first, then measured optimisation** (kept; now the spine).
- **D15 — ITCH over Binance.** Real equities, Level 3, exactness, scale, determinism; loses live/ops. See Why This Project.
- **D16 — decompress once; mmap in Phase 2.** Separates I/O cost from parse cost.
- **D17 — correctness by invariants + priority check + independent implementation.** No external oracle exists; the priority check is the closest thing to the exchange grading the book. Agreement with MeatPy is recorded as agreement.
- **D18 — single-threaded v1.** Correctness and clean measurement first; symbol-partitioned parallelism is the named extension.
- **D19 — hard gate before the stretch.** No matching engine or FIX until Phases 1–4 are written up.
- **D20 — three days: `12302019`, `01302020`, `S120825`.** Smallest 2019 file for development; one busy 2020 day; one recent day.
- **D21 — subset file for development**, full file for numbers.
- **D22 — `U` goes to the back of the queue** with a new ref (spec §1.4.5). Time priority is lost on replace.
- **D23 — `P` trades are unsigned.** Signed metrics use `E`/`C` only; `P` counts for volume.
- **D24 — episodes computed in Java, aggregated in Python.** Python over 300 M messages is impractical.
- **D25 — match-number checks are a validation *mode*** (large primitive set), not always-on.

## 13. Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Sample-file framing isn't 2-byte length prefix | low | Task 1 checks prefix == spec length over 10 k frames; if wrong, read as raw concatenated messages using `expectedLength` |
| 2025/2026 files carry spec changes | low | spec revision log ends Apr 2023 (`O` message); Task 1 type histogram will show any unknown types |
| Full-day memory (order map) blows 16 GB | medium | subset development; `LongObjectMap` with 8-byte keys; `-Xmx8g`; measure live-order peak in Task 1 |
| Naive baseline too slow to even finish a day | medium | run naive on the subset for the histogram; run full day only from `+mmap` onward and say so |
| Priority check has legitimate exceptions I don't know | high | expected — classify violations; that *is* a finding |
| MeatPy disagrees | medium | investigate per message type; disagreement is information, not failure |
| Scope creep into the stretch before the core is written up | high | D19; Index checklist |

## 14. Self-review (per `brainstorming`)

- **Placeholder scan:** none. Every failure in §8 has a response; every metric in §11 is defined in [[04 Reference/Metrics Definitions]].
- **Internal consistency:** §6 flow uses only §5 components; §7 derived fields match the Listener methods; D22/D23 are reflected in §6 and §8.
- **Scope:** four plans + one stretch, each independently testable. Phase 1 runs alone; Phase 2 needs only Phase 1's tests; Phase 3 needs derived output; Phase 4 wraps.
- **Ambiguity:** "market hours" = after `S` code `Q` and before `M`. "Best level" = first key of the side's map. "Head" = `Level.head`. "Tier" = by that day's `E+C+P` volume rank.

**Approved by:** ________ (date)
