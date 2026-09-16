#!/usr/bin/env bash
# Phase 4 Task 2 Step 2: run the remaining two days as their downloads land. Waits for data/itch/download.log to report
# each file, and for any determinism check to finish, before starting a day (one heavy job at a time on 13.7 GB).
cd "$(dirname "$0")/.." || exit 1
wait_for() { until grep -qE "$1" "$2" 2>/dev/null; do sleep 60; done; }
wait_for "DETERMINISM-CHECK-DONE" data/derived/determinism.log
for pair in "01302020 01302020.NASDAQ_ITCH50.gz" "S120825 S120825-v50.txt.gz"; do
  set -- $pair; day=$1; gz=$2
  echo "== waiting for $gz $(date)"
  wait_for "downloaded $gz|md5 ok $gz" data/itch/download.log
  echo "== make $day start $(date)"
  powershell -NoProfile -ExecutionPolicy Bypass -File ops/make.ps1 -Day "$day" -Gz "$gz" > "data/derived/make_$day.log" 2>&1
  echo "== make $day exit $? $(date)"
  grep -E "complete:|Exception|failed|error" "data/derived/make_$day.log" | head -3
done
echo "ALL-DAYS-DONE $(date)"
