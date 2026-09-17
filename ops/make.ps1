# One-day pipeline (Phase 4 Task 2): decompress -> Probe -> Filter -> Replay (derived, validation, priority dump, MeatPy
# export, ladder) -> pick probes -> Replay (episodes) -> classify priorities -> Parquet -> report.
# Usage: powershell -ExecutionPolicy Bypass -File ops/make.ps1 -Day 01302020 -Gz 01302020.NASDAQ_ITCH50.gz [-Steps all]
#        -Steps is a comma list to run only some of: gunzip,probe,filter,replay,probes,classify,convert,report
# Every JVM runs at 4 GB G1 (D27). Logs go to data/derived/<Day>/*.txt (stderr to *.txt.err); numbers to
# research/results/numbers_<Day>.md. Every step is run through Start-Process so its exit code is checked and its stderr
# is kept: a step that dies (a traceback, or an OS kill with no traceback at all) fails the whole chain instead of
# letting it print "complete" with no output written.
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
  $java = Join-Path $env:JAVA_HOME "bin\java.exe"
  $py = Join-Path $root "research\.venv\Scripts\python.exe"
  $bin = "data\itch\$Day.bin"; $sub = "data\itch\$Day.sub20.bin"; $out = "data\derived\$Day"
  New-Item -ItemType Directory -Force $out | Out-Null
  if (-not (Test-Path "cp.txt")) { & mvn -q dependency:build-classpath -pl replay "-Dmdep.outputFile=cp.txt" }
  if (-not (Test-Path "replay\target\classes\sg\phuc\lob\replay\Replay.class")) { & mvn -q -B -pl replay -am package -DskipTests }
  $cp = "replay\target\classes;core\target\classes;" + (Get-Content cp.txt -Raw).Trim()
  $jvm = @("-Xms4g", "-Xmx4g", "-XX:+UseG1GC")
  function Step($name) { return ($Steps -eq "all") -or (($Steps -split ",") -contains $name) }

  # Run one external command, keeping stdout in $Log and stderr in "$Log.err"; throw on a non-zero exit code.
  # $Show: all | last | first3 | none — how much of stdout is echoed into the chain log.
  function Native($Label, $Exe, $ArgList, $Log, $Show = "all") {
    $t = Get-Date
    Write-Output "== $Label $($t.ToString('HH:mm:ss'))"
    $log = if ($Log) { $Log } else { Join-Path $env:TEMP "make_$Label.txt".Replace(" ", "_") }
    $err = "$log.err"
    New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
    $p = Start-Process -FilePath $Exe -ArgumentList $ArgList -NoNewWindow -Wait -PassThru `
      -RedirectStandardOutput $log -RedirectStandardError $err
    $lines = if (Test-Path $log) { Get-Content $log } else { @() }
    switch ($Show) {
      "last" { $lines | Select-Object -Last 1 | Write-Output }
      "first3" { $lines | Select-Object -First 3 | Write-Output }
      "none" { }
      default { $lines | Write-Output }
    }
    $errLines = if (Test-Path $err) { Get-Content $err | Where-Object { $_ -ne "" } } else { @() }
    if ($errLines) { Write-Output "   [stderr] $($errLines -join "`n   [stderr] ")" }
    if ($p.ExitCode -ne 0) { throw "$Label failed for ${Day}: exit code $($p.ExitCode) (stdout $log, stderr $err)" }
    Write-Output "   $Label done in $([int]((Get-Date) - $t).TotalSeconds) s"
  }

  if ((Step "gunzip") -and -not (Test-Path $bin)) {
    Native "gunzip" "bash" @("-c", "gzip -dk -c data/itch/$Gz > data/itch/$Day.bin") "$out\gunzip.txt"
  }
  if (Step "probe") {
    Native "probe" $java @("-cp", $cp, "sg.phuc.lob.replay.Probe", $bin) "$out\probe.txt"
    if ((Get-Content "$out\probe.txt" | Select-String "mismatch=0") -eq $null) { throw "framing check failed for $Day (see $out\probe.txt)" }
  }
  if ((Step "filter") -and -not (Test-Path $sub)) {
    Native "filter" $java @("-cp", $cp, "sg.phuc.lob.replay.Filter", $bin, $sub, $Symbols) "$out\filter.txt"
  }
  if (Step "replay") {
    Native "replay" $java ($jvm + @("-cp", $cp, "sg.phuc.lob.replay.Replay", "--file", $bin, "--final", "--validate",
        "--dump-priority", "500", "--meatpy", $MeatPy, "--ladder", $Ladder, "--out", $out)) "$out\replay.txt"
  }
  if (Step "probes") {
    Native "pick probes" $py @("research\pick_probes.py", "--derived", $out, "--out", "$out\probe_symbols.txt", "--per-tier", $PerTier) "$out\pick_probes.txt" "last"
    New-Item -ItemType Directory -Force "$out\probes" | Out-Null
    Native "episodes" $java ($jvm + @("-cp", $cp, "sg.phuc.lob.replay.Replay", "--file", $bin, "--final", "--no-derived",
        "--probe-symbols", "@$out\probe_symbols.txt", "--out", "$out\probes")) "$out\probes.txt"
    Move-Item -Force "$out\probes\episodes.ndjson" "$out\episodes.ndjson"
  }
  if (Step "classify") {
    Native "classify" $py @("research\classify_priority.py", "--derived", $out, "--out", "research\out\priority_$Day.md") "$out\classify.txt" "first3"
  }
  if (Step "convert") {
    Native "convert" $py @("research\convert.py", "--derived", $out, "--out", "data\parquet", "--date", $Day) "$out\convert.txt"
  }
  if (Step "report") {
    Native "report" $py @("research\report.py", "--derived", $out, "--parquet", "data\parquet", "--date", $Day) "$out\report.txt"
  }
  if (Step "report") {
    if (-not (Test-Path "research\results\numbers_$Day.md")) { throw "report step left no research\results\numbers_$Day.md for $Day" }
    Write-Output "== $Day complete: research\results\numbers_$Day.md"
  }
  else { Write-Output "== $Day steps done: $Steps" }
} finally { Pop-Location }
