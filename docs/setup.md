# Setup — from a fresh clone to numbers

Everything here was run on the reference machine: **AMD Ryzen 5 6600HS laptop (6C/12T), 13.7 GB usable RAM, NVMe SSD,
Windows 11, Temurin JDK 21.0.12**. The timings are what that machine actually took, so they are the yardstick for "is my
run stuck or just slow".

No market data is in the repository (`data/` is git-ignored). A clone gives you the engine, the research layer, the
pipeline scripts and the generated results; §4 downloads the data again.

---

## 1. Toolchain

| need | version | note |
|---|---|---|
| JDK | **21** | Records, pattern-switch, sealed types. JDK 17 will not compile it. |
| Maven | 3.9.x | Build only; no plugins beyond surefire/jar/dependency. |
| Python | **3.12** | The research layer (DuckDB, pandas, pyarrow, matplotlib, meatpy). |
| git bash | any | `ops/run_days.sh`, and the `gzip` call inside `ops/make.ps1`, need a POSIX shell. On Windows that is Git's bash. |
| disk | **~35 GB per day** while a day is being built, plus up to 25 GB of temporary sort spill | see §6 |
| RAM | 16 GB comfortably, 13.7 GB works | at 13.7 GB never run two heavy stages at once |

On the reference machine neither tool is on `PATH`. Rather than hard-coding paths per script, source the environment
file, which finds a JDK 21 and Maven wherever this machine keeps them (`$JAVA_HOME`, `~/tools`, `/usr/lib/jvm`, Program
Files), exports `JAVA_HOME`/`PATH`, and sets `PY` (the venv interpreter) and `CP` (the runtime classpath, with the right
separator for the platform):

