---
title: Phase 4 — Pipeline and Delivery — Implementation Plan
date: 2026-09-12
spec: "[[02 Spec/2026-09-12-itch-lob-design]]"
status: in progress — started 2026-09-16
depends: phase-3-results
tags: [plan, phase-4, delivery]
---

# Phase 4: Pipeline and Delivery — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** Reproducible data pipeline for all three days, a README whose every number comes from `numbers.md`, a static demo replaying one symbol through the opening cross, a 3-minute screencast, and the `v0.1.0` tag that opens the gate to the stretch plan.

**Architecture:** Scripts only — no new engine code except `LadderWriter` (top-10 snapshots for the demo). `make.ps1` chains download → verify → decompress → filter → replay → convert → report so a full rebuild is one command per day.

**Spec:** [[02 Spec/2026-09-12-itch-lob-design]]

## File structure

```
ops/
├── download.ps1        curl -C - + md5 check
├── make.ps1            one-day pipeline
└── days.txt            12302019  01302020  S120825
core/src/main/java/sg/phuc/lob/engine/LadderWriter.java
demo/ index.html · make_demo.py · data.js (generated)
README.md · docs/perf.md (Phase 2) · research/out/numbers.md (Phase 3)
```

---

### Task 1: Download and verify the other two days

- [x] **Step 1: `ops/download.ps1`** — *done 2026-09-16: `curl -C -` with `--retry` and a resume loop; md5 verified when NASDAQ publishes one (neither of the two remaining files has one: 404), otherwise `Probe` framing is the check* —
```powershell
param([string]$Name)   # e.g. 01302020.NASDAQ_ITCH50.gz or S120825-v50.txt.gz
$base = "https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/"
New-Item -ItemType Directory -Force data\itch | Out-Null
curl.exe -L -C - -o "data\itch\$Name" "$base$Name"
try { curl.exe -f -L -o "data\itch\$Name.md5sum" "$base$Name.md5sum" } catch { Write-Output "no md5sum published for $Name" }
if (Test-Path "data\itch\$Name.md5sum") {
  $have = (Get-FileHash "data\itch\$Name" -Algorithm MD5).Hash.ToLower()
  $want = (Get-Content "data\itch\$Name.md5sum").Split(" ")[0].ToLower()
  if ($have -ne $want) { throw "MD5 mismatch for $Name" } else { Write-Output "md5 ok $Name" }
}
```
- [x] **Step 2: Run for `01302020.NASDAQ_ITCH50.gz` and `S120825-v50.txt.gz`** — *done 2026-09-17: neither publishes an `.md5sum` (404), so `Probe` framing is the integrity check, and both pass. `01302020`: gz 5,597,158,940 B -> bin 12,952,050,754 B (2.31x), **423,285,709 frames, mismatch=0**, 8,915 symbols. `S120825`: gz 8,775,891,119 B -> bin 20,718,163,388 B (2.36x), **650,338,709 frames, mismatch=0**, 12,102 `R` directory messages. The 2025 day's message mix is different: `U` replaces are 23.6 % of it (153,561,578) against 8.1 % on 12302019* (the second is 8.8 GB — start it and do something else). Decompress each with `gzip -dk -c … > data/itch/<day>.bin`. Run `Probe` on each: `mismatch=0` expected. Record sizes and frame counts.
- [x] **Step 3: Commit** — *done 2026-09-16 in `9031097` (download.ps1 + make.ps1 + demo in one commit)* — `git add ops && git commit -m "ops: download + md5 verify"`

---

### Task 2: One-command pipeline

