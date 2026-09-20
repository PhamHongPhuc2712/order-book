#!/usr/bin/env bash
# Phase 4 Task 2 Step 2: run the one-day pipeline for every day in ops/days.txt, one at a time (13.7 GB of RAM on
# the reference machine allows exactly one heavy job), then write the cross-day table. A day whose pipeline fails
# stops the chain with its log named.
# Usage: bash ops/run_days.sh [day ...]        # default: every day in ops/days.txt
# Downloads are a separate step (ops/download.sh, ops/download.ps1); the .gz files must already be in data/itch/.
set -u
cd "$(dirname "$0")/.." || exit 1
LOB_QUIET=1 . ops/env.sh                       # JAVA_HOME, PY, CP; also tells us which platform we are on

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
  if [ "${LOB_WIN:-0}" = 1 ]; then
    powershell -NoProfile -ExecutionPolicy Bypass -File ops/make.ps1 -Day "$day" -Gz "$gz" > "$log" 2>&1
  else
    bash ops/make.sh --day "$day" --gz "$gz" > "$log" 2>&1
  fi
  rc=$?
  echo "== make $day exit $rc $(date)"
  if [ $rc -ne 0 ]; then
    tail -20 "$log"
    echo "== stopping: $day failed, see $log"
    exit $rc
  fi
done

echo "== crossday $(date)"
(cd research && "$PY" crossday.py --days "${days[@]}" --derived ../data/derived) || exit $?
echo "ALL-DAYS-DONE $(date)"
