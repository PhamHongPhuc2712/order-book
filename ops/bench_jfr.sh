#!/usr/bin/env bash
# Phase 2 Task 6 + Task 5: JFR on the subset for naive vs final under G1 and ZGC (GC count, longest pause, top allocation
# sites, top execution-sample frames), then the listener cost (NullListener vs DerivedWriter, with and without dedupe).
# Usage: ops/bench_jfr.sh <day>   -> data/perf/<day>.jfr.txt  (and data/perf/*.jfr, git-ignored)
set -u
DAY=${1:?day}
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"
CP="replay/target/classes;core/target/classes;$(cat cp.txt)"
SUB="data/itch/$DAY.sub20.bin"; OUT="data/perf/$DAY.jfr.txt"; : > "$OUT"
log() { echo "$@" | tee -a "$OUT"; }

for GC in G1 Z; do
  for cfg in "naive|" "final|--final"; do
    label=${cfg%%|*}; flags=${cfg#*|}; jfr="data/perf/$DAY.$label.$GC.jfr"
    log "=== JFR $label $GC ==="
    java -Xms4g -Xmx4g -XX:+Use${GC}GC -XX:StartFlightRecording=filename=$jfr,settings=profile -cp "$CP" \
      sg.phuc.lob.replay.Replay --file "$SUB" $flags --hist 2>&1 | grep -E "^config=|^apply" | tee -a "$OUT"
    # GC: count and pauses
    jfr print --events jdk.GarbageCollection "$jfr" | grep -E "sumOfPauses|longestPause" | tr -d ' ' \
      | awk -F= '{v=$2; sub(/ms$/,"",v); sub(/us$/,"",v); if ($1=="sumOfPauses") {n++; s+=v} else if (v+0>lp) lp=v} END {printf "gcCount=%d sumOfPauses=%s longestPause=%s (units as printed by jfr; see raw)\n", n, s, lp}' | tee -a "$OUT"
    jfr summary "$jfr" | grep -E "jdk.GarbageCollection |jdk.ObjectAllocationSample |jdk.ExecutionSample " | tee -a "$OUT"
    log "--- top allocation sample classes:"
    jfr print --events jdk.ObjectAllocationSample "$jfr" | grep -E "objectClass = " | sed 's/.*objectClass = //; s/ (classLoader.*//' | sort | uniq -c | sort -rn | head -8 | tee -a "$OUT"
    log "--- top execution sample frames (top of stack):"
    jfr print --events jdk.ExecutionSample "$jfr" | grep -A1 "stackTrace = \[" | grep -vE "stackTrace|^--" | sed 's/^ *//; s/line:.*//' | sort | uniq -c | sort -rn | head -12 | tee -a "$OUT"
  done
done

log "=== listener cost (final config, G1, subset, no --hist) ==="
for v in "null|" "derived+dedupe|--out data/perf/derived.dedupe" ; do
  label=${v%%|*}; extra=${v#*|}
  java -Xms4g -Xmx4g -XX:+UseG1GC -cp "$CP" sg.phuc.lob.replay.Replay --file "$SUB" --final $extra | head -1 | sed "s/^/listener=$label /" | tee -a "$OUT"
done
java -Xms4g -Xmx4g -XX:+UseG1GC -cp "$CP" sg.phuc.lob.replay.Replay --file "$SUB" --reader mmap --map long --book array --pool --out data/perf/derived.nodedupe | head -1 | sed "s/^/listener=derived+nodedupe /" | tee -a "$OUT"
log "--- derived outputs identical with and without dedupe:"
(cd data/perf && sha256sum derived.dedupe/*.ndjson derived.nodedupe/*.ndjson) | tee -a "$OUT"

log "=== clean re-run of the naive subset histogram (G1) ==="
java -Xms4g -Xmx4g -XX:+UseG1GC -cp "$CP" sg.phuc.lob.replay.Replay --file "$SUB" --hist | grep -E "^config=|^apply" | tee -a "$OUT"
log "DONE $(date)"