- [x] **Step 1: `ops/make.ps1`** — *done 2026-09-16, differs from below: 4 GB G1 (D27); two replay passes (derived + validation + priority dump + MeatPy export + ladder, then probes only, because the probe symbols come from that day's `daily.ndjson`); `pick_probes.py`, `classify_priority.py` and `report.py` in the chain; `-Steps` to rerun a subset; logs in `data/derived/<day>/*.txt`* —
```powershell
param([string]$Day, [string]$Gz, [string]$Symbols = "AAPL,MSFT,AMZN,GOOG,INTC,CSCO,NVDA,TSLA,AMD,QQQ,SPY,MU,SBUX,PYPL,ADBE,CMCSA,NFLX,BKNG,ILMN,AAL", [string]$ProbeSymbols)
$cp = (Get-Content cp.txt) + ";replay\target\classes;core\target\classes"
$jvm = @("-Xms10g", "-Xmx10g", "-XX:+UseZGC")
if (-not (Test-Path "data\itch\$Day.bin")) { bash -c "gzip -dk -c data/itch/$Gz > data/itch/$Day.bin" }
java -cp $cp sg.phuc.lob.replay.Probe "data\itch\$Day.bin" 10000
java -cp $cp sg.phuc.lob.replay.Filter "data\itch\$Day.bin" "data\itch\$Day.sub20.bin" $Symbols
java @jvm -cp $cp sg.phuc.lob.replay.Replay --file "data\itch\$Day.bin" --reader mmap --map long --book array --pool --dedupe --validate --dump-priority 500 --probe-symbols $ProbeSymbols --out "data\derived\$Day" | Tee-Object "data\derived\$Day\replay.txt"
Push-Location research; .\.venv\Scripts\python.exe convert.py --derived "..\data\derived\$Day" --out ..\data\parquet --date $Day; .\.venv\Scripts\python.exe report.py --parquet ..\data\parquet --date $Day; Pop-Location
```
- [x] **Step 2: Run for all three days** — *done 2026-09-17/18. `01302020`'s report was re-run after the overnight silent failure (D32). `S120825` ran end to end except the quote-table conversion: its 23 GB `bbo.ndjson` was still converting when the laptop ran out of headroom, so `episodes`/`executions`/`trades`/`daily`/`priority` were converted alone and `report.py --skip spreads,ofi` produced the day's queue result; its spread and OFI cells are empty and labelled as such. **Structural counters 0 on all three days**, priority 0.186 / 0.129 / 0.234 % fully classified, queue anomaly rate 0.00 % over 3,255,268 episodes, and the conditional curve replicates (smallest-decile P(fill <= 5 s) upper bound 50.6 / 53.0 / 49.8 %, largest 1.2 / 8.2 / 3.2 %). Comparison in `research/results/crossday.md`* — Expected: three `numbers.md` sections; three `validation.json`. Compare structural counters across days (all zero) and the queue curves (shape consistent; levels differ — that's a result).
- [x] **Step 3: Commit** — *done 2026-09-16 in `9031097`; hardened 2026-09-17 in `becc80c` (every step runs through `Start-Process`, exit code checked, stderr kept, `ops/days.txt` + `run_days.sh` drive the days)* — `git add ops && git commit -m "ops: one-command per-day pipeline"`

---

### Task 3: `LadderWriter` and the demo

**Files:** `core/src/main/java/sg/phuc/lob/engine/LadderWriter.java`; `demo/make_demo.py`; `demo/index.html`

- [x] **Step 1: `LadderWriter(symbol, fromNs, toNs, everyNs=100ms, depth=10)`** — *done 2026-09-16: `Book.levelAt` on the interface (`TreeBook` walks, `ArrayBook` indexes); also writes the symbol's `Q` cross trades and system events as marker lines; `Replay --ladder SYM [--ladder-from --ladder-to]`; `LadderWriterTest`* — — a `Listener` that on each `onBbo` for the symbol, if `ts >= nextTick`, walks the engine's book (`book(loc)` → for each side, iterate `level(...)` via the `Book` interface — add `Level levelAt(byte side, int index)` to `Book`, trivial in `ArrayBook`) and writes `{"t":ts,"b":[[p,sh,count]…10],"a":[…]}`. Window: **9:28:00–9:32:00** = `[33_880e9, 34_320e9)` — through the opening cross.
- [x] **Step 2: `make_demo.py`** — *done 2026-09-16: `window.META`, `window.PERF` (final-row G1 subset percentiles + median full-day msgs/s parsed from `data/perf/<day>.txt`), `window.SLICE`* — — reads the ladder NDJSON, converts price to dollars, embeds as `window.SLICE` in `data.js`, plus the latency percentiles from `replay.txt` as `window.PERF`.
- [x] **Step 3: `index.html`** — *done 2026-09-16: play/pause, 1/10/100×, scrubber, 10-level ladder with order counts and depth bars, crossed-book indicator pre-open, cross marker, event log, latency caption; no external requests* — — same skeleton as the Binance-era demo: play/pause, speed 1/10/100×, a 10-level ladder with order **count** shown next to shares (that's the Level-3 detail the Binance version couldn't show), the timestamp as `HH:MM:SS.mmm`, and a marker when the `Q` cross message arrives (from a `system` line in the slice). Latency percentiles in a caption. No external requests.
- [x] **Step 4: Publish** — *done 2026-09-18: `.github/workflows/pages.yml` deploys `/demo` (upload-pages-artifact + deploy-pages) on any push touching `demo/`; repo pushed to <https://github.com/PhamHongPhuc2712/order-book>, links in README and above. **One manual step left for me: Settings -> Pages -> Source: GitHub Actions** — the first run failed at `configure-pages` because enabling Pages from the workflow token is not permitted; after the toggle the workflow deploys to <https://phamhongphuc2712.github.io/order-book/>*

---

### Task 4: README and screencast

- [x] **Step 1: README structure** — *done 2026-09-18: three-day correctness table, the naive->optimised performance table, the day-1 research detail, a generated `Cross-day` section (scale + research, from `crossday.md`), design, honest limitations, what I would change at a bank, and `docs/setup.md` for running it. Every cell traces to `numbers_<day>.md`, `crossday.md` or `docs/perf.md`*
```markdown
# lob-reconstruct — NASDAQ ITCH 5.0 Level-3 order book reconstruction

One paragraph: real feed, full day, correctness by invariants + priority check, naive→optimised, exact queue result.

## Results (12302019 · 01302020 · S120825; <CPU>, <RAM>, JDK 21, flags)
### Correctness
| check | 12302019 | 01302020 | S120825 |
| structural violations | 0 | 0 | 0 |
| crossed in market hours | … | … | … |
| priority violations / checked | … | … | … |
| MeatPy top-of-book diff (AAPL) | 0 / 391 minutes | — | — |
| live orders at C | … | … | … |
### Performance (naive → optimised)
| step | msgs/s | wall | p50 | p99 | p99.9 | B/msg |   ← from docs/perf.md
### Research
- queue: P(fill ≤ 5 s) by tier; conditional on initial queue size; cancel share ahead of joiners
- spreads by tier; OFI R² by tier × session
charts: queue_*.png · queue_cond_*.png · spreads_*.png

## Design (10 lines; link docs/)
## Honest limitations (verbatim from "Why This Project")
## What I'd change to run this at a bank (transport sequencing, GLIMPSE recovery, symbol-partitioned threads, telemetry)
## Run it (make.ps1 <day> <gz>)
## Demo · Screencast
```
- [ ] **Step 2: Screencast (3 minutes)**
1. 0:00 — `Probe` on the raw file: "this is what the exchange actually sends"
2. 0:30 — the demo ladder through the opening cross at 10×
3. 1:00 — `validation.json` for one day: zero structural violations; the priority rate and what the buckets mean
4. 1:40 — `docs/perf.md` table; point at B/msg going to zero and the p99.9 collapse
5. 2:20 — the conditional queue chart; one sentence with the number
6. 2:50 — what changes to run it at a bank
- [x] **Step 3: Tag** — *done 2026-09-18: `v0.1.0` annotated and pushed, along with `phase-1-baseline`, `phase-2-optimised` and `phase-3-results`. CI green on the pushed tree. **Gate opens.***

## Self-review
- **Spec coverage:** §11 delivery; D20 three days (Tasks 1–2); §5.5 demo (Task 3); the "what I'd change" section answers the non-goals in §2.
- **Placeholder scan:** README numbers are `…` by design — they are filled from `numbers.md` and `perf.md` only; the structure is fixed here so no number is invented at write-up time.
