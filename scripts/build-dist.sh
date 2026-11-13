#!/bin/sh
# Builds everything the TV downloads into $OUT (default: dist): channels.json, epg.json.gz, match-report.md.
# Used by the daily workflow and runnable locally. SKIP_EPG=1 skips the guide.
set -eu
START=$(date +%s)
# Whole-run time budget. The guide passes stop when it is used up and we publish what was collected so far.
BUDGET="${BUDGET_SECONDS:-1200}"
left() { echo $(( START + BUDGET - $(date +%s) )); }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${OUT:-dist}"
IN=build/input
EPG=build/epg
rm -rf "$OUT"
mkdir -p "$OUT" build

./scripts/fetch-playlists.sh "$IN"

./gradlew -q :tools:run --args="build-channels --fta data/fta.json --streams $IN/streams_in.m3u --index $IN/index_in.m3u --overrides data/overrides.json --numbers data/numbers.json --out $OUT"

if [ "${SKIP_EPG:-0}" = "1" ]; then
  echo "Skipping guide (SKIP_EPG=1)"
  exit 0
fi

# Guide: the iptv-org/epg grabber is a third-party Node program; our code only selects channels and converts its XMLTV.
[ -d "$EPG" ] || git clone --depth 1 https://github.com/iptv-org/epg "$EPG"
(cd "$EPG" && npm ci --no-audit --no-fund --silent)
./gradlew -q :tools:run --args="epg-channels --sites-dir $EPG/sites --sites tataplay.com,dishtv.in,airtelxstream.in,zee5.com --channels $OUT/channels.json --out build/epg-channels.xml"
# The sites throttle bursts (HTTP 429/403), so grab fast once, then retry only the channels still without a guide, slowly.
grab() { # channels.xml output connections delay-ms
  secs=$(left)
  if [ "$secs" -lt 60 ]; then echo "Time budget used up; no more guide passes"; return 1; fi
  # A pass cut off by the budget writes nothing (the grabber saves at the end); earlier passes are kept.
  (cd "$EPG" && timeout "$secs" npm run grab --silent -- --channels="$ROOT/$1" --days=2 --maxConnections="$3" --delay="$4" --output="$ROOT/$2") || true
}
rm -f build/guide-*.xml
cp build/epg-channels.xml build/pending.xml
guides=""
for pass in 1 2 3 4; do
  if [ "$pass" = 1 ]; then conns=2; delay=250; else conns=1; delay=1000; fi
  echo "Guide pass $pass: $(grep -c '<channel ' build/pending.xml) channels"
  grab build/pending.xml "build/guide-$pass.xml" "$conns" "$delay" || break
  [ -s "build/guide-$pass.xml" ] && guides="${guides:+$guides,}build/guide-$pass.xml"
  [ -n "$guides" ] || continue
  ./gradlew -q :tools:run --args="epg-missing --channels-xml build/epg-channels.xml --xmltv $guides --out build/pending.xml"
  [ "$(grep -c '<channel ' build/pending.xml || true)" = "0" ] && break
  [ "$pass" = 4 ] || [ "$(left)" -lt 120 ] || sleep 20
done

if [ -n "$guides" ]; then
  ./gradlew -q :tools:run --args="build-epg --xmltv $guides --channels $OUT/channels.json --out $OUT/epg.json.gz"
else
  echo "Guide grab failed; publishing channels without a new guide" >&2
fi