```bash
source ops/env.sh        # prints the java, mvn, python and classpath it resolved
```

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$HOME\tools\apache-maven-3.9.9\bin;$env:PATH"
```

`ops/bench.sh`, `ops/bench_jfr.sh`, `ops/make.sh` and `ops/run_days.sh` source `ops/env.sh` themselves, so there is
nothing to edit on a new machine. `ops/make.ps1` still sets its two paths inline — **change them there first when
running the PowerShell pipeline on a new Windows machine.**

**Linux / WSL.** Everything runs here too; only the pipeline driver differs (`ops/make.sh` instead of `ops/make.ps1`,
`ops/download.sh` instead of `ops/download.ps1` — same steps, same outputs, same exit-code discipline). With no root
access, install the toolchain under `~/tools` and nothing else is needed:

```bash
curl -L -o /tmp/jdk21.tar.gz https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse
mkdir -p ~/tools && tar -C ~/tools -xzf /tmp/jdk21.tar.gz
curl -L https://dlcdn.apache.org/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz | tar -C ~/tools -xz
```

## 2. Build and test the Java side

```bash
mvn -B verify          # both modules, all tests: unit, jqwik property, golden-hash, corruption
```

About 15 s warm (26 s cold), and it must be green before anything else. 57 tests in `core`, 8 in `replay`. The 5-minute
fixture and its pinned hash are both committed, so `GoldenReplayTest` asserts rather than skipping — its `assumeTrue`
guard is a Phase 1 leftover and only matters if the fixture is deleted.

Two modules: `core` (parser, book, engine, listeners — no file I/O except the frame readers) and `replay` (the three
mains `Probe`, `Filter`, `Replay`). The pipeline needs the HdrHistogram jar path once:

```bash
mvn -B install -DskipTests                                              # core must be resolvable first
mvn -q dependency:build-classpath -pl replay -Dmdep.outputFile="$PWD/cp.txt"
```

Both lines matter. Without the install, resolution fails with `Could not find artifact sg.phuc:core` — `-pl replay`
takes `replay` out of the reactor, so `core` has to come from the local repository. And `-Dmdep.outputFile` is resolved
against the *module* directory, so a relative `cp.txt` lands in `replay/cp.txt`, where nothing looks for it; the scripts
read the one at the repository root. `cp.txt` is git-ignored; `ops/env.sh` and `make.ps1` create it if it is missing.

## 3. Build the Python layer

```bash
cd research
python -m venv .venv                       # 3.12; `uv venv --python 3.12 .venv` also works and fetches 3.12 itself
./.venv/Scripts/python.exe -m pip install -r requirements.txt     # Windows
./.venv/bin/python        -m pip install -r requirements.txt      # Linux / macOS
./.venv/bin/python -m pytest -q            # 21 tests, about 20 s
```

Every script is invoked through the venv interpreter: `make.ps1` assumes `research/.venv/Scripts/python.exe`,
`make.sh` uses `$PY` from `ops/env.sh`, which is the `Scripts/` or `bin/` path as the platform requires. All nine
pinned versions install cleanly on CPython 3.12.

## 4. Get the data

NASDAQ publishes whole-day TotalView-ITCH 5.0 sample files at <https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/>. The three
days this project reports on are listed in `ops/days.txt`:

| day | file | `.gz` | decompressed | messages |
|---|---|---|---|---|
| `12302019` | `12302019.NASDAQ_ITCH50.gz` | 3,524,013,057 B | 8,251,407,909 B | 268,744,780 |
| `01302020` | `01302020.NASDAQ_ITCH50.gz` | 5,597,158,940 B | 12,952,050,754 B | 423,285,709 |
| `S120825` | `S120825-v50.txt.gz` | 8,775,891,119 B | 20,718,163,388 B | 650,338,709 |

```powershell
powershell -ExecutionPolicy Bypass -File ops/download.ps1 -Name 12302019.NASDAQ_ITCH50.gz
```

```bash
bash ops/download.sh 12302019.NASDAQ_ITCH50.gz
```

- `curl -C -` inside a resume loop, because a multi-hour single-connection download does get reset. Expect about
  **1.5 MB/s**: 40 minutes for day 1, over an hour for day 3.
- **None of the three publishes an `.md5sum`** (404), so the integrity check is `Probe`: it walks every `[len:2][msg]`
  frame and must report `mismatch=0`. A truncated or shifted file cannot survive that.
- The `.txt.gz` extension on the 2025 file is NASDAQ's own; the content is the same binary framing.

## 5. Build one day

```powershell
powershell -ExecutionPolicy Bypass -File ops/make.ps1 -Day 12302019 -Gz 12302019.NASDAQ_ITCH50.gz
```

```bash
bash ops/make.sh --day 12302019 --gz 12302019.NASDAQ_ITCH50.gz     # --steps report, --heap 8g
```

Each step runs as its own process: stdout to `data/derived/<day>/<step>.txt`, stderr to `<step>.txt.err`, exit code
checked. A step that dies — including one the OS kills, which leaves no traceback at all — stops the day (D32).
`-Steps` (`--steps` in `make.sh`) reruns a subset, e.g. `-Steps report` or `--steps convert,report`.

| step | what it does | writes | 12302019 | 01302020 | S120825 |
|---|---|---|---|---|---|
| `gunzip` | `gzip -dk -c` | `data/itch/<day>.bin` | ~80 s | 128 s | 264 s |
| `probe` | framing check and type histogram | `probe.txt` | 21 s | 32 s | 50 s |
| `filter` | 20-symbol subset for benchmarking | `data/itch/<day>.sub20.bin` | ~30 s | 35 s | 57 s |
| `replay` | full reconstruction: derived NDJSON, validation counters, priority dump, MeatPy export, demo ladder | `bbo/executions/trades/daily.ndjson`, `validation.json`, `priority.ndjson`, `meatpy_AAPL.csv`, `ladder_AAPL.ndjson` | 302 s | 553 s | 998 s |
| `probes` | pick probe symbols, then a second replay for the queue episodes | `probe_symbols.txt`, `episodes.ndjson` | ~290 s | 282 s | 530 s |
| `classify` | bucket every priority violation | `research/out/priority_<day>.md` | ~90 s | 93 s | 453 s |
| `convert` | NDJSON to Parquet (DuckDB, streaming) | `data/parquet/<table>/date=<day>/` | ~15 min | see below | — |
| `report` | spreads, OFI, queue tables and charts | `research/results/numbers_<day>.md`, CSVs, PNGs | ~21 min | 34 min | — |

The two slow stages, honestly:

- **`convert` is I/O bound and hates company.** Day 2's convert took **17 hours** while an 8.8 GB download ran on the
  same SSD; the same work uncontended is tens of minutes. Run it alone.
- **`report`** is DuckDB doing ASOF joins per batch of 250 symbols (D30). Day 2 split as spreads 28 min, OFI 6 min,
  queue 2 min. It holds about 5 GB and pins every core, which on a 13.7 GB laptop you will feel. `--threads 3
  --memory 3GB` is much gentler and roughly twice as slow; `--reuse spreads,ofi` re-renders the write-up from the saved
  CSVs in about two minutes, which is what you want when only the classification changed.

Then the demo slice, and for all three days the cross-day table:

```powershell
python demo/make_demo.py --ladder data/derived/12302019/ladder_AAPL.ndjson --symbol AAPL --date 12302019 --perf data/perf/12302019.txt
```

```bash
bash ops/run_days.sh                       # every day in ops/days.txt, one at a time, then crossday.py
cd research && "$PY" crossday.py --days 12302019 01302020 S120825 --derived ../data/derived
```

`run_days.sh` picks the pipeline driver for the platform it is on — `make.ps1` under Git bash on Windows, `make.sh`
otherwise — so the same command does every day on either.

How the committed golden fixture was cut, if you ever need to re-cut it for another day or symbol set:

```bash
java -cp "$CP" sg.phuc.lob.replay.Filter data/itch/12302019.bin \
     replay/src/test/resources/golden/subset-5min.bin AAPL,MSFT,INTC 34200000000000 34500000000000
