#!/usr/bin/env bash
# Phase 2 measurement matrix. Usage: ops/bench.sh <day> [fullDayRunsG1=3] [fullDayRunsZGC=1]
# Rows are cumulative; subset rows use --hist, full-day rows do not. Appends one line per run to data/perf/<day>.txt.
set -u
DAY=${1:?day}; NG1=${2:-3}; NZ=${3:-1}
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
CP="replay/target/classes;core/target/classes;$(cat cp.txt)"
OUT="data/perf/$DAY.txt"
ROWS=("naive|" "+mmap|--reader mmap" "+long|--reader mmap --map long" "+array|--reader mmap --map long --book array"
      "+pool|--reader mmap --map long --book array --pool" "+dedupe|--reader mmap --map long --book array --pool --dedupe")
run() { # gc label flags file hist
  local gc=$1 label=$2 flags=$3 file=$4 hist=$5 heap=$6
  local line; line=$(java -Xms${heap} -Xmx${heap} -XX:+Use${gc}GC -cp "$CP" sg.phuc.lob.replay.Replay --file "$file" $flags $hist | head -2 | tr '\n' ' ')
  echo "gc=$gc row=$label file=$(basename $file) $line" | tee -a "$OUT"
}
for GC in G1 Z; do
  N=$NG1; [ "$GC" = Z ] && N=$NZ
  for r in "${ROWS[@]}"; do
    label=${r%%|*}; flags=${r#*|}
    run $GC "$label" "$flags" "data/itch/$DAY.sub20.bin" --hist 4g
    for i in $(seq 1 $N); do run $GC "$label" "$flags" "data/itch/$DAY.bin" "" 8g; done
  done
done
echo "DONE $(date)" | tee -a "$OUT"
