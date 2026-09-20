#!/usr/bin/env bash
# One-day pipeline, Linux/macOS port of ops/make.ps1 (same steps, same order, same outputs):
# decompress -> Probe -> Filter -> Replay (derived, validation, priority dump, MeatPy export, ladder) -> pick probes
# -> Replay (episodes) -> classify priorities -> Parquet -> report.
# Usage: bash ops/make.sh --day 01302020 --gz 01302020.NASDAQ_ITCH50.gz [--steps report]
#        --steps is a comma list to run only some of: gunzip,probe,filter,replay,probes,classify,convert,report
# Every JVM runs at 4 GB G1 (D27) unless --heap says otherwise. Logs go to data/derived/<day>/*.txt (stderr to
# *.txt.err); numbers to research/results/numbers_<day>.md. Every step runs as its own process with its exit code
# checked and its stderr kept: a step that dies — a traceback, or an OS kill with no traceback at all — fails the
# whole chain instead of letting it print "complete" with no output written (D32).
set -u

cd "$(dirname "$0")/.." || exit 1
LOB_QUIET=1 . ops/env.sh

DAY=""; GZ=""; STEPS="all"; HEAP="4g"
SYMBOLS="AAPL,MSFT,AMZN,GOOG,INTC,CSCO,NVDA,TSLA,AMD,QQQ,SPY,MU,SBUX,PYPL,ADBE,CMCSA,NFLX,BKNG,ILMN,AAL"
LADDER="AAPL"; MEATPY="AAPL"; PER_TIER=20
while [ $# -gt 0 ]; do
  case "$1" in
    --day)      DAY=$2; shift 2 ;;
    --gz)       GZ=$2; shift 2 ;;
    --steps)    STEPS=$2; shift 2 ;;
    --symbols)  SYMBOLS=$2; shift 2 ;;
    --ladder)   LADDER=$2; shift 2 ;;
    --meatpy)   MEATPY=$2; shift 2 ;;
    --per-tier) PER_TIER=$2; shift 2 ;;
    --heap)     HEAP=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$DAY" ] || { echo "--day is required" >&2; exit 2; }
[ -n "$GZ" ] || { echo "--gz is required" >&2; exit 2; }
[ -x "$PY" ] || { echo "research venv missing at $PY (see docs/setup.md §3)" >&2; exit 2; }

BIN="data/itch/$DAY.bin"; SUB="data/itch/$DAY.sub20.bin"; OUT="data/derived/$DAY"
mkdir -p "$OUT" data/itch
if [ ! -f replay/target/classes/sg/phuc/lob/replay/Replay.class ]; then mvn -q -B -pl replay -am package -DskipTests || exit $?; fi
[ -n "${CP:-}" ] || { echo "no classpath: run mvn -B install -DskipTests" >&2; exit 2; }
JAVA="$JAVA_HOME/bin/java"
JVM=("-Xms$HEAP" "-Xmx$HEAP" "-XX:+UseG1GC")

step() { [ "$STEPS" = all ] && return 0; case ",$STEPS," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

# Run one external command, keeping stdout in $log and stderr in "$log.err"; abort the day on a non-zero exit code.
# $show: all | last | first3 | none — how much of stdout is echoed into the chain log.
native() {
  local label=$1 log=$2 show=$3; shift 3
  local t0; t0=$(date +%s)
  echo "== $label $(date +%H:%M:%S)"
  mkdir -p "$(dirname "$log")"
  "$@" >"$log" 2>"$log.err"
  local rc=$?
  case "$show" in
    last)   tail -n 1 "$log" ;;
    first3) head -n 3 "$log" ;;
    none)   ;;
    *)      cat "$log" ;;
  esac
  [ -s "$log.err" ] && sed 's/^/   [stderr] /' "$log.err"
  if [ $rc -ne 0 ]; then
    echo "$label failed for $DAY: exit code $rc (stdout $log, stderr $log.err)" >&2
    exit $rc
  fi
  echo "   $label done in $(( $(date +%s) - t0 )) s"
}

