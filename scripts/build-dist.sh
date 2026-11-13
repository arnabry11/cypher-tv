#!/bin/sh
# Builds everything the TV downloads into $OUT (default: dist): channels.json, epg.json.gz, match-report.md.
# Used by the daily workflow and runnable locally. SKIP_EPG=1 skips the guide.
set -eu
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
# One grab pass, no retries: a channel that throttles us simply has no guide today (not worth CI minutes).
rm -f build/guide.xml
if (cd "$EPG" && npm run grab --silent -- --channels="$ROOT/build/epg-channels.xml" --days=2 --maxConnections=2 --delay=250 --output="$ROOT/build/guide.xml") \
   && [ -s build/guide.xml ]; then
  ./gradlew -q :tools:run --args="build-epg --xmltv build/guide.xml --channels $OUT/channels.json --out $OUT/epg.json.gz"
else
  echo "Guide grab failed; publishing channels without a new guide" >&2
fi
