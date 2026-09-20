#!/usr/bin/env bash
# Download one NASDAQ ITCH sample file with resume; verify against the published .md5sum if there is one.
# Linux/macOS port of ops/download.ps1. Usage: bash ops/download.sh 01302020.NASDAQ_ITCH50.gz
# NASDAQ publishes no .md5sum for some files (404): then the framing check (Probe: mismatch=0) is the integrity
# check, which is what ops/make.sh runs straight after decompressing. Expect about 1.5 MB/s — 40 minutes for the
# smallest of the three days, over an hour for the largest — so a reset mid-download is normal and is resumed.
set -u
NAME=${1:?usage: ops/download.sh <file.gz>}
BASE="https://emi.nasdaq.com/ITCH/Nasdaq%20ITCH/"
cd "$(dirname "$0")/.." || exit 1
mkdir -p data/itch
TARGET="data/itch/$NAME"

attempt=0; code=1
while [ $code -ne 0 ] && [ $attempt -lt 30 ]; do
  attempt=$((attempt + 1))
  curl -L -C - --retry 20 --retry-delay 10 --retry-all-errors -o "$TARGET" "$BASE$NAME"
  code=$?
  [ $code -ne 0 ] && { echo "curl exit $code on attempt $attempt; resuming"; sleep 15; }
done
[ $code -eq 0 ] || { echo "download failed for $NAME after $attempt attempts" >&2; exit 1; }

size=$(wc -c < "$TARGET")
want=$(curl -fsSL "$BASE$NAME.md5sum" 2>/dev/null | tr -d '\r' | awk '{print tolower($1)}')
if [ -n "${want:-}" ]; then
  have=$(md5sum "$TARGET" | awk '{print $1}')
  [ "$have" = "$want" ] || { echo "MD5 mismatch for $NAME: have $have want $want" >&2; exit 1; }
  echo "md5 ok $NAME ($size bytes)"
else
  echo "no md5sum published for $NAME"
  echo "downloaded $NAME ($size bytes); verify framing with Probe (ops/make.sh does this)"
fi
