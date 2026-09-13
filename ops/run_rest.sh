#!/usr/bin/env bash
# Remaining Phase 2 measurements after the ZGC pass: G1 cross-check at 4g, then JFR + listener cost.
cd "$(dirname "$0")/.." || exit 1
echo "REST START $(date)" >> data/perf/bench2.log
bash ops/bench.sh 12302019 1 1 4g G1 "naive +dedupe" >> data/perf/bench2.log 2>&1
bash ops/bench_jfr.sh 12302019 > data/perf/jfr.log 2>&1
echo "ALL DONE $(date)" >> data/perf/bench2.log
