---
title: Phase 3 — Validate and Research — Implementation Plan
date: 2026-09-12
spec: "[[02 Spec/2026-09-12-itch-lob-design]]"
status: done — 2026-09-16; tag `phase-3-results`; results in `research/results/numbers_12302019.md` (one day; the other two days follow Phase 4 Task 1)
depends: phase-2-optimised
tags: [plan, phase-3, itch, research]
---

# Phase 3: Validate and Research — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Produce the correctness report (the seven checks from spec §9, including the MeatPy diff) and the research results: the **exact queue-position fill-probability curve**, spread decomposition, and OFI — by liquidity tier and session, on three days.

**Architecture:** Validation mode adds match-number tracking (`LongSet`) and a priority-violation dump. `QueueProbe` is a `Listener` that samples joiner episodes in Java and writes `episodes.ndjson`; Python only aggregates. `MeatPyExport` writes top-of-book at minute marks for one symbol; `meatpy_diff.py` compares to MeatPy's own output. `convert.py` turns derived NDJSON into Parquet; DuckDB views define BBO, signed executions and spreads; `report.py` writes `numbers.md` and charts.

**Tech Stack:** Java (as before); Python 3.11: pyarrow 17, duckdb 1.1, pandas 2.2, numpy 2, matplotlib 3.9, tabulate, pytest 8, hypothesis 6, **meatpy** (reference parser).

**Spec:** [[02 Spec/2026-09-12-itch-lob-design]] · formulas: [[04 Reference/Metrics Definitions]]

## Global Constraints

- Signed metrics use `E`/`C` only (D23). `P` and `Q` contribute to volume tables only.
- Session buckets (ET, ns since midnight): open `9:30–10:00` = `[34_200e9, 36_000e9)`; midday `[36_000e9, 55_800e9)`; close `15:30–16:00` = `[55_800e9, 57_600e9)`.
- Tier by that day's `E+C+P` share volume rank: `top100`, `101-1000`, `rest`.
- Queue probes: 60 symbols (20 per tier, chosen from `daily.ndjson`), bid side and ask side, sample every 1 s, censor at 60 s.
- All Parquet under `data/parquet/<table>/symbol=/date=/`.

## File structure

```
core/src/main/java/sg/phuc/lob/engine/QueueProbe.java · CompositeListener.java
core/src/test/java/sg/phuc/lob/engine/QueueProbeTest.java · CorruptionTest.java (itch)
replay/src/main/java/sg/phuc/lob/replay/MeatPyExport.java · PriorityDump.java
research/
├── requirements.txt · pytest.ini
├── convert.py · db.py · metrics.sql · ofi.py · queue.py · meatpy_diff.py · report.py
└── tests/ conftest.py · test_convert.py · test_metrics.py · test_ofi.py · test_queue.py
```

---

### Task 1: Corruption tests for the readers

**Files:** `core/src/test/java/sg/phuc/lob/itch/CorruptionTest.java`

- [x] **Step 1: Test** — take the golden fixture; (a) truncate at a random byte inside a message → both readers throw `IOException` whose message contains `truncated` and `at byte`; (b) overwrite one length prefix with `0x00 0x00` → `bad frame length 0`; (c) overwrite a length prefix with a too-large value → `bad frame length`; (d) flip a *type* byte to `'z'` → readers do **not** throw; `Engine` counts `unknownType == 1` and every other counter is unchanged versus the clean run. — *done 2026-09-14 in `replay/src/test/.../CorruptionTest.java` (the fixture lives in the replay module). Correction to (c): a 2-byte prefix cannot exceed the 64 KB buffer, so an oversized prefix is not a reader error — the reader delivers a 65,535-byte frame and the **engine** counts `badLength`; the test asserts that. (d) flips a `P` (no book/state effect) so the "every other counter unchanged" assertion is exact, samples included.*
- [x] **Step 2: Run, commit** — `git commit -am "test: reader corruption cases; engine tolerates unknown type"` — *commit `test: reader corruption cases; engine tolerates unknown type`*

---

### Task 2: Validation mode — match tracking and priority dump

**Files:** `replay/src/main/java/sg/phuc/lob/replay/PriorityDump.java`; `Replay` gains `--validate` (enables `LongSet` tracker sized `1 << 27`) and `--dump-priority N`

