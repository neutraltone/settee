# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Settee: a GPL-3.0 IPTV player for Android TV, Fire TV, phones and tablets — one APK, single Gradle
module (`app/`), Kotlin + Jetpack Compose + Room + Media3/ExoPlayer. Sources are Xtream Codes logins,
M3U/M3U8 playlists, Stalker/Ministra portals and Stremio add-ons. **D-pad first**: every UI change must
be fully navigable with a remote (focus order, focus visuals), touch is secondary.

This is **Settee** (`neutraltone/settee`), a personal app based on the abandoned OpenTV project (see the
spec, neutraltone/settee#1). The primary target device is a Fire TV Stick. The application ID is
`uk.neutraltone.settee`, but the Kotlin package stays `app.opentv` and the GPL headers still credit the
OpenTV contributors — don't rename either.

## Commands

Requires JDK 17 or newer (CI uses Temurin 17; Gradle 9 also runs on 25) and Android SDK API 37 —
Android Studio provides both. AGP 9 compiles Kotlin itself, so there is no `kotlin.android` plugin;
AGP, the Gradle wrapper, Kotlin and KSP move together (Dependabot's `toolchain` group).
minSdk is 24 (Android 7.0), which WorkManager 2.12 requires. Fire OS 6+ (API 25) is unaffected; Fire
OS 5 (API 22) was already unsupported.

```bash
./gradlew test                              # all unit tests (what CI runs)
./gradlew testDebugUnitTest --tests 'app.opentv.M3uParserTest'                # one class
./gradlew testDebugUnitTest --tests 'app.opentv.M3uParserTest.some test name' # one test (backticked names)
./gradlew assembleDebug                     # APK → app/build/outputs/apk/debug/
./gradlew installDebug                      # install on the connected emulator/device
adb connect <firestick-ip>:5555             # Fire TV over wifi (enable ADB debugging on the stick)
adb logcat -s OpenTV:* EpgRepository:* SyncWorker:*
python3 tools/stalker_mock.py [port]        # mock Stalker portal with free test streams (live only)
./gradlew ktlintFormat                      # auto-fix formatting (ktlintCheck is what CI runs)
./gradlew detekt lintDebug                  # static analysis, as CI runs it
git config core.hooksPath .githooks         # once per clone: enables the commit-msg hook
tools/check-message-test.sh                 # tests for the commit/PR-title convention
```

Tests live in `app/src/test` (JUnit4, Truth, Robolectric, Room testing, coroutines-test); there are no
instrumented tests.

### PR checks

`.github/workflows/build.yml` runs one job per required check: **Test and assemble** (unit tests, debug
APK, and a fail if the build changed `app/schemas/`), **Android Lint**, **ktlint**, **detekt** and
**gitleaks**. `pr-hygiene.yml` runs `tools/check-message-test.sh` and checks the PR title with
`tools/check-message.sh`. Lint and detekt fail only on findings missing from `app/lint-baseline.xml` /
`app/detekt-baseline.xml`: fix a new finding, don't regenerate a baseline to hide it. `.gitleaks.toml`
adds rules for Xtream credentials in URLs and MAG MACs; use placeholders such as `u`/`p`, `example.com` or `00:1A:79:00:00:01` in tests.
`RoomMigrationTest` walks every exported schema to the latest with `OpenTvDatabase.MIGRATIONS`.

The debug build has `applicationIdSuffix = ".debug"` (`uk.neutraltone.settee.debug`), so it installs
alongside a release build without touching its data.

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

- **`data/repo/ViewerLibrary` owns all per-Profile watch state**: it records progress, answers each
  title's `WatchStatus` (Watched, In Progress, Unwatched), resume points and the In Progress list, and
  merges synced positions newest-wins. Screens and sync go through it, never `PlaybackPositionDao`.
  Watched = past 93%, everywhere (status, resume, Continue Watching). Live TV is never resumed.
- Resume points are `PlaybackPosition`, keyed `(profileId, mediaKey)` where `mediaKey` is `movie:<localId>`
  or `ep:<localId>` (local Room row ids).
- `favourite` is a column on `channels`, `movies` and `series`. Cross-device sync identifies favourites by
  `streamUrl` (channels, movies) or provider `seriesId` (series), and positions newest-wins
  (`sync/SyncData.kt`, `SyncEngine.kt`) — keep new watch/favourite state syncable the same way.
- `VodViewModel.continueWatching` builds the Continue Watching shelf from `ViewerLibrary.inProgress()`.

### Database migrations

Room schema version lives in `data/db/OpenTvDatabase.kt`; schemas are exported to `app/schemas/`. The
schema restarted at version 1 for Settee, with none of upstream's migrations.

**Until 1.0, a schema change may wipe data:** bump the version, commit the newly exported schema, and
let `fallbackToDestructiveMigration()` rebuild the database. The 0.x releases are installed only by the
developer, so losing favourites on an upgrade is acceptable while the schema is still settling. **From
1.0 on, real migrations are required:** every schema change bumps the version and adds a hand-written
`Migration` that matches Room's generated DDL so favourites survive, and the destructive fallback (with the
pre-restart test in `OpenTvDatabaseTest`) is deleted.

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
- **Commits and PR titles** follow `type(#issue): description`, e.g. `feat(#12): add series rules`. The
  issue is required for `feat`, `fix`, `refactor` and `perf`, optional for `docs`, `chore`, `ci`, `test`
  and `build`; `!` before the colon marks a breaking change; release-please's `chore(main): release
  x.y.z` also passes. `tools/check-message.sh` is the rule, shared by the `.githooks/commit-msg` hook
  (enable with `git config core.hooksPath .githooks`) and the PR title check. The hook lets git's own
  `Merge …`, `Revert "…"` and `fixup!`/`squash!`/`amend!` messages through; PR titles get no such
  exemption.
- **`feat`, `fix` and `perf` titles describe what a viewer notices** ("episodes show how far through
  them you are", not "add ViewerLibrary"), because release-please turns them into `CHANGELOG.md` and
  the app shows that, filtered by `update/ReleaseNotes`, as its What's new and update-available notes.
  Other types never reach a viewer. Before 1.0, a change that wipes the database is marked `!` and its
  title says what the viewer has to redo (e.g. "add your providers again").
- **Releases:** one branch and PR per ticket, squash-merged so the PR title becomes the commit on
  `main`. release-please (`.github/workflows/release-please.yml`) keeps a `chore(main): release x.y.z`
  PR open that bumps `appVersionName` in `app/build.gradle.kts` (`versionCode` is derived from it) and
  adds the `CHANGELOG.md` entry from `feat`/`fix`/`perf` commits. Merge that PR to ship: it tags
  `vx.y.z`, creates the GitHub release and calls `release.yml` to attach the APKs. Don't bump versions,
  edit released `CHANGELOG.md` entries or push tags by hand. Local release builds fall back to the debug key
  without keystore secrets, but `release.yml` fails rather than attach an APK that isn't signed with
  the release key (`tools/check-apk-signer.sh`) — see `docs/RELEASING.md`.
- The in-app updater checks `UpdateChecker.REPO_SLUG` (`neutraltone/settee`) and installs the release's
  versioned `settee-<tag>.apk`; the release workflow also publishes a fixed-name `settee.apk` for the
  Downloader short code. Keep both names in step with `GitHubRelease.apkAsset`.
- Never put provider URLs, usernames, passwords or MACs in logs, tests or fixtures.
- Before opening a PR, ask the user whether to run `/code-review` against `main` first.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `neutraltone/settee`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical labels, unchanged: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` plus `docs/adr/` at the repo root, created lazily. See `docs/agents/domain.md`.
