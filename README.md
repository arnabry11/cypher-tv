# Cypher TV

A lightweight free-to-air TV player for low-RAM Android TV / Google TV boxes (built for a 1 GB device
where Kodi is too heavy). Turn it on and your first favourite is already playing. Zap, favourite,
reorder, see what's on. Nothing else.

Native Kotlin, Media3 ExoPlayer, plain Views. No Compose, no image loader, no DI, no Room: the release APK
is under 1 MB and uses roughly 45 MB of RAM while playing (measured on a Google TV 14 set).

## Using it

| Key | While watching | In the guide |
| --- | --- | --- |
| Up / CH+ | next channel (by number) | move up (CH+ jumps 6 rows) |
| Down / CH- | previous channel | move down |
| OK | open the guide on the channel being watched | watch the highlighted channel |
| Hold OK | favourite / unfavourite the channel being watched | favourite / unfavourite. In the Favourites tab: Move or Remove |
| Left / Right | | previous / next tab: Favourites, All, one per language |
| 0-9 | type a channel number, tunes after 2 s | same |
| Menu / Guide / Info | guide / guide / show banner | close the guide |
| Back | press twice to exit | close the guide |

The guide has the tabs across the top, the channels on the left (what is on now with a progress bar, and what
is next) and, on the right, the highlighted channel's programmes from now on. The keys that apply are listed
along the bottom.

In Move mode Up/Down shifts the row and OK drops it. The zap banner shows number, name, Now and Next.
Each channel's streams are published best resolution first (playlist order within a resolution). A channel
starts on its best stream. If playback rebuffers (for more than 4 s, or 3 times in a minute) it steps down to
the next lower-quality stream and says "Switching to 720p…"; it never steps back up on its own mid-stream.
The step-down is remembered for 30 minutes so zapping back doesn't repeat the stall; after that the best
stream is tried again. A stream that errors, ends, or does not start within 10 s moves on to the next one too;
if all fail you see "This channel is not playable".

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
  (channel numbers, append-only, never reused). `numbers.json` does not exist until the first daily run
  assigns numbers, and CI commits it from then on. A local `build-dist.sh` also writes it: discard that file
  (`git checkout data/numbers.json` or delete it) instead of committing numbers you did not review.
* `server/` the daily updater that runs on the home server (see below).
* `scripts/` `build-dist.sh` builds everything the TV downloads (the guide step retries throttled channels and stops after a 20 minute budget, publishing what it has), `publish-dist.sh` pushes it to `dist`.

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

* The data (channels and guide) is rebuilt daily at 01:00 IST by the home-server updater (below) and published
  to the `dist` branch. `data.yml` does the same on GitHub, on demand only, as a fallback.
* `release.yml` runs on a tag `vMAJOR.MINOR.PATCH`: builds the signed APK, attaches it to a GitHub Release
  and writes it into `config.json`, so installed apps offer it.

Repository secrets for releases: `CYPHER_KEYSTORE_BASE64`, `CYPHER_KEYSTORE_PASSWORD`, `CYPHER_KEY_ALIAS`,
`CYPHER_KEY_PASSWORD`. **Back up the keystore.** Updates must be signed with the same key; if it is lost, the
app has to be uninstalled and reinstalled.

## Daily updater (home server)

`server/` is a small Go service in one container. Once a day it runs `scripts/build-dist.sh` (the Kotlin tools
on a trimmed Java runtime, plus the Node guide grabber) as an unprivileged user that cannot read the deploy
key, checks the result (plain files, valid JSON, no sudden drop in channels or guide coverage), then pushes new
channel numbers to `main` and the data to `dist`. It never touches `config.json`; releases own that.

On the server, one directory holds `compose.yml` (from `server/`), `.env` (from `server/.env.example`) and
`deploy_key` (a write deploy key of this repository):

```sh
docker compose up -d --build      # builds the image straight from GitHub; rerun after changing tools/ or server/
docker compose logs -f
```

Changes to `data/` and `scripts/` need no rebuild: each run uses the current `main`. The status page (last run,
counts, log, a "Run now" button) is on `127.0.0.1:8787` of the server: `ssh -L 8787:127.0.0.1:8787 homeserver`,
then open http://localhost:8787. With `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` set in `.env` it sends a
message when a run fails, publishes without a fresh guide, or works again after a failure. A failed run is
retried twice, an hour apart.

The first time an update is accepted, Android asks once to allow installs from Cypher TV; after that it is
one OK on the system prompt.
