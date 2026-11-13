#!/bin/sh
# Downloads the iptv-org India playlists into the directory given as $1 (default: build/input).
# streams/in.m3u is the source of every channel and stream; countries/in.m3u only supplies logos and categories.
set -eu
dir="${1:-build/input}"
mkdir -p "$dir"
curl -fsSL https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u -o "$dir/streams_in.m3u"
curl -fsSL https://iptv-org.github.io/iptv/countries/in.m3u -o "$dir/index_in.m3u"
# What language each channel broadcasts in (the licence list only says which languages it may use).
curl -fsSL https://iptv-org.github.io/api/feeds.json -o "$dir/feeds.json"
echo "Saved $(grep -c '^#EXTINF' "$dir/streams_in.m3u") streams to $dir"
