# Contributing to Settee

Everyone is welcome here, including — especially — people who have never contributed to an
open-source project before. If you are fixing the bug that has been annoying you, you are
exactly the person this project is for.

## The one hard rule

**Do not copy code from any other IPTV app, and do not decompile one.**

Settee, like OpenTV before it, is a clean-room implementation. If any decompiled or copied code
enters this repository, the project loses the legal footing that makes it safe for anyone to use,
fork and distribute — which is the whole reason it exists. Pull requests that appear to contain code lifted from a
closed-source app will be closed.

You may absolutely:

- use another app as a *user* and describe how it behaves
- read public API documentation, XMLTV specs, or the Xtream Codes request format
- port ideas, layouts and interaction patterns

You may not:

- run an APK through a decompiler and paste the output
- copy source you obtained from a private repository or leak

## Reporting a bug

The most valuable bug report in this project names a **device**. Most failures here are
device-specific — a particular box, a particular Android version, a particular provider. Please
include:

- Device and Android version (e.g. "Ugoos AM9 Pro, Android 14"; "Fire TV Stick 4K, Fire OS 7")
- Settee version (Settings → About)
- Provider type: Xtream login, M3U playlist, Stalker portal or Stremio add-on
- What happened, and what you expected
- Logs if you can get them: `adb logcat -s OpenTV:* EpgRepository:* SyncWorker:*` (the log tags
  still carry the old name)

**Never paste your provider's URL, username or password into an issue.** Redact them. They are
credentials for a service you pay for, and issues are public forever.

## Setting up

```bash
git clone https://github.com/neutraltone/settee.git
cd settee
git config core.hooksPath .githooks   # checks each commit message
./gradlew assembleDebug               # APK in app/build/outputs/apk/debug/
./gradlew installDebug                # onto a connected device or emulator
```

JDK 17+ and Android SDK API 37. Android Studio handles both. The debug build installs as
`uk.neutraltone.settee.debug`, beside a release build, without touching its data. A release build
made locally (`./gradlew assembleRelease`, without the release keystore) is debug-signed, so it
installs as its own app too: `uk.neutraltone.settee.pr`, called Settee Release. A Fire TV Stick
connects over wifi with `adb connect <stick-ip>:5555` once ADB debugging is on.

To run tests, run the class you're working on through `tools/check/check.py`, which prints why a
test failed and stops a test run that has frozen:

```bash
tools/check/check.py test M3uParserTest   # one or more test classes
tools/check/check.py quick                # ktlintFormat and detekt, after each change
```

`./gradlew test` runs every test, as CI does. It takes a while, and CI runs it on every PR anyway.

Testing against a real provider is the hard part of working on this project. If you do not have
one, the parsers, the database layer and the UI can all be worked on with unit tests and
synthetic data — and those are where most of the interesting bugs are. For live channels,
`python3 tools/stalker_mock.py` runs a mock Stalker portal serving free test streams.

## How the work is done

Most of the code is written with Claude Code and a few agent skills: Matt Pocock's for the
engineering process, Impeccable for design and Emil Kowalski's for motion. `docs/WORKFLOW.md`
lists them, how to install them, and the process from issue to merged PR. Using them is optional;
the same steps work by hand.

## Pull requests

- One change per PR. A PR that fixes a bug *and* restyles three screens is hard to review and
  hard to revert.
- Add a test if the change is testable. Parsers, URL handling, time arithmetic and database
  merge logic all are.
- Run `tools/check/check.py quick` and your test class before pushing; CI runs the rest (see below).
- Title the PR `type(#issue): description`, e.g. `fix(#42): keep favourites across a refresh`.
  `feat`, `fix`, `refactor` and `perf` need the issue number; `docs`, `chore`, `ci`, `test` and
  `build` don't. `tools/check-message.sh "<title>"` checks one.
