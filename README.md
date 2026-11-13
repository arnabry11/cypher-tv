# Cypher TV

A lightweight free-to-air TV player for low-RAM Android TV / Google TV boxes (built for a 1 GB device
where Kodi is too heavy). Turn it on and your first favourite is already playing. Zap, favourite,
reorder, see what's on. Nothing else.

Native Kotlin, Media3 ExoPlayer, plain Views. No Compose, no image loader, no DI, no Room: the release APK
is under 1 MB and uses roughly 80 MB of RAM while playing.

## Using it

| Key | In the player | In the channel list |
| --- | --- | --- |
| Up / CH+ | next channel (favourites, or all if none) | move up (Up on the first row focuses the tabs) |
| Down / CH- | previous channel | move down |
| OK | open the channel list | tune the selected row |
| Long-press OK | | favourite / unfavourite. In the Favourites tab: Move or Remove |
| Right | | today's schedule for the row (Left / Back returns) |
| Left / Right on the tab bar | | switch tab: Favourites, All, one per language |
| 0-9 | type a channel number, tunes after 2 s | same |
| Menu / Guide / Info | list / list / show banner | |

In Move mode Up/Down shifts the row and OK drops it. The zap banner shows number, name, Now and Next.
If a stream fails or stalls for 10 s the next backup stream is tried; if all fail you see
"Channel unavailable".

## How it fits together

```
iptv-org streams/in.m3u ─┐
MIB FTA list (data/fta.json) ─┼─> tools:build-channels ─> channels.json ┐
data/overrides.json ─────┘                                             ├─> `dist` branch ─> the TV
iptv-org/epg grabber ─> XMLTV ─> tools:build-epg ─> epg.json.gz ───────┘     (config.json, channels.json, epg.json.gz)
```

* `core/` pure Kotlin: models, JSON formats, name normalisation and matching, numbering, the logic the app
  uses (favourites, zapping, number entry, Now/Next, stream failover). Fully unit-tested on the JVM.
* `tools/` command-line tools run locally and in CI (`./gradlew :tools:run --args="..."`):
  `extract-fta`, `build-channels`, `epg-channels`, `build-epg`, `epg-missing`, `make-config`.
* `app/` the Android app.
* `data/` `fta.json` (the 572 MIB FTA channels), `overrides.json` (manual fixes), `numbers.json`
  (channel numbers, append-only, never reused).
* `scripts/` `build-dist.sh` builds everything the TV downloads, `publish-dist.sh` pushes it to `dist`.

The only URL baked into the APK is `dist/config.json`; channels, guide and the app update offer can all be
redirected from there. Files are read from raw.githubusercontent.com with jsDelivr as a fallback.

### Matching and fixing channels

`build-channels` writes `match-report.md` next to its output. It lists fuzzy and alias matches for review,
unmatched FTA channels with the closest playlist names, and duplicate claims. Fix things in
`data/overrides.json`:

```json
{
  "aliases": { "R. Kannada": ["Republic Kannada"] },
  "map": { "Some FTA name": "TvgId.in" },
  "exclude": ["Name or TvgId.in"],
  "extra": [ { "id": "DDNational.in", "lang": "Hindi" } ],
  "preferred": { "TvgId.in": "substring of the stream url to try first" },
  "lang": { "TvgId.in": "Telugu" }
}
```

Only channels in the FTA list (plus `extra`) are published, and only if the playlist has a stream for them.

## Building

Needs JDK 17 and the Android SDK (`local.properties` with `sdk.dir`).

```sh
./gradlew :core:test :tools:test          # unit tests
./gradlew :app:assembleDebug              # debug APK
./scripts/build-dist.sh                   # channels.json + guide into ./dist (SKIP_EPG=1 skips the guide)
```

For a first-run channel list inside the APK, copy `dist/channels.json` to `app/src/main/assets/channels.json`
(git-ignored; the release workflow does this itself).

## Releasing

* `data.yml` runs daily (01:00 IST) and on demand: rebuilds the data and publishes the `dist` branch.
* `release.yml` runs on a tag `vMAJOR.MINOR.PATCH`: builds the signed APK, attaches it to a GitHub Release
  and writes it into `config.json`, so installed apps offer it.

Repository secrets for releases: `CYPHER_KEYSTORE_BASE64`, `CYPHER_KEYSTORE_PASSWORD`, `CYPHER_KEY_ALIAS`,
`CYPHER_KEY_PASSWORD`. **Back up the keystore.** Updates must be signed with the same key; if it is lost, the
app has to be uninstalled and reinstalled.

The first time an update is accepted, Android asks once to allow installs from Cypher TV; after that it is
one OK on the system prompt.