if step gunzip; then
  # A failed gunzip still leaves the redirect's empty target behind, so "the .bin exists" is not evidence that it is
  # usable: a partial file is deleted and re-made, and the size is asserted on every run rather than only on the run
  # that wrote it. Skipping decompression because of a 0-byte leftover is exactly the silent success D32 was about.
  if [ -f "$BIN" ] && [ "$(wc -c < "$BIN")" -lt 1048576 ]; then
    echo "== gunzip: discarding partial $BIN ($(wc -c < "$BIN") bytes)"
    rm -f "$BIN"
  fi
  if [ ! -f "$BIN" ]; then
    native gunzip "$OUT/gunzip.txt" all bash -c "gzip -dk -c 'data/itch/$GZ' > '$BIN'"
  fi
  size=$(wc -c < "$BIN" 2>/dev/null || echo 0)
  if [ "$size" -lt 1048576 ]; then
    rm -f "$BIN"
    echo "gunzip left no usable $BIN for $DAY ($size bytes)" >&2; exit 1
  fi
  echo "   $BIN is $size bytes"
fi
if step probe; then
  native probe "$OUT/probe.txt" all "$JAVA" -cp "$CP" sg.phuc.lob.replay.Probe "$BIN"
  # mismatch=0 is trivially true of an empty file, so the frame count has to be positive as well.
  grep -q "mismatch=0" "$OUT/probe.txt" || { echo "framing check failed for $DAY (see $OUT/probe.txt)" >&2; exit 1; }
  frames=$(sed -n 's/^frames=\([0-9]*\).*/\1/p' "$OUT/probe.txt" | head -1)
  [ "${frames:-0}" -gt 0 ] || { echo "probe read 0 frames from $BIN for $DAY (see $OUT/probe.txt)" >&2; exit 1; }
fi
if step filter && [ ! -f "$SUB" ]; then
  native filter "$OUT/filter.txt" all "$JAVA" -cp "$CP" sg.phuc.lob.replay.Filter "$BIN" "$SUB" "$SYMBOLS"
fi
if step replay; then
  # The dump cap is above any day's violation count (33,674 is the largest of the three), so the classification
  # covers the population rather than a sample and the days are comparable. One line per violation; a few MB.
  native replay "$OUT/replay.txt" all "$JAVA" "${JVM[@]}" -cp "$CP" sg.phuc.lob.replay.Replay \
    --file "$BIN" --final --validate --dump-priority 50000 --meatpy "$MEATPY" --ladder "$LADDER" --out "$OUT"
fi
if step probes; then
  native "pick probes" "$OUT/pick_probes.txt" last \
    "$PY" research/pick_probes.py --derived "$OUT" --out "$OUT/probe_symbols.txt" --per-tier "$PER_TIER"
  mkdir -p "$OUT/probes"
  native episodes "$OUT/probes.txt" all "$JAVA" "${JVM[@]}" -cp "$CP" sg.phuc.lob.replay.Replay \
    --file "$BIN" --final --no-derived --probe-symbols "@$OUT/probe_symbols.txt" --out "$OUT/probes"
  mv -f "$OUT/probes/episodes.ndjson" "$OUT/episodes.ndjson"
fi
if step classify; then
  native classify "$OUT/classify.txt" first3 \
    "$PY" research/classify_priority.py --derived "$OUT" --out "research/out/priority_$DAY.md"
fi
if step convert; then
  native convert "$OUT/convert.txt" all "$PY" research/convert.py --derived "$OUT" --out data/parquet --date "$DAY"
fi
if step report; then
  native report "$OUT/report.txt" all "$PY" research/report.py --derived "$OUT" --parquet data/parquet --date "$DAY"
  [ -f "research/results/numbers_$DAY.md" ] || { echo "report step left no research/results/numbers_$DAY.md for $DAY" >&2; exit 1; }
  echo "== $DAY complete: research/results/numbers_$DAY.md"
else
  echo "== $DAY steps done: $STEPS"
fi