- For `feat`, `fix` and `perf`, word the title as what a viewer notices ("episodes show how far
  through them you are"), because it becomes the changelog and the app's What's new.
- Explain the *why* in the description. The code says what it does.
- User-facing text goes in `res/values/strings.xml`, and every `.kt` file starts with the GPL header
  the others carry.

Comments should explain reasoning, not restate the line below them. Look at
`EpgRepository` or `PlayerController` for the house style: the interesting comments are the ones
explaining why an obvious implementation was rejected.

## Checks on every pull request

Each of these must pass before a PR can merge. All but the title check can run locally, though
the Gradle ones are slow; it's fine to push and let CI run them:

| Check | What it catches | Run it locally |
|---|---|---|
| Test and assemble | Failing unit tests, a broken build, Room schema changes that weren't committed | `./gradlew test assembleDebug` |
| ktlint | Formatting | `./gradlew ktlintCheck` (`ktlintFormat` fixes most of it) |
| detekt | Kotlin and Compose code smells | `./gradlew detekt` |
| Android Lint | Android API misuse, resource problems | `./gradlew lintDebug` |
| gitleaks | Provider URLs with credentials, MAC addresses, API keys | [gitleaks](https://github.com/gitleaks/gitleaks): `gitleaks git . --config .gitleaks.toml` |
| PR title | The title convention above | `tools/check-message.sh "<title>"` |
| Screenshot tools | The Python tools' own tests | `python3 -m unittest discover -s tools/<dir>` for each of `screenshots`, `perf`, `check`, `signing` and `launcher-art` |

The **Screenshots** workflow also runs on each PR. It never fails one: it adds a before | after table
of every screen your change altered to the PR description, so a reviewer can see a UI change without
installing the build.

detekt and Lint compare against baselines of findings that predate them, so they only flag what
your change adds. Fix those rather than adding them to a baseline.

**Changing the database?** Bump the version in `OpenTvDatabase`, commit the new schema JSON that the
build writes to `app/schemas/`, and add a hand-written `Migration` to `OpenTvDatabase.MIGRATIONS`,
so viewers keep their Favourites. `RoomMigrationTest` fails if one is missing.

## Screenshots

If your change alters a screen, add a screenshot to that screen's UI tests: one
`captureScreen("<screen>_<state>")` line after the assertions for each state, plus one with the
screen's initial focus highlight showing. `EpisodeRowTest` is the example, and
`app/src/test/java/app/opentv/Screenshots.kt` explains the setup. Screenshots render on the JVM
under Robolectric at the Fire TV Stick's 1080p, with placeholder artwork instead of network images.

To render them locally:

```bash
./gradlew recordRoborazziDebug                                  # every screen
./gradlew recordRoborazziDebug --tests 'app.opentv.EpisodeRowTest'  # one test class
open app/build/outputs/roborazzi/                               # the PNGs
```

A plain `./gradlew test` runs the same tests without writing images. Nothing under
`app/build/outputs/roborazzi/` is committed: the Screenshots workflow renders both sides of a PR itself.

## Good first issues

- Device-specific playback bugs — if you own the device, you are uniquely able to fix it
- Test coverage for `EpgRepository.sync` failure paths (see the end of `docs/ARCHITECTURE.md`)
- Accessibility: content descriptions, focus order, caption styling
- Translations: about half the app's text is still missing in every language (#492)
- Anything on the [project board](https://github.com/users/neutraltone/projects/2) marked Ready

## Things that need discussion before you build them

Open an issue first for these. They are not refusals — they are decisions with consequences
that outlive the PR:

- **Anything requiring a server we have to run.** See `docs/ARCHITECTURE.md`. The short version:
  a hosting bill that grows with users is how this category of app dies.
- **Anything that changes the look.** `docs/LOOK.md` decides how Settee looks; a change to it is
  agreed first.
- **Replacing the hand-rolled Xtream JSON parsing with typed models.** There is a specific
  reason it is written that way; read the class docs before proposing it.

## Code of conduct

Be decent. Assume the person you are replying to is doing their best with less context than
you have. Frustration with a *piece of software* is fine and often justified; taking it out on
a person is not.

Maintainers are volunteers. Nobody here owes anyone a fix by a deadline. Equally, if a
maintainer goes quiet, that is what forking is for — the licence guarantees you that option,
permanently, and using it is not a betrayal. It is the design working.
