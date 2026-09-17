---
title: Stretch — Matching Engine, Pre-Trade Risk, FIX Gateway
date: 2026-09-12
spec: "[[02 Spec/2026-09-12-itch-lob-design]]"
status: gated — do not start before tag v0.1.0
tags: [plan, stretch, matching, fix]
---

# Stretch: Matching Engine, Pre-Trade Risk, FIX Gateway

> **Gate (D19):** nothing here starts until Phases 1–4 are complete and `v0.1.0` is tagged. A half-built FIX gateway is worse than none. Stretch A before Stretch B.

**Goal:** (A) A price-time-priority matching engine over the same `Book`/`Level`/`Order` structures, **validated against NASDAQ's own execution sequences** in the feed, plus the SEC 15c3-5 pre-trade risk checks; (B) a FIX 4.4 gateway in front of it with a measured tick-to-trade latency.

**Architecture:** `MatchingEngine.predictFills(side, shares)` walks the opposite side best-first, FIFO within level, and returns the fill sequence — that function *is* the validator when pointed at the reconstructed book, and *is* the engine when applied. `PreTradeRisk` is a pure function `Order → Accept | Reject(reason)`. The FIX layer is QuickFIX/J with one acceptor session; `NewOrderSingle (35=D)` → risk → engine → `ExecutionReport (35=8)`.

**Tech Stack:** Java 21; QuickFIX/J 2.3.x (`org.quickfixj:quickfixj-core`, `quickfixj-messages-fix44`).

## Stretch A — Matching engine and risk

### Task A1: `predictFills` and the burst validator

**Files:** `core/src/main/java/sg/phuc/lob/match/MatchingEngine.java`, `engine/BurstValidator.java`; tests

**Interfaces:** `record Fill(long ref, int shares, int price)`; `int predictFills(Book bk, byte aggressorSide, int shares, int limitPrice, Fill[] out)` — returns count; walks `bestLevel(opposite)` while price satisfies `limitPrice` (0 = market); within a level `head → next`, consuming `min(o.shares, remaining)`.

- [ ] **Step 1: Test** — book with asks 100.02 (refs 1:100, 2:50) and 100.03 (ref 3:70); `predictFills(BUY, 180, limit 100.03)` → `[1:100, 2:50, 3:30]`; with limit 100.02 → `[1:100, 2:50]`, returns 2 and leaves 30 unfilled (caller decides: rest or cancel).
- [ ] **Step 2: `BurstValidator`** — a `Listener` that groups consecutive `E` messages sharing `(locate, side, ts)` into a burst; at the burst's first `E`, calls `predictFills(book, opposite(side), Σ burst shares, 0)` **before** the engine applies it (hook: `Listener.onExecutionBurstStart` fired by `Engine` when it sees an `E` whose `(locate, side, ts)` differs from the previous). Compares predicted `(ref, shares)` sequence to the actual burst; counts `burstsChecked`, `burstsMatched`, `firstMismatchIndex` histogram. Bursts are recognised only in market hours, state `T`, and for `E` (not `C`).
- [ ] **Step 3: Run on a full day** — report `burstsMatched / burstsChecked`. Expected: high but not 100 % — mismatches are where NASDAQ's matching differs from pure displayed price-time (hidden liquidity via `P` interleaved, odd-lot handling, mid-point orders). Classify the first 200 mismatches. **That classification is the interesting output of Stretch A.**
- [ ] **Step 4: Commit** — `git commit -am "match: predictFills + burst validator against feed executions"`

### Task A2: `PreTradeRisk`

**Files:** `core/src/main/java/sg/phuc/lob/match/PreTradeRisk.java`; test

**Interfaces:** `record Limits(int maxShares, long maxNotional4, int priceBandBps, boolean killSwitch)`; `enum Reject { NONE, MAX_SHARES, MAX_NOTIONAL, PRICE_BAND, KILL_SWITCH, NO_REFERENCE_PRICE }`; `Reject check(Limits l, byte side, int shares, int price, int referenceMid)`.

- [ ] **Step 1: Test** — each rejection path with exact boundary values (`shares == maxShares` passes, `+1` rejects; notional computed as `shares * price` at scale 4, compare to `maxNotional4`; band `|price − mid| * 1e4 / mid > priceBandBps` rejects; `referenceMid == 0` → `NO_REFERENCE_PRICE`; kill switch rejects everything first).
- [ ] **Step 2: Implement** (≤ 30 lines), commit — `git commit -am "risk: 15c3-5 pre-trade checks"`

### Task A3: Apply path and a replay-driven "shadow desk"

- [ ] `MatchingEngine.submit(...)` applies fills to the book (reducing resting orders) and rests any remainder as a new `Order` at the back of its level; a `ShadowDesk` replay mode injects synthetic orders at chosen times into the reconstructed book and reports fills — used only for the tick-to-trade measurement in Stretch B. Commit.

## Stretch B — FIX 4.4 gateway

### Task B1: QuickFIX/J acceptor

**Files:** `fix/` module; `fix/src/main/resources/acceptor.cfg`; `FixGateway.java`

- [ ] **Step 1: `acceptor.cfg`**
```
[default]
ConnectionType=acceptor
StartTime=00:00:00
EndTime=00:00:00
HeartBtInt=30
FileStorePath=data/fix/store
FileLogPath=data/fix/log
UseDataDictionary=Y
DataDictionary=FIX44.xml

[session]
BeginString=FIX.4.4
SenderCompID=LOB
TargetCompID=CLIENT
SocketAcceptPort=9878
```
- [ ] **Step 2: `FixGateway extends MessageCracker implements Application`** — `onMessage(NewOrderSingle m, SessionID s)`: read `Symbol(55)`, `Side(54)`, `OrderQty(38)`, `OrdType(40)`, `Price(44)` (scale to 4); `PreTradeRisk.check` → on reject send `ExecutionReport` with `OrdStatus(39)=8 Rejected`, `Text(58)=reason`; on accept `MatchingEngine.submit` → one `ExecutionReport` per fill (`ExecType(150)=F Trade`, `LastQty(32)`, `LastPx(31)`, `CumQty(14)`, `LeavesQty(151)`), and a final `OrdStatus=2 Filled` or `1 Partially filled`.
- [ ] **Step 3: Test with a QuickFIX/J initiator** in the test suite: send `NewOrderSingle`, assert the `ExecutionReport` sequence and tags.
- [ ] **Step 4: Tick-to-trade** — timestamp on socket receipt (`Application.fromApp` entry) to `ExecutionReport` `toApp` exit, HdrHistogram, 100 k synthetic orders; report p50/p99/p99.9. State plainly it's loopback and single-session.
- [ ] **Step 5: Commit** — `git commit -am "fix: QuickFIX/J acceptor, NOS → risk → match → ExecReport; tick-to-trade histogram"`

## Self-review
- **Placeholder scan:** A3 and B2 are described at interface/tag level rather than full code — acceptable for a gated stretch whose scope is deliberately bounded by the tests listed; every FIX tag and every risk rule is named.
- **Consistency:** `predictFills` walks the same `Level.head/next` chain `QueueProbe` relies on; `Fill` price is the resting order's price (as for `E`), never the aggressor's limit.
