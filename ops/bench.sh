#!/usr/bin/env bash
# Phase 2 measurement matrix.
# Usage: ops/bench.sh <day> [fullDayRunsG1=3] [fullDayRunsZGC=1] [fullDayHeap=8g] [gcs="G1 Z"] [rows=all]
# Rows are cumulative; subset rows use --hist at 4g, full-day rows do not. Appends one line per run to data/perf/<day>.txt,
# tagged gc=<GC>@<heap> so tables at different heaps never mix.
set -u
DAY=${1:?day}; NG1=${2:-3}; NZ=${3:-1}; HEAP=${4:-8g}; GCS=${5:-"G1 Z"}; ONLY=${6:-all}
cd "$(dirname "$0")/.." || exit 1
LOB_QUIET=1 . ops/env.sh                      # JAVA_HOME, PATH and CP for this machine; LOB_QUIET keeps the log clean
OUT="data/perf/$DAY.txt"; mkdir -p data/perf     # absent on a fresh clone; tee would fail per row
ROWS=("naive|" "+mmap|--reader mmap" "+long|--reader mmap --map long" "+array|--reader mmap --map long --book array"
      "+pool|--reader mmap --map long --book array --pool" "+dedupe|--reader mmap --map long --book array --pool --dedupe")
gcflags() { case $1 in G1) echo "-XX:+UseG1GC";; Z) echo "-XX:+UseZGC -XX:+ZGenerational";; *) echo "-XX:+Use$1GC";; esac; }
# Z = Generational ZGC: JDK 21's default (non-generational) ZGC multi-maps the heap at three addresses, which Windows counts
# three times in the working set and which starves the page cache on a 13.7 GB machine with an 8 GB file.
run() { # gc label flags file hist heap
  local gc=$1 label=$2 flags=$3 file=$4 hist=$5 heap=$6
  local line; line=$(java -Xms${heap} -Xmx${heap} $(gcflags $gc) -cp "$CP" sg.phuc.lob.replay.Replay --file "$file" $flags $hist | head -2 | tr -d '\r' | tr '\n' ' ')
  echo "gc=$gc@$HEAP row=$label file=$(basename $file) $line" | tee -a "$OUT"
}
for GC in $GCS; do
  N=$NG1; [ "$GC" = Z ] && N=$NZ
  for r in "${ROWS[@]}"; do
    label=${r%%|*}; flags=${r#*|}
    if [ "$ONLY" != all ] && [[ " $ONLY " != *" $label "* ]]; then continue; fi
    run $GC "$label" "$flags" "data/itch/$DAY.sub20.bin" --hist 4g
    for i in $(seq 1 $N); do run $GC "$label" "$flags" "data/itch/$DAY.bin" "" $HEAP; done
  done
done
echo "DONE $(date)" | tee -a "$OUT"
