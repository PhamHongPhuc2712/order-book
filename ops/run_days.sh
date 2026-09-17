#!/usr/bin/env bash
# Phase 4 Task 2 Step 2: run ops/make.ps1 for every day in ops/days.txt, one at a time (13.7 GB of RAM allows exactly
# one heavy job), then write the cross-day table. A day whose pipeline fails stops the chain with its log named.
# Usage: bash ops/run_days.sh [day ...]        # default: every day in ops/days.txt
# Downloads are a separate step (ops/download.ps1); this script assumes the .gz files are already in data/itch/.
set -u
cd "$(dirname "$0")/.." || exit 1

days=()
gzs=()
while read -r day gz _; do
  case "$day" in ''|'#'*) continue;; esac
  if [ $# -gt 0 ]; then
    for want in "$@"; do [ "$want" = "$day" ] && { days+=("$day"); gzs+=("$gz"); }; done
  else
    days+=("$day"); gzs+=("$gz")
  fi
done < ops/days.txt

[ ${#days[@]} -eq 0 ] && { echo "no days selected (see ops/days.txt)"; exit 1; }

for i in "${!days[@]}"; do
  day="${days[$i]}"; gz="${gzs[$i]}"; log="data/derived/make_$day.log"
  mkdir -p "data/derived/$day"
  echo "== make $day start $(date)"
  powershell -NoProfile -ExecutionPolicy Bypass -File ops/make.ps1 -Day "$day" -Gz "$gz" > "$log" 2>&1
  rc=$?
  echo "== make $day exit $rc $(date)"
  if [ $rc -ne 0 ]; then
    tail -20 "$log"
    echo "== stopping: $day failed, see $log"
    exit $rc
  fi
done

echo "== crossday $(date)"
(cd research && .venv/Scripts/python.exe crossday.py --days "${days[@]}" --derived ../data/derived) || exit $?
echo "ALL-DAYS-DONE $(date)"
