---
title: lob-reconstruct — Index
target: Bank of America Global Technology Summer Analyst (Software Engineer), Job ID 14365, Singapore
created: 2026-09-11
revised: 2026-09-12 (switched from Binance L2 live capture to NASDAQ TotalView-ITCH 5.0 Level 3 files)
status: phase-4-in-progress
tags: [bofa, itch, lob, internship-2027]
---

# lob-reconstruct — Home

**One line:** A full-day NASDAQ TotalView-ITCH 5.0 parser and Level-3 limit order book reconstructor in Java — correct by invariants, optimised with measured before/after numbers, and used to produce an *exact* queue-position fill-probability result on real US equities. Built to target BofA Global Markets Technology, req **14365**.

## Read in this order

1. [[01 Context/Target - BofA 14365]] — the JD verbatim, and which part of the project answers which line
2. [[01 Context/Why This Project]] — the reasoning trail, including why ITCH replaced the Binance version
3. [[02 Spec/2026-09-12-itch-lob-design]] — **the design spec. This is the approval gate.** Read it, edit it, then execute the plans.
4. Plans, in order — each is independently testable software. **Hard gate:** nothing in the stretch plan starts until Phases 1–3 have a README with numbers.
   - [[03 Plans/2026-09-12-phase-1-parse-and-reconstruct]] — frame reader, decoders, Level-3 book, engine, invariants (the deliberately naive baseline)
   - [[03 Plans/2026-09-12-phase-2-optimise-and-measure]] — mmap, raw accessors, primitive maps, tick arrays, pools; msgs/sec and allocations/msg at every step
   - [[03 Plans/2026-09-12-phase-3-validate-and-research]] — the seven validation checks, MeatPy diff, exact queue model, spreads, OFI
   - [[03 Plans/2026-09-12-phase-4-pipeline-and-delivery]] — download/verify, subset extraction, Parquet, README, demo
   - [[03 Plans/2026-09-12-stretch-matching-risk-fix]] — matching engine validated against NASDAQ's own executions, pre-trade risk (15c3-5), FIX 4.4 gateway
5. Reference — keep open while building:
   - [[04 Reference/ITCH 5.0 Message Layouts]] — offsets, lengths, codes. **Verified from the spec PDF, not recalled.**
   - [[04 Reference/Metrics Definitions]] — every formula, including the trade-sign rule for `P` messages
   - [[04 Reference/Interview Talking Points]] — forty minutes, with `[bracketed]` slots you fill only from measured numbers
6. Logs:
   - [[05 Log/Decision Log]] — every decision with the alternative it beat; the Binance decisions are kept as *superseded*
   - [[05 Log/Daily Log]] — did / broke / measured

## Key dates

| Date | What |
|---|---|
| **12 Sep 2026** | Today. Spec rewritten for ITCH. |
| **30 Sep 2026** | BofA 14365 closes. **Apply before this regardless of project state.** |
| ~19 Sep | Phase 1 done: full-day parse of `12302019` with zero structural violations |
| ~3 Oct | Phase 2 done: naive→optimised numbers published |
| ~17 Oct | Phase 3 done: validation report + queue finding + charts |
| ~24 Oct | Phase 4 done: README, demo, `v0.1.0` tag. **Gate opens.** |
| Oct–Dec | Interview window. Stretch work only if it doesn't threaten the above. |

## Status checklist

- [x] Spec written
- [ ] Spec reviewed and approved by me (Phuc)
- [x] Day-1: `12302019.NASDAQ_ITCH50.gz` downloaded (no md5sum published; 3,524,013,057 bytes), framing confirmed on 10k frames (Phase 1 Task 1) — 2026-09-12
- [x] Phase 1 complete — full day parsed, `Validation` counters all zero for structural checks — 2026-09-12 (naive median of 3: 753,133 msgs/s, 156.8 B/msg; crossed book 0 unexplained / 642 at halt resumptions, D26)
- [x] Phase 2 complete — before/after table exists — 2026-09-14 (`docs/perf.md`: G1 1.5x msgs/s, 26x less allocation, subset p99.9 14.8 -> 2.7 us, 0 GCs; ZGC table; JFR)
- [x] Phase 3 complete — seven checks reported, MeatPy diff clean on one symbol, queue chart exists — 2026-09-16 (`research/results/numbers_12302019.md`: structural 0, priority 0.19 % classified, MeatPy 391/391, 917 k joiner episodes; P(fill ≤ 5 s) top100 falls 50.6 % → 1.2 % across ahead0 deciles (upper bound), cancellations 88 % of queue movement; one day so far — days 2-3 in Phase 4)
- [ ] Phase 4 complete — README with numbers, demo live, tag `v0.1.0` — *2026-09-18: three days run (structural counters 0 on all three; the queue finding replicates), README + `crossday.md` + `docs/setup.md` + vault copy in `docs/`, repo pushed, `v0.1.0` tagged, CI green, 97 GB of data cleared with the small artifacts kept in the repo. **Two things left, both mine: flip Settings -> Pages -> Source: GitHub Actions (one click, then the demo is live), and record the 3-minute screencast from `docs/screencast.md`.** Day 3's spread/OFI cells stay empty unless I re-download that file*
- [ ] Stretch A (matching + risk) — only after the gate
- [ ] Stretch B (FIX) — only after Stretch A

## Links

- Repo: <https://github.com/PhamHongPhuc2712/order-book>
- Demo (GitHub Pages, deployed from `/demo`): <https://phamhongphuc2712.github.io/order-book/>
- Results: `research/results/numbers_<day>.md` per day, `research/results/crossday.md` across the three
- Setup on a new machine: `docs/setup.md` in the repo; `docs/vault/` is a copy of this vault, minus the JD and the interview script

## Ground rules

- Every task ends with a passing test and a commit.
- No placeholder in a plan. "Add error handling" is not an instruction.
- **Develop on a symbol subset, measure on the full day.** Iteration must take seconds. Full-day runs are for numbers.
- **Naive first, honestly.** The Phase 1 baseline is what a competent engineer writes first, not a straw man. The before/after is only a story if the "before" is credible.
- Never quote a number that isn't in `research/results/numbers_<day>.md` (generated by `report.py`) or `docs/perf.md`.

## Where things live

- Docs (this vault): `C:\Users\hongp\Documents\Phuc\Projects\bofa project`
- Code repo: `C:\Computing\GitHub\order-book` (GitHub: `order-book`)
- Data (git-ignored): `order-book/data/itch/` — raw `.gz`, decompressed `.bin`, subset files
- Spec PDF: `docs/NQTVITCHspecification.pdf` in the repo (copy from `nasdaqtrader.com/content/technicalsupport/specifications/dataproducts/NQTVITCHspecification.pdf`)