```

## 6. Disk budget, and what is safe to delete

Measured, per day:

| artifact | 12302019 | 01302020 | S120825 | how to get it back |
|---|---|---|---|---|
| `.gz` download | 3.3 GB | 5.2 GB | 8.2 GB | re-download, 40 min to 1.5 h |
| `.bin` decompressed | 8.3 GB | 13.0 GB | 20.7 GB | `-Steps gunzip`, 1–5 min |
| `.sub20.bin` subset | 0.5 GB | 0.8 GB | 2.0 GB | `-Steps filter`, about 1 min |
| `data/derived/<day>/` NDJSON (`bbo.ndjson` is ~95 % of it) | 11 GB | 18 GB | 24 GB | `-Steps replay,probes`, 10–25 min |
| `data/parquet/**/date=<day>/` | 0.8 GB | 1.3 GB | ~1.8 GB | `-Steps convert`, needs the NDJSON |
| `data/parquet/.tmp` sort spill during `convert` | — | — | up to **25 GB** | transient, freed when the step ends |

Rules of thumb:

- Budget **1.5× the `bbo.ndjson` size** of free space for the sort spill, or `convert` dies late.
- The **Parquet tables are what is worth keeping**: 1–2 GB per day, and every research number is computed from them.
  `report.py` additionally needs three small files per day from `data/derived/<day>/` — `validation.json`,
  `meatpy_AAPL.csv`, `daily.ndjson` — and copies of those, with the run logs, are kept in
  `research/results/runs/<day>/` so the write-up stays reproducible after the bulk data is gone.
- The NDJSON, the `.bin` files and the subsets are **all regenerable** and are the first things to delete.
- `data/perf/derived.*` are Phase-2 equivalence-check copies (607 MB each) and can go. `data/perf/<day>.txt` and the
  `.jfr` recordings are the provenance of `docs/perf.md` and cannot be regenerated without re-running the whole
  benchmark matrix (hours).

## 7. Troubleshooting, from things that actually went wrong

- **`mvn` fails with `release 21 not supported`** — `java -version` is a 17 default. Export `JAVA_HOME` (§1).
- **`Probe` reports `mismatch > 0`** — partial download. Re-run `download.ps1`; it resumes.
- **A pipeline step "succeeds" with no output** — cannot happen any more (D32). If you see it, read
  `data/derived/<day>/<step>.txt.err`; an empty `.err` with a non-zero exit code means the OS killed the process, which
  means memory.
- **ZGC thrashes on this class of machine.** JDK 21's default ZGC multi-maps its heap, Windows counts it three times, and
  an 8 GB heap next to an 8 GB memory-mapped file on 13.7 GB of RAM spent 90 minutes paging. Benchmarks run at
  `-Xms4g -Xmx4g` under G1 (D27).
- **`java.lang.foreign` is still preview on 21**, so the reader uses chunked `MappedByteBuffer` windows rather than
  `MemorySegment` (D16 amendment).
- **`research/queue.py` would shadow the standard library** — the module is `queues.py`.
- **Logs from older runs are UTF-16LE** (PowerShell 5.1 `Tee-Object`); current steps write UTF-8. `crossday.py` sniffs
  the BOM and anything else reading those logs should too.
- **`Replay` prints numbers in the JVM's locale**: `msgs/s=768.144` means 768,144 under a comma-decimal locale.
  `crossday.py:num()` handles both; do not `float()` those strings naively.
- **The laptop goes unresponsive during `convert` or `report`** — one stage at a time, and
  `(Get-Process python).PriorityClass = 'BelowNormal'` hands the foreground back without losing the run.

## 8. Where the design lives

`docs/vault/` is a copy of the Obsidian vault this project is planned in: the design spec, the four phase plans, the
decision log (D1–D33), the daily log with every measurement behind every number, and the ITCH 5.0 message-layout
reference. Read `docs/vault/00 Index.md` first. Two caveats — the `[[wiki links]]` do not resolve on GitHub, and the
vault itself, not this copy, is the source of truth, so re-copy rather than editing in place.
