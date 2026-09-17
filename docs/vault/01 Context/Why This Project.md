---
title: Why This Project
created: 2026-09-11
revised: 2026-09-12
tags: [reasoning]
---

# Why this project

Short version of the reasoning trail, so future-me doesn't relitigate it.

## Constraints I set

1. **Real data, not synthetic.** Ground truth I didn't generate.
2. **Verifiable metrics.** Numbers a stranger can check.
3. **Near-zero cost.** A laptop.
4. **SWE + fintech + quant.** All three in one artifact.
5. **Targets BofA 14365** — Global Markets Technology, Java, equities.

## Alternatives, and why they lost

| Project | Killed by |
|---|---|
| Real-time mule-network scorer on PayNow rails | Synthetic ground truth (PaySim is a 2-hop chain, not a network). Better theatre, worse evidence. |
| Sanctions/PEP screening at pinned recall | Real data and a sharp framing, but **quant-weak**. |
| Agentic KYC governance harness | Best match to the "supervise AI output" signal; hard for a reviewer to verify quickly. |
| PQC crypto-agility scanner | Highest differentiation; wrong lane (cyber, not markets). |
| **Binance L2 live capture + book** (the v1 of this project) | Good, and it had a live-ops story. But: crypto ≠ equities; Level 2 only, so the queue result was *bounded*; correctness depended on an oracle with an inherent tolerance. Superseded — see below. |
| **NASDAQ ITCH 5.0 Level-3 reconstruction** | **Survives all five.** Real US equities; every individual order, so the queue result is *exact*; free public full-day files from NASDAQ itself; parsing 300M+ binary messages is a genuine systems problem; Java matches the stack. |

## Why ITCH replaced Binance (D15)

**Gained:** real equities (the "crypto isn't equities" caveat disappears); order-level data (exact queue position, exact cancel-vs-execute attribution); scale (hundreds of millions of messages a day — throughput and memory become first-class engineering); market structure (crosses, halts, LULD, imbalances); full determinism.

**Lost:** the live stream, and with it the websocket/reconnect/gap-recovery engineering, the always-on VM, and the ops layer. Also the external oracle — there is no exchange snapshot to diff against; correctness comes from invariants, a price-time-priority check against the feed's own executions, and an independent reference parser.

**Net:** narrower in surface, deeper in the middle. For 14365 that is the better trade — a markets data team's daily work is overwhelmingly *handling feeds like this correctly at volume*, not running websockets.

## What "outstanding" means here, honestly

Not "impressive build." No citable case exists of a personal project producing an offer at a Singapore bank. What a project *can* do:

- **Produce a number that didn't exist before.** The exact fill-probability curve conditioned on queue position for real US equities, sliced by liquidity tier, is that number.
- **State a hypothesis before measuring; report negatives.**
- **Go deep enough that no question lands outside lived experience** — which is why the naive→optimised story matters: you will have personally hit every wall.

## Limitations to volunteer before being asked

- **Sample days only.** A handful of NASDAQ-published days, not a continuous history. Findings are per-day; cross-day consistency is checked on two or three days, not months.
- **NASDAQ only.** ITCH shows NASDAQ's book and NASDAQ's executions — not the consolidated market. Comparing to consolidated volume (Yahoo etc.) is *wrong*; say so.
- **No live component.** This is not a feed handler. It's the reconstruction and analysis core of one. Know what you'd add: transport sequencing (MoldUDP64), gap requests, snapshot recovery (GLIMPSE), multi-threading by symbol partition.
- **`P` trades are unsigned since 2014** — the feed sets Buy/Sell to "B" always. Signed metrics use `E`/`C` only.
- **Reference-parser agreement is not ground truth.** If MeatPy and I misread the same spec line, we agree and we're both wrong.