- [x] **Step 1: `PriorityDump`** — a `Listener` wrapper that, when the engine records a priority violation (expose a `Validation.onViolation` callback), writes the first N cases with full context: `ts, sym, ref, execShares, orderPrice, bestPriceOnSide, orderIsHead, headRef, levelCount, levelShares, tradingState, nsSinceLastBboChange`. — *done 2026-09-14: `Engine.PriorityHook` (+ `queuePos`, `orderShares`, `side`, `nsSinceLastExec`, `nsSinceLastChange`), `replay.PriorityDump` writes `priority.ndjson`; `ValidationModeTest` asserts the exact context*
- [x] **Step 2: Full-day validation run** (all three days) — *done: all structural 0, crossedInMarket 0, crossedAtResume 642, priority 10,565 / 5,683,178 (0.19 %), **duplicateMatch 0, twoSidedMatches 4,324, brokenUnknown 0** (no `B` this day), liveAtC 0. Match tracking needed refinement (D28): a match between two resting orders is reported once per leg (N + Y). Run at 4g, final config, 291 s incl. derived output*
```
java -Xms10g -Xmx10g -XX:+UseZGC -cp "..." sg.phuc.lob.replay.Replay --file data/itch/12302019.bin --reader mmap --map long --book array --pool --dedupe --validate --dump-priority 500 --out data/derived/12302019
```
Expected: `duplicateRef, unknownRef, execExceeds, cancelExceeds, badLength, unknownType` all **0**; `duplicateMatch 0`; `brokenUnknown 0`; `crossedInMarket` 0 or explained; `priorityViolations / priorityChecked` reported as a rate; `liveAtC` reported.
- [x] **Step 3: Classify the priority dump** — `research/classify_priority.py`: bucket by (a) `nsSinceLastBboChange < 1000` (same-instant tie), (b) `orderIsHead == false && orderPrice == bestPrice` (queue-order anomaly), (c) `orderPrice != bestPrice` (off-best), (d) `tradingState != 'T'` (shouldn't occur — gate bug). Report counts. Each bucket gets one sentence of interpretation in `numbers.md`. **Whatever the rate is, it's reported, not hidden.** — *done: `research/classify_priority.py` (burst size from `executions.ndjson`): burst 9,455 (89.5 %), isolated 1,110 (10.5 %, median queuePos 1, 44.5 % odd lots — self-match prevention is the working hypothesis, unverifiable from ITCH), **off_best 0, gate_bug 0**. Report `research/out/priority_12302019.md`*
- [x] **Step 4: Commit** — `git commit -am "validation mode: match tracking, priority dump, classification"` — *commits `fd75e93`, classify script*

---

### Task 3: MeatPy cross-check

**Files:** `replay/src/main/java/sg/phuc/lob/replay/MeatPyExport.java`; `research/meatpy_diff.py`

- [x] **Step 1: Install and run MeatPy on one symbol** (Python venv) — *meatpy 0.5.0 in the venv; `research/meatpy_ref.py` (LOBRecorder, max_depth 1, marks 9:30–16:00) started on the full `.bin` for AAPL 2026-09-14 — **done 2026-09-15**: 268,744,780 messages in 3,379 s (56 min; ours 178 s incl. the export), all 391 marks reached, 262 unresolved priority exceptions by MeatPy's own check*
```
pip install meatpy
```
Follow the repository's "Full LOB at 1-Minute Intervals" example for ITCH 5.0 to produce, for `AAPL` on `12302019`, a CSV of `timestamp, bid, bid_size, ask, ask_size` at each minute mark from 9:30 to 16:00. MeatPy reads the `.gz` directly. Expect this to take a long time (Python over ~300 M messages) — run it overnight and only once per day-file. Record wall time as a data point ("reference implementation: X hours; ours: Y seconds").
- [x] **Step 2: `MeatPyExport`** — a `Listener` that, for one symbol, records the BBO as of each minute boundary (`ts >= nextMinute` → emit the *last* BBO before the boundary) and writes the same CSV shape. Also emit per-type message counts for that symbol (from `daily.ndjson`). — *done 2026-09-14: `engine.MeatPyExport` listener (+ `CompositeListener`), `Replay --meatpy SYM --out DIR [--no-derived]`; boundary rule matches MeatPy's: state after every message with ts < mark + 1 us; `MeatPyExportTest` pins it*
- [x] **Step 3: `meatpy_diff.py`** — join on minute; report rows where any of the four fields differ; print the first 20 diffs with both sides. Also compare per-type message counts. — *done: on the 5-minute fixture the four comparable marks (9:31–9:34) agree on bid, ask and both sizes; the 9:30 mark differs only because MeatPy cannot record an instrument's very first update (its LOB is None until then) — a cold-start artefact; unreached marks are reported, not failed*
```python
import pandas as pd, sys
ours = pd.read_csv(sys.argv[1]); ref = pd.read_csv(sys.argv[2])
m = ours.merge(ref, on="minute", suffixes=("_ours", "_ref"))
diff = m[(m.bid_ours != m.bid_ref) | (m.ask_ours != m.ask_ref) | (m.bid_size_ours != m.bid_size_ref) | (m.ask_size_ours != m.ask_size_ref)]
print(f"minutes={len(m)} differing={len(diff)}"); print(diff.head(20).to_string())
```
Expected: `differing=0`. If not: the diff tells you which field and when; check (i) whether MeatPy applies `U` to the back of the queue, (ii) whether it counts `F` orders, (iii) its handling of `C` non-printable. Disagreement is recorded as a finding either way — see D17.
- [x] **Step 4: Commit** — `git commit -am "validation: MeatPy top-of-book diff on one symbol"` — *code in `164da34`; **result: 391/391 marks identical (bid, ask, bidSh, askSh), per-type counts identical on all 18 message types***

---

### Task 4: `QueueProbe` — exact joiner episodes

**Files:** `core/src/main/java/sg/phuc/lob/engine/QueueProbe.java`, `CompositeListener.java`; test `QueueProbeTest.java`

**Interfaces:** `QueueProbe(Engine engine, Set<String> symbols, long everyNs, long censorNs, Writer out)` implements `Listener`; `CompositeListener(Listener... ls)` fans out. Episode line: `{"t0":…,"sym":"…","side":"B","price":…,"ahead0":…,"orders0":…,"fillTs":…|null,"censor":"none|moved|eod|T","execAhead":…,"cancelAhead":…,"anomaly":0|1}`.

- [x] **Step 1: Failing test** — build a book with bid level 65.00 containing refs 1 (100), 2 (50); start a probe at t0 (both orders ahead: `ahead0=150, orders0=2`); then: `X` cancel 60 on ref 1 → `cancelAhead=60, ahead=90`; `E` 40 on ref 1 → `execAhead=40, ahead=50`; `D` ref 2 → `cancelAhead=110, ahead=0` (at head); `E` on a new ref 3 behind us at 65.00 → **fill** at that ts. Assert one episode line with `fillTs` = that ts, `execAhead=40`, `cancelAhead=110`, `anomaly=0`. Second test: price moves (ask side best changes) → `censor=moved`. — *done 2026-09-16: `QueueProbeTest`, six cases (fill, moved vs exhausted, anomaly fill, replace-as-cancel + grid alignment, T and eod censors, symbol/state gating), run on the final engine configuration (dedupe on)*

- [x] **Step 2: Implement** — *done 2026-09-16 with three changes from the code below (D29): `exhausted` censor + `endTs` per episode; executions matched by reference before price (a `C` at an improved price still reduces `ahead`); sampling starts at the first grid point after `Q` and aligns to the grid. `QueueProbe(Set<String>, everyNs, censorNs, Writer)` + `setEngine`, `AutoCloseable`.*

```java
package sg.phuc.lob.engine;

import sg.phuc.lob.book.Level;
import sg.phuc.lob.book.Order;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Set;

/** Samples a hypothetical infinitesimal joiner at the touch every `everyNs` and tracks exactly what happens to the queue ahead of it. */
public final class QueueProbe implements Listener {
    private static final class Episode {
        long t0; int locate; byte side; int price; int ahead0, orders0; long ahead;
        long[] refs; int[] rem; int n;            // orders ahead at t0 and their remaining shares
        long execAhead, cancelAhead; boolean atHead; int anomaly;
        int indexOf(long ref) { for (int i = 0; i < n; i++) if (refs[i] == ref) return i; return -1; }
    }
    private final Engine engine; private final long everyNs, censorNs; private final Writer out;
    private final boolean[] on = new boolean[65536];
    private final long[] nextSample = new long[65536];
    @SuppressWarnings("unchecked") private final ArrayList<Episode>[] active = new ArrayList[65536];
    private final Set<String> symbols;
    private final StringBuilder sb = new StringBuilder(256);

    public QueueProbe(Engine engine, Set<String> symbols, long everyNs, long censorNs, Writer out) {
        this.engine = engine; this.symbols = symbols; this.everyNs = everyNs; this.censorNs = censorNs; this.out = out;
    }

    private boolean enabled(int loc) {
        if (active[loc] == null) { String s = engine.symbol(loc); on[loc] = s != null && symbols.contains(s); active[loc] = new ArrayList<>(); }
        return on[loc];
    }

    @Override public void onBbo(long ts, int loc, int bid, int bidSh, int ask, int askSh) {
        if (!enabled(loc)) return;
        // censor moved / T
        for (int i = active[loc].size() - 1; i >= 0; i--) {
            Episode e = active[loc].get(i);
            int best = e.side == 'B' ? bid : ask;
            if (best != e.price) finish(e, ts, null, "moved", i, loc);
            else if (ts - e.t0 > censorNs) finish(e, ts, null, "T", i, loc);
        }
        if (ts >= nextSample[loc] && engine.marketHours() && engine.tradingState(loc) == 'T') {
            nextSample[loc] = ts + everyNs;
            start(ts, loc, (byte) 'B', bid); start(ts, loc, (byte) 'S', ask);
        }
    }
    private void start(long ts, int loc, byte side, int price) {
        if (price == 0) return;
        Level lv = engine.book(loc).bestLevel(side); if (lv == null || lv.price != price) return;
        Episode e = new Episode(); e.t0 = ts; e.locate = loc; e.side = side; e.price = price;
        e.refs = new long[lv.count]; e.rem = new int[lv.count];
        for (Order o = lv.head; o != null; o = o.next) { e.refs[e.n] = o.ref; e.rem[e.n] = o.shares; e.n++; e.ahead += o.shares; }
        e.ahead0 = (int) Math.min(Integer.MAX_VALUE, e.ahead); e.orders0 = e.n; e.atHead = e.ahead == 0;
        active[loc].add(e);
    }
    @Override public void onExecution(long ts, int loc, long ref, byte side, int shares, int price, long match, boolean printable) {
        if (!enabled(loc)) return;
        for (int i = active[loc].size() - 1; i >= 0; i--) {
            Episode e = active[loc].get(i);
            if (e.side != side || e.price != price) continue;
            if (e.atHead) { finish(e, ts, ts, "none", i, loc); continue; }
            int k = e.indexOf(ref);
            if (k >= 0) { int d = Math.min(shares, e.rem[k]); e.rem[k] -= d; e.execAhead += d; e.ahead -= d; if (e.ahead <= 0) e.atHead = true; }
            else { e.anomaly = 1; finish(e, ts, ts, "none", i, loc); }   // an order behind us executed first: priority anomaly, treat as fill
        }
    }
    @Override public void onCancel(long ts, int loc, long ref, byte side, int shares, int price) {
        if (!enabled(loc)) return;
        for (Episode e : active[loc]) {
            if (e.side != side || e.price != price) continue;
            int k = e.indexOf(ref);
            if (k >= 0) { int d = Math.min(shares, e.rem[k]); e.rem[k] -= d; e.cancelAhead += d; e.ahead -= d; if (e.ahead <= 0) e.atHead = true; }
        }
    }
    @Override public void onSystemEvent(long ts, char code) {
        if (code != 'M') return;
        for (int loc = 0; loc < active.length; loc++) if (active[loc] != null)
            for (int i = active[loc].size() - 1; i >= 0; i--) finish(active[loc].get(i), ts, null, "eod", i, loc);
    }
    private void finish(Episode e, long ts, Long fillTs, String censor, int idx, int loc) {
        active[loc].remove(idx);
        sb.setLength(0);
        sb.append("{\"t0\":").append(e.t0).append(",\"sym\":\"").append(engine.symbol(loc)).append("\",\"side\":\"").append((char) e.side)
          .append("\",\"price\":").append(e.price).append(",\"ahead0\":").append(e.ahead0).append(",\"orders0\":").append(e.orders0)
          .append(",\"fillTs\":").append(fillTs == null ? "null" : fillTs.toString()).append(",\"censor\":\"").append(censor)
          .append("\",\"execAhead\":").append(e.execAhead).append(",\"cancelAhead\":").append(e.cancelAhead).append(",\"anomaly\":").append(e.anomaly).append("}\n");
        try { out.write(sb.toString()); } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
}
```
`CompositeListener` forwards every method to each wrapped listener. `Engine` exposes `book(int)`, `symbol(int)`, `marketHours()`, `tradingState(int)` (already present).

Note the fill rule: the joiner is filled at the **first execution at its price after the queue ahead is exhausted** — including the case where that execution's ref is behind us (which under price-time priority means we'd have been hit first; we record `anomaly=1` and still count the fill). Both interpretations are reported.

