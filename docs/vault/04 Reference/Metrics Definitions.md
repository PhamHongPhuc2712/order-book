---
title: Metrics Definitions
revised: 2026-09-12 (ITCH)
tags: [reference, metrics, quant]
---

# Metrics definitions

Everything reported is defined here so a reader can recompute it. Prices at native scale 4 (`650000` = $65.00). Timestamps ns since midnight ET. One **tick** = `100` (one cent) for prices ≥ $1.00.

## Engineering

**Messages per second** — total frames ÷ wall-clock seconds for one full-day file, single thread, from reader open to last frame, **no histogram** (the two `nanoTime` calls per message are overhead). Median of three runs. State CPU, RAM, disk, OS, JDK and flags.

**Apply latency** — `nanoTime()` before `Engine.apply` to after it returns, on the subset file with `--hist`, `HdrHistogram(10 s, 3 sig. digits)`. Report p50/p90/p99/p99.9/max and count. Excludes reading.

**Bytes allocated per message** — `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(tid)` delta over the run ÷ messages. Includes warm-up. Steady-state figure: same measurement over the second half of the subset (add `--alloc-from N`).

**GC correlation** — JFR `jdk.GarbageCollection` events: count, longest pause; stated alongside p99.9 and max.

**Validation counters** — defined in spec §8/§9. "Priority violation rate" = `priorityViolations / priorityChecked`.

## Market data

**BBO** at time t — best bid `b_t`, its shares `qb_t`, best ask `a_t`, `qa_t`, from the last `bbo` line with `ts ≤ t`. **Mid** `m_t = (b_t + a_t)/2`. Only during market hours with state `T`.

**Quoted spread** — `(a_t − b_t)·10⁴ / m_t` bps.

**Trade sign (D23).** Only `E`/`C` executions carry sign, via the *resting* side: resting `B` ⇒ sell aggressor, `d = −1`; resting `S` ⇒ buy aggressor, `d = +1`. `P` trades: **no sign** (field is always `B` since 2014). `C` with `Printable = N` excluded from signed metrics and volume.

**Effective spread** at execution `(p, T, d)` — `2·d·(p − m_T)·10⁴/m_T` bps, BBO as of `T` (the execution's own timestamp; the BBO *before* the execution is applied — take the last `bbo` line with `ts < T`, or `ts ≤ T` and the earlier of equal timestamps).

**Realised spread** at horizon h — `2·d·(p − m_{T+h})·10⁴/m_T`. h ∈ {1 s, 5 s, 30 s}.

**Price impact** — `effective − realised_h = 2·d·(m_{T+h} − m_T)·10⁴/m_T`.

Report volume-weighted means per tier per session, 95 % CI by block bootstrap (5-minute blocks, 500 resamples). `mid_T` is the last quote **strictly before** T (the execution's own BBO update carries the same timestamp); `mid_{T+h}` is the last quote at or before T + h. The quote table holds one row per (symbol, nanosecond): the state after every message at that instant (D31). Quotes only exist as `mid` when two-sided, uncrossed and not a stub: a quote whose relative spread is ≥ 100 % (`ask ≥ 3·bid`, e.g. bid $0.01 against an ask near the $200,000 maximum) defines no mid and the last real quote before it is used instead. Without this rule one such quote near the close put the `rest` tier's realised spread at thousands of bps.

**Volume** — `E` + printable `C` + `P` + `Q` shares per symbol. `Q` by cross type separately (opening/closing). Excludes `C` non-printable per spec.

**Tier** — rank symbols within a day by `volE + volC + volP`: `top100`, `101-1000`, `rest`. Exclude `Authenticity = 'T'` issues.

**Session** — open `[9:30, 10:00)`, midday `[10:00, 15:30)`, close `[15:30, 16:00)` ET.

## Order flow imbalance (Cont, Kukanov & Stoikov 2014)

For consecutive BBO observations n−1 → n:
```
e_n =  1{b_n ≥ b_{n−1}}·qb_n − 1{b_n ≤ b_{n−1}}·qb_{n−1} − 1{a_n ≤ a_{n−1}}·qa_n + 1{a_n ≥ a_{n−1}}·qa_{n−1}
```
`OFI_k = Σ e_n` over 1-second window k; `Δm_k` = last mid of window k − last mid of the previous non-empty window of the same symbol, in ticks; windows with no quote update are excluded. OLS with intercept **per symbol** (β differs by depth, so pooling across symbols is meaningless); in-sample = windows in the first 70 % of each session's clock, out-of-sample = the last 30 %; a symbol needs ≥ 30 in-sample and ≥ 10 out-of-sample windows. Reported: median and IQR of β, R² (in) and R² (out) across symbols, by tier × session.

## Exact queue model (Level 3)

**Setup.** Sampling is event-triggered on a 1-second grid: at the first top-of-book change at or after each grid point (market hours, state `T`, starting at the first grid point after system event `Q`), a hypothetical infinitesimal order joins the **back** of the best level on each side s at price P *immediately after that change*, so the triggering message's order (if at the touch) is ahead of it. The orders ahead are exactly the level's FIFO list at t₀: `ahead0 = Σ shares`, `orders0 = count`. (Between top-of-book changes the touch queue is unchanged, so nothing is lost by sampling only at changes; on thin names samples are rarer than one per second.)

**Evolution.** For each subsequent message touching an order in the ahead-set: `E`/`C` → `execAhead += shares`, `ahead −= shares` (matched by reference, so a `C` reported at an improved price still counts); `X`/`D`/`U`(old) → `cancelAhead += shares`, `ahead −= shares`. Orders added behind the joiner are irrelevant.

**Fill.** The joiner is filled at the first execution at price P on side s after `ahead` reaches 0 (`fillTs`). If an execution at P hits an order **not** in the ahead-set while `ahead > 0`, that contradicts price-time priority: record `anomaly = 1` and count as filled at that time (report both with and without anomalies).

**Censor.** `moved` — the side's best price leaves P while the level still exists or orders were still ahead; `exhausted` — `ahead` reached 0 and the level then disappeared, so nobody was behind the joiner to reveal a fill (in the real book the joiner would have been alone at the touch; D29); `T` — 60 s elapsed; `eod` — `M` system event. Every episode records `endTs` (fill or censoring event). **Reported as a range:** `p_fill` counts observed fills only (censored = not filled: a lower bound); `p_fill_upper` also counts `exhausted` episodes at their exhaustion time (an upper bound, since the joiner may still have been cancelled or the price moved before the next contra order arrived).

**Reported.**
- `P(fill ≤ T)` for T ∈ {1, 5, 30, 60} s, lower and upper bound, by tier × session × side.
- **Conditional curve:** `P(fill ≤ 5 s | ahead0 ∈ bucket)`, buckets = within-tier deciles of `ahead0`. **This is the headline finding.**
- Cancellation share ahead of joiners: `Σ cancelAhead / (Σ execAhead + Σ cancelAhead)`, by tier.
- Censor fractions and anomaly rate.

Cross-day: the same tables on `01302020` and `S120825`; report the range, not just one day.

## Optional: staleness on the signal side

Lag the BBO by L ∈ {0, 10, 50, 100, 500, 1000} ms and recompute OFI R² against the true Δmid; report R²(L). Purely offline; still valid without a live feed.
