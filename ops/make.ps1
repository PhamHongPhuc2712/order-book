# One-day pipeline (Phase 4 Task 2): decompress -> Probe -> Filter -> Replay (derived, validation, priority dump, MeatPy
# export, ladder) -> pick probes -> Replay (episodes) -> classify priorities -> Parquet -> report.
# Usage: powershell -ExecutionPolicy Bypass -File ops/make.ps1 -Day 01302020 -Gz 01302020.NASDAQ_ITCH50.gz [-Steps all]
#        -Steps is a comma list to run only some of: gunzip,probe,filter,replay,probes,classify,convert,report
# Every JVM runs at 4 GB G1 (D27). Logs go to data/derived/<Day>/*.txt; numbers to research/results/numbers_<Day>.md.
param(
  [Parameter(Mandatory = $true)][string]$Day,
  [Parameter(Mandatory = $true)][string]$Gz,
  [string]$Symbols = "AAPL,MSFT,AMZN,GOOG,INTC,CSCO,NVDA,TSLA,AMD,QQQ,SPY,MU,SBUX,PYPL,ADBE,CMCSA,NFLX,BKNG,ILMN,AAL",
  [string]$Ladder = "AAPL",
  [string]$MeatPy = "AAPL",
  [int]$PerTier = 20,
  [string]$Steps = "all"
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $root
try {
  $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
  $env:PATH = "$env:JAVA_HOME\bin;$HOME\tools\apache-maven-3.9.9\bin;$env:PATH"
  $py = Join-Path $root "research\.venv\Scripts\python.exe"
  $bin = "data\itch\$Day.bin"; $sub = "data\itch\$Day.sub20.bin"; $out = "data\derived\$Day"
  New-Item -ItemType Directory -Force $out | Out-Null
  if (-not (Test-Path "cp.txt")) { & mvn -q dependency:build-classpath -pl replay "-Dmdep.outputFile=cp.txt" }
  if (-not (Test-Path "replay\target\classes\sg\phuc\lob\replay\Replay.class")) { & mvn -q -B -pl replay -am package -DskipTests }
  $cp = "replay\target\classes;core\target\classes;" + (Get-Content cp.txt -Raw).Trim()
  $jvm = @("-Xms4g", "-Xmx4g", "-XX:+UseG1GC")
  function Step($name) { return ($Steps -eq "all") -or (($Steps -split ",") -contains $name) }
  function Run($label, $block) { $t = Get-Date; Write-Output "== $label $($t.ToString('HH:mm:ss'))"; & $block; Write-Output "   $label done in $([int]((Get-Date) - $t).TotalSeconds) s" }

  if ((Step "gunzip") -and -not (Test-Path $bin)) {
    Run "gunzip" { & bash -c "gzip -dk -c data/itch/$Gz > data/itch/$Day.bin" }
  }
  if (Step "probe") {
    Run "probe" { & java -cp $cp sg.phuc.lob.replay.Probe $bin | Tee-Object "$out\probe.txt" }
    if ((Get-Content "$out\probe.txt" | Select-String "mismatch=0") -eq $null) { throw "framing check failed for $Day (see $out\probe.txt)" }
  }
  if ((Step "filter") -and -not (Test-Path $sub)) {
    Run "filter" { & java -cp $cp sg.phuc.lob.replay.Filter $bin $sub $Symbols | Tee-Object "$out\filter.txt" }
  }
  if (Step "replay") {
    Run "replay" { & java @jvm -cp $cp sg.phuc.lob.replay.Replay --file $bin --final --validate --dump-priority 500 --meatpy $MeatPy --ladder $Ladder --out $out | Tee-Object "$out\replay.txt" }
  }
  if (Step "probes") {
    Run "pick probes" { & $py research\pick_probes.py --derived $out --out "$out\probe_symbols.txt" | Select-Object -Last 1 }
    New-Item -ItemType Directory -Force "$out\probes" | Out-Null
    Run "episodes" { & java @jvm -cp $cp sg.phuc.lob.replay.Replay --file $bin --final --no-derived --probe-symbols "@$out\probe_symbols.txt" --out "$out\probes" | Tee-Object "$out\probes.txt" }
    Move-Item -Force "$out\probes\episodes.ndjson" "$out\episodes.ndjson"
  }
  if (Step "classify") {
    Run "classify" { & $py research\classify_priority.py --derived $out --out "research\out\priority_$Day.md" | Select-Object -First 3 }
  }
  if (Step "convert") {
    Run "convert" { & $py research\convert.py --derived $out --out data\parquet --date $Day }
  }
  if (Step "report") {
    Run "report" { & $py research\report.py --derived $out --parquet data\parquet --date $Day }
  }
  Write-Output "== $Day complete: research\results\numbers_$Day.md"
} finally { Pop-Location }