- [x] **Step 3: Pass, then run probes on three days** — `Replay … --probe-symbols <60 syms> --out …`. Expected: `episodes.ndjson` with ~60 × 2 × 23,400 ≈ 2.8 M lines per day. — *done 2026-09-16 for `12302019` (the other two days are Phase 4 Task 1): `research/pick_probes.py` chooses 20 symbols per tier at evenly spaced volume ranks (test symbols excluded, `rest` needs ≥ 10 executions); `Replay --final --validate --dump-priority 500 --probe-symbols @data/derived/12302019/probe_symbols.txt --out data/derived/12302019` → **917,092 episodes** on 59 symbols in 328 s (derived output + probes + validation; validation counters identical). Fewer than 2.8 M because sampling is event-triggered: thin names have no top-of-book change in most seconds.*
- [x] **Step 4: Commit** — `git commit -am "research: QueueProbe exact joiner episodes; CompositeListener"` — *commit `0a36549` (`CompositeListener` already existed from Task 3)*

---

### Task 5: Python research layer

**Files:** `research/*` as listed

- [x] **Step 1: Environment** — *done 2026-09-16: pins match the venv (pyarrow 25.0.1, meatpy 0.5.0); `ci.yml` runs `setup-python` 3.12 + `pytest`* — `requirements.txt`:
```
pyarrow==17.0.0
duckdb==1.1.3
pandas==2.2.3
numpy==2.1.3
matplotlib==3.9.2
tabulate==0.9.0
pytest==8.3.3
hypothesis==6.115.6
meatpy
```
CI: add `setup-python` + `pip install -r research/requirements.txt` + `cd research && pytest -q` to `ci.yml`.

