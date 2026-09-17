# perf-raw/

The measurement record behind [`../perf.md`](../perf.md), kept in the repository because it cannot be regenerated
without re-running the whole Phase-2 benchmark matrix (several hours per collector).

| file | what it is |
|---|---|
| `12302019.txt` | every `ops/bench.sh` row: one line per (GC, heap, optimisation step, run) with messages, wall, msgs/s, allocation per message and the subset apply-latency percentiles. `ops/perf_table.py` turns this into the tables in `perf.md`. |
| `bench.log`, `bench2.log` | the benchmark driver's own logs, including the abandoned non-generational ZGC pass. |
| `tables.md` | `perf_table.py` output as generated, before it was written up. |
| `12302019.jfr.txt`, `jfr.log` | JFR summaries: allocation sites and GC pauses, naive and final, G1 and Generational ZGC. |
| `*.jfr` | the four recordings themselves (`jfr print --events ObjectAllocationSample …` to read them). |
| `final_fullday.txt`, `validate_fullday.txt` | the end-to-end full-day runs in the final configuration, with and without validation mode. |

`demo/make_demo.py --perf` reads `12302019.txt` to put the latency caption on the demo page, so keep the name.
