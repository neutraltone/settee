# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

OpenTV: a GPL-3.0 IPTV player for Android TV, Fire TV, phones and tablets — one APK, single Gradle
module (`app/`), Kotlin + Jetpack Compose + Room + Media3/ExoPlayer. Sources are Xtream Codes logins,
M3U/M3U8 playlists, Stalker/Ministra portals and Stremio add-ons. **D-pad first**: every UI change must
be fully navigable with a remote (focus order, focus visuals), touch is secondary.

This is **Settee** (`neutraltone/settee`), a personal app based on the abandoned `opentvproject/opentv` and
being rebranded (see the spec, neutraltone/settee#1). The primary target device is a Fire TV Stick.

## Commands

Requires JDK 17 (CI uses Temurin 17; newer JDKs such as 25 may break the Android Gradle Plugin) and
Android SDK API 35 — Android Studio provides both.

```bash
./gradlew test                              # all unit tests (what CI runs)
./gradlew testDebugUnitTest --tests 'app.opentv.M3uParserTest'                # one class
./gradlew testDebugUnitTest --tests 'app.opentv.M3uParserTest.some test name' # one test (backticked names)
./gradlew assembleDebug                     # APK → app/build/outputs/apk/debug/
./gradlew installDebug                      # install on the connected emulator/device
adb connect <firestick-ip>:5555             # Fire TV over wifi (enable ADB debugging on the stick)
adb logcat -s OpenTV:* EpgRepository:* SyncWorker:*
python3 tools/stalker_mock.py [port]        # mock Stalker portal with free test streams (live only)
```

No linter/formatter is configured. Tests live in `app/src/test` (JUnit4, Truth, Robolectric, Room
testing, coroutines-test); there are no instrumented tests.

The debug build has `applicationIdSuffix = ".debug"` (`app.opentv.debug`), so it installs alongside a
release build without touching its data.

## Architecture

The non-obvious decisions are documented in `docs/ARCHITECTURE.md` — read it before changing EPG sync,
catalogue refresh, playback or Xtream parsing. The big picture:

- **No backend, ever.** Everything lives on-device in SQLite. Multi-device features (`sync/`) work
  peer-to-peer over LAN/Tailscale or via the user's own NAS (SMB). Don't add anything that needs a
  server the project must run.
- **`core/ServiceLocator.kt` is the whole dependency graph** (hand-rolled, no Hilt — deliberate). View
  models get dependencies via `ServiceLocator.get(app)`; new repositories/services are wired there.
- **Layers:** `data/remote` (provider clients) → `data/repo` (repositories: `CatalogRepository`,
  `EpgRepository`, `SourceRepository`, …) → `ui/OpenTvViewModels.kt` (most view models in one file) →
  Compose screens under `ui/`. `data/model/Models.kt` entities double as domain models.
- **Navigation:** `MainActivity.kt` owns the `NavHost` and the `Routes` object. `ui/MainScreen.kt` is the
  tabbed shell (`enum class Tab`: Live, Movies, Shows, Recordings), toggled by settings.
- **Settings** are in `core/AppSettings.kt` (exposed as `StateFlow`s, e.g. `activeProfileId`).

### Data rules that are easy to break

- **Catalogue refresh merges, never wipes.** `ChannelDao.replaceCatalogue` carries `favourite`, `hidden`,
  `sortIndex`, custom names and EPG overrides across by `streamId`; M3U `streamId`s are stable FNV-1a
  hashes. Anything that changes identity silently deletes users' favourites.
- **EPG never deletes before it has the replacement**: batched upserts of 500, pruning by age only after
  success. Times are UTC epoch millis everywhere; convert to local only when drawing.
- **SQLite bound-variable limit:** never write `IN (:hugeList)` / `NOT IN (:everyId)` over catalogue-sized
  lists — use sync stamps or time-window queries and group in memory.
- **Xtream JSON is parsed by hand** (`JsonElement`, nullable accessors) because panels vary field types.
  Don't convert it to `@Serializable` models. VOD failures must never cost the live channel list.
- **Episodes are fetched lazily** (`CatalogRepository.ensureEpisodes`) when a series is opened, not during
  catalogue sync — don't assume a series' episodes are in the DB.

### Watch state, favourites, profiles

- Resume points are `PlaybackPosition`, keyed `(profileId, mediaKey)` where `mediaKey` is `movie:<localId>`
  or `ep:<localId>` (local Room row ids). `isFinished` = past 95%. Live TV is never resumed. Always scope
  queries to the active profile.
- `favourite` is a column on `channels`, `movies` and `series`. Cross-device sync identifies favourites by
  `streamUrl` (channels, movies) or provider `seriesId` (series), and positions newest-wins
  (`sync/SyncData.kt`, `SyncEngine.kt`) — keep new watch/favourite state syncable the same way.
- `VodViewModel.continueWatching` builds the Continue Watching shelf from recent unfinished positions.

### Database migrations

Room schema version lives in `data/db/OpenTvDatabase.kt`; schemas are exported to `app/schemas/`. Every
schema change bumps the version and adds a hand-written `Migration` that matches Room's generated DDL
(see existing `MIGRATION_x_y` comments) so favourites survive. `fallbackToDestructiveMigration()` is only a
backstop.

### Playback

`player/PlayerController` keeps one `ExoPlayer` per screen and swaps media items (debounced 350 ms;
never release/rebuild on channel change). Retries 403/429/5xx with backoff; not 401/404/410.
`PlaybackErrors` turns failures into actionable sentences — don't hide them.

## Conventions

- **Clean-room rule:** never copy or decompile code from another IPTV app.
- Every `.kt` file starts with the GPL header block used throughout the repo.
- Comments explain *why* an obvious approach was rejected (see `EpgRepository`, `PlayerController`); don't
  restate code.
- User-facing strings go in `res/values/strings.xml`; ~30 `values-*` translations exist. Add new keys to
  the base file (translations fall back to English).
- Releases: bump `versionCode`/`versionName` in `app/build.gradle.kts`, add a `CHANGELOG.md` entry, push a
  `v*` tag (`.github/workflows/release.yml`). Release builds fall back to the debug key without keystore
  secrets — see `docs/RELEASING.md`.
- The in-app updater checks `UpdateChecker.REPO_SLUG` (`opentvproject/opentv`); for fork releases point it
  at the fork or upstream releases will be offered over fork builds.
- Never put provider URLs, usernames, passwords or MACs in logs, tests or fixtures.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `neutraltone/settee`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical labels, unchanged: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` plus `docs/adr/` at the repo root, created lazily. See `docs/agents/domain.md`.