- [x] **Step 2: `convert.py`** — *done 2026-09-16 via DuckDB `COPY … FROM read_ndjson` (streams the 10.6 GB bbo file), one file per table per day partitioned by date only, rows ordered by (sym, ts) — D30; `tests/test_convert.py`* — — derived NDJSON → Parquet. Tables `bbo(ts, sym, bid, bidSh, ask, askSh)`, `executions(ts, sym, ref, side, shares, price, match, printable)`, `trades(ts, sym, kind, shares, price, match, cross)`, `episodes(...)`, `daily(...)`; all ints `int64`, `side`/`kind`/`censor` as `string`; partition `symbol=/date=` (date from the file name). Test on a 3-line fixture per table.

- [x] **Step 3: `db.py` + `metrics.sql`** — *done 2026-09-16: as below plus `session()`/`in_market()` macros, `bbo_sel`/`exec_sel` batch views, `ofi_windows()`; `mid0` is the quote strictly before the execution; `spreads.py` batches by 250 symbols, aggregates to 5-minute blocks, bootstraps 500×; `tests/test_metrics.py` pins the hand example (eff 1.9998, real −1.9998, impact 3.9996 bps)*

```sql
-- mid in price units (scale 4)
create or replace view bbo_mid as
select sym, date, ts, bid, bidSh, ask, askSh, (bid + ask) / 2 as mid from bbo where bid > 0 and ask > 0;

-- signed executions: resting side 'B' means a SELL aggressor (d = -1); resting 'S' means BUY aggressor (d = +1). E/C only.
create or replace view exec_signed as
select sym, date, ts, ref, shares, price, match, case when side = 'B' then -1 else 1 end as d
from executions where printable;

create or replace macro eff_real(h) as table
with t as (
  select e.sym, e.date, e.ts, e.shares, e.price, e.d, b0.mid as mid0
  from exec_signed e asof join bbo_mid b0 on b0.sym = e.sym and b0.date = e.date and b0.ts <= e.ts
), th as (
  select t.*, bh.mid as midh from t asof join bbo_mid bh on bh.sym = t.sym and bh.date = t.date and bh.ts <= t.ts + h
)
select sym, date, ts, shares, d,
       2.0 * d * (price - mid0) / mid0 * 1e4 as eff_bps,
       2.0 * d * (price - midh) / mid0 * 1e4 as real_bps,
       2.0 * d * (midh - mid0)  / mid0 * 1e4 as impact_bps
from th;

create or replace view daily_tier as
select sym, date, volE + volC + volP as vol,
       case when rank() over (partition by date order by volE + volC + volP desc) <= 100 then 'top100'
            when rank() over (partition by date order by volE + volC + volP desc) <= 1000 then '101-1000' else 'rest' end as tier
from daily;
```
Test: hand-built fixture (bid 100.00/ask 100.02 → mid 100.01; an `E` on a resting **ask** at 100.02 at t, so `d=+1`, `eff = 2*(100.02−100.01)/100.01*1e4 ≈ 2.0 bps`; BBO moves to 100.02/100.04 by t+h → `real ≈ 2*(100.02−100.03)/100.01*1e4 ≈ −2.0`, `impact ≈ 4.0`).

