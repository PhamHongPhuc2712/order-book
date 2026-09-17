---
title: ITCH 5.0 Message Layouts
source: Nasdaq TotalView-ITCH 5.0 specification PDF (nasdaqtrader.com), revision log through April 2023; read in full on 2026-09-12
verified: yes — offsets and lengths transcribed from the spec tables
tags: [reference, itch]
---

# NASDAQ TotalView-ITCH 5.0 — message layouts

**Verified from the spec.** The one thing *not* from the spec is the sample-file framing (§ Framing), confirmed empirically in Phase 1 Task 1.

## Data types

- All integers **big-endian, unsigned**.
- Alpha: ASCII, **left-justified, right-padded with spaces**.
- **Timestamp: 6 bytes, nanoseconds since midnight** (ET, the exchange's clock).
- **Price(4): 4 bytes, 4 implied decimals.** `650000` = $65.0000. Max 200,000.0000 = `0x77359400`. Fits `int`.
- Price(8): 8 bytes, 8 implied decimals — only in MWCB Decline Level (`V`).
- **Stock Locate: 2 bytes.** Assigned daily via `R`; "intended to serve as an array index." `0` for non-stock messages. Not stable across days.
- Tracking Number: 2 bytes, NASDAQ-internal; ignore.
- Order Reference Number: 8 bytes, **day-unique**. Match Number: 8 bytes, day-unique.

## Common header (every message)

| offset | len | field |
|---|---|---|
| 0 | 1 | Message Type (alpha) |
| 1 | 2 | Stock Locate |
| 3 | 2 | Tracking Number |
| 5 | 6 | Timestamp (ns since midnight) |

## Framing of the sample files

Each message is preceded by a **2-byte big-endian length**. So a file is `[len][msg][len][msg]…` with no other container. Phase 1 Task 1 asserts `len == expectedLength(type)` over the first 10,000 frames and the whole file.

## Message table (length includes the header)

| type | name | len | book effect |
|---|---|---|---|
| `S` | System Event | 12 | session state |
| `R` | Stock Directory | 39 | locate → symbol |
| `H` | Stock Trading Action | 25 | per-stock trading state |
| `Y` | Reg SHO Restriction | 20 | — |
| `L` | Market Participant Position | 26 | — |
| `V` | MWCB Decline Level | 35 | — |
| `W` | MWCB Status | 12 | — |
| `K` | IPO Quoting Period Update | 28 | — |
| `J` | LULD Auction Collar | 35 | — |
| `h` | Operational Halt | 21 | (per-market halt; treat like `H` for gating if desired) |
| `A` | Add Order (no MPID) | 36 | **add** |
| `F` | Add Order with MPID | 40 | **add** |
| `E` | Order Executed | 31 | **reduce/remove** |
| `C` | Order Executed With Price | 36 | **reduce/remove** |
| `X` | Order Cancel (partial) | 23 | **reduce/remove** |
| `D` | Order Delete | 19 | **remove** |
| `U` | Order Replace | 35 | **remove old, add new at back** |
| `P` | Trade (non-cross, non-displayed) | 44 | none |
| `Q` | Cross Trade | 40 | none |
| `B` | Broken Trade | 19 | none |
| `I` | Net Order Imbalance Indicator | 50 | none |
| `N` | Retail Price Improvement Indicator | 20 | none |
| `O` | Direct Listing with Capital Raise | 48 | none |

## Layouts that move the book

**`A` Add Order (36)**

| off | len | field |
|---|---|---|
| 11 | 8 | Order Reference Number |
| 19 | 1 | Buy/Sell: `B` buy, `S` sell |
| 20 | 4 | Shares |
| 24 | 8 | Stock (alpha) |
| 32 | 4 | Price(4) |

**`F` Add Order with MPID (40)** — as `A`, plus `36 | 4 | Attribution (MPID, alpha)`.

**`E` Order Executed (31)**

| off | len | field |
|---|---|---|
| 11 | 8 | Order Reference Number |
| 19 | 4 | Executed Shares |
| 23 | 8 | Match Number |

Executed at the order's **display price**. Cumulative: several `E` may hit the same order. Remove when shares reach 0.

**`C` Order Executed With Price (36)**

| off | len | field |
|---|---|---|
| 11 | 8 | Order Reference Number |
| 19 | 4 | Executed Shares |
| 23 | 8 | Match Number |
| 31 | 1 | Printable: `Y` / `N` |
| 32 | 4 | Execution Price(4) |

Execution price differs from display price (price improvement, cross-related). Spec: ignore `N` (non-printable) for time-and-sales and volume to avoid double counting. Book effect identical to `E`.

**`X` Order Cancel (23)** — `11 | 8 | ref`, `19 | 4 | Cancelled Shares` (partial cancel).

**`D` Order Delete (19)** — `11 | 8 | ref`. Remove entirely.

**`U` Order Replace (35)**

| off | len | field |
|---|---|---|
| 11 | 8 | Original Order Reference Number |
| 19 | 8 | **New** Order Reference Number |
| 27 | 4 | Shares (new total) |
| 31 | 4 | Price(4) (new) |

Spec: "All remaining shares from the original order are no longer accessible, and must be removed. … Since the side, stock symbol and attribution cannot be changed by an Order Replace event, these fields are not included … Firms should retain the side, stock symbol and MPID from the original Add Order." The new order **loses time priority** — append at the back of its (possibly new) level. (D22)

## Trade messages (no book effect)

**`P` Trade non-cross (44)**

| off | len | field |
|---|---|---|
| 11 | 8 | Order Reference Number — **zero since Dec 2010** |
| 19 | 1 | Buy/Sell — **always `B` since 14 Jul 2014, regardless of resting side** |
| 20 | 4 | Shares |
| 24 | 8 | Stock |
| 32 | 4 | Price(4) |
| 36 | 8 | Match Number |

Spec: "no Add Order Message is generated when a non-displayed order is initially received … this message indicates when a match occurs between non-displayable order types." → `P` and `E/C` are **disjoint** sets of executions. **`P` has no usable trade sign** (D23).

**`Q` Cross Trade (40)**

| off | len | field |
|---|---|---|
| 11 | 8 | Shares (8 bytes!) |
| 19 | 8 | Stock |
| 27 | 4 | Cross Price(4) |
| 31 | 8 | Match Number |
| 39 | 1 | Cross Type: `O` opening, `C` closing, `H` IPO/halted/paused, (`E` EMC historical) |

**`B` Broken Trade (19)** — `11 | 8 | Match Number` of a prior `E`/`C`/`P`. Spec: "If a firm is only using the ITCH feed to build a book … it may ignore these messages as they have no impact on the current book."

## State messages

**`S` System Event (12)** — `11 | 1 | Event Code`:

| code | meaning |
|---|---|
| `O` | Start of Messages — always first |
| `S` | Start of System hours (NASDAQ accepting orders; pre-market from 4:00) |
| `Q` | **Start of Market hours (9:30)** — market-hours orders available for execution |
| `M` | **End of Market hours (16:00)** — market-hours orders no longer available; **post-market continues** |
| `E` | End of System hours — closed; "still possible to receive Broken Trade messages and Order Delete messages after" |
| `C` | End of Messages — always last |

**"Market hours" for validation gating = between `Q` and `M`.** Drain check is at **`C`**, not `M`.

**`R` Stock Directory (39)** — `11 | 8 | Stock`, `19 | 1 | Market Category` (`Q` Global Select, `G` Global Market, `S` Capital Market, `N` NYSE, `A` NYSE American, `P` NYSE Arca, `Z` BATS, `V` IEX), `20 | 1 | Financial Status`, `21 | 4 | Round Lot Size`, `25 | 1 | Round Lots Only`, `26 | 1 | Issue Classification`, `27 | 2 | Issue Sub-Type`, `29 | 1 | Authenticity` (`P` live, `T` test), `30 | 1 | Short Sale Threshold`, `31 | 1 | IPO Flag`, `32 | 1 | LULD Tier`, `33 | 1 | ETP Flag`, `34 | 4 | ETP Leverage`, `38 | 1 | Inverse`. **Filter `Authenticity == 'T'` (test issues) out of research tables.**

**`H` Stock Trading Action (25)** — `11 | 8 | Stock`, `19 | 1 | Trading State`: `H` halted, `P` paused, `Q` quotation-only, **`T` trading**; `20 | 1 | reserved`; `21 | 4 | Reason` (e.g. `T1` news pending, `LUDP` volatility pause, `MWC1-3` circuit breaker; full list in spec Appendix C). Pre-open spin sends `T` for everything eligible; a symbol **absent** from the spin should be assumed halted.

**`I` NOII (50)** — `11 | 8 | Paired Shares`, `19 | 8 | Imbalance Shares`, `27 | 1 | Direction` (`B`/`S`/`N`/`O`/`P`), `28 | 8 | Stock`, `36 | 4 | Far Price`, `40 | 4 | Near Price`, `44 | 4 | Current Reference Price`, `48 | 1 | Cross Type` (`O`/`C`/`H`/`A` extended close), `49 | 1 | Price Variation Indicator`. Disseminated every 10 s from 9:25, every 1 s from 9:28; 15:50/15:55 for the close. Useful for the demo window and optional auction research.

## Things the spec says that matter for correctness

- Orders are FIFO within a price level; NASDAQ is price-time priority for displayed orders.
- A `D`/`X`/`E`/`C`/`U` always references a prior `A`/`F` (or a prior `U`'s new ref).
- Match Numbers are day-unique across `E`, `C`, `P`, `Q`, and are what `B` references.
- The displayed book may be locked/crossed **outside** continuous trading (pre-open accumulation, halts). Never assert un-crossed unconditionally.
- Locate codes are per-day. Never persist them across files.
