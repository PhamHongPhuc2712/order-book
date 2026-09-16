# Download one NASDAQ ITCH sample file with resume, verify against the published .md5sum if there is one.
# Usage: powershell -ExecutionPolicy Bypass -File ops/download.ps1 -Name 01302020.NASDAQ_ITCH50.gz
# NASDAQ publishes no .md5sum for some files (404): then the framing check (Probe: mismatch=0) is the integrity check.
param([Parameter(Mandatory = $true)][string]$Name)
$base = "https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/"
$dir = Join-Path $PSScriptRoot "..\data\itch"
New-Item -ItemType Directory -Force $dir | Out-Null
$target = Join-Path $dir $Name
$attempt = 0
do {
  $attempt++
  & curl.exe -L -C - --retry 20 --retry-delay 10 --retry-all-errors -o $target "$base$Name"
  $code = $LASTEXITCODE
  if ($code -ne 0) { Write-Output "curl exit $code on attempt $attempt; resuming"; Start-Sleep 15 }
} while ($code -ne 0 -and $attempt -lt 30)
if ($code -ne 0) { throw "download failed for $Name after $attempt attempts" }
$want = $null
try { $want = (Invoke-WebRequest -UseBasicParsing "$base$Name.md5sum" -ErrorAction Stop).Content.Trim().Split(" ")[0].ToLower() } catch { Write-Output "no md5sum published for $Name" }
$size = (Get-Item $target).Length
if ($want) {
  $have = (Get-FileHash $target -Algorithm MD5).Hash.ToLower()
  if ($have -ne $want) { throw "MD5 mismatch for $Name : have $have want $want" }
  Write-Output "md5 ok $Name ($size bytes)"
} else {
  Write-Output "downloaded $Name ($size bytes); verify framing with Probe"
}