- [x] **Step 4: `ofi.py`** — *done 2026-09-16: windows in SQL, per-symbol OLS from regression sums (exact), in-sample first 70 % of each session, OOS R² on the rest; medians/IQR by tier × session; `tests/test_ofi.py` (SQL window case, numpy agreement, hypothesis scale property)* — — same as the Binance version over `bbo_mid` (columns renamed); windows of 1 s (`ts // 1_000_000_000`); `dmid` in ticks (tick = 100 = one cent); fit in/out-of-sample; grouped by tier and session.

- [x] **Step 5: `queues.py`** (renamed: `queue` shadows the stdlib) — *done 2026-09-16: `enrich`, `summary` (lower/upper bounds per D29 + censor/anomaly/cancel-share attrs), `by_group`, `conditional` (rank-based within-tier deciles); `tests/test_queue.py`* — aggregation only:
```python
def summary(ep: pd.DataFrame, T_list=(1, 5, 30, 60)) -> pd.DataFrame:
    ep = ep.copy(); ep["dt"] = (ep["fillTs"] - ep["t0"]) / 1e9
    rows = []
    for T in T_list:
        filled = ep["fillTs"].notna() & (ep["dt"] <= T)
        rows.append({"T_s": T, "p_fill": float(filled.mean()), "n": int(len(ep))})
    s = pd.DataFrame(rows)
    tot = ep["execAhead"].sum() + ep["cancelAhead"].sum()
    s.attrs.update(cancel_share=float(ep["cancelAhead"].sum() / tot) if tot else float("nan"),
                   censored=float((ep["censor"] != "none").mean()), anomaly=float(ep["anomaly"].mean()),
                   median_ahead0=float(ep["ahead0"].median()))
    return s
```
Group by `tier × session × side`; also a conditional curve: `P(fill ≤ 5 s | ahead0 bucket)` for buckets of initial queue size (quantiles of `ahead0` within tier). **That conditional curve is the finding.**

- [x] **Step 6: `report.py`** — *done 2026-09-16: `research/results/numbers_12302019.md` + `queue_`, `queue_cond_`, `spreads_12302019.png` (committed); `--reuse spreads,ofi` re-summarises from the saved CSVs; tag `phase-3-results`* — — `numbers.md` sections per day: validation counters (from `validation.json`), MeatPy diff result, perf table reference, spreads by tier × horizon, OFI by tier × session, queue tables and the conditional curve; charts `queue_<date>.png` (P(fill) vs T by tier), `queue_cond_<date>.png` (P(fill ≤ 5 s) vs ahead0 bucket by tier), `spreads_<date>.png`. Then `git commit -m "research: parquet, duckdb views, OFI, queue aggregation, report"` and `git tag phase-3-results`.

## Self-review

- **Spec coverage:** §9 items 1–3 and 7 (Task 2), 4 (Task 2 classification), 5–6 (Task 3); §10 corruption (Task 1); §11 research criteria (Tasks 4–5); D23 in `exec_signed`; D24 probes in Java.
- **Placeholder scan:** Task 2 Step 1, Task 3 Step 2, Task 5 Steps 2/4/6 describe rather than list; each is a direct adaptation of listed code (Phase 1 `DerivedWriter`, Binance-era `convert.py`/`ofi.py`/`report.py`) with the field renames given. MeatPy's exact API is deliberately not transcribed — follow its own example, which is the safer instruction.
- **Type consistency:** `QueueProbe` uses `Engine.book/symbol/marketHours/tradingState` as defined in Phase 1; episode JSON fields match `convert.py`'s `episodes` table and `queue.py`'s column names.
