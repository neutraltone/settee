# Releasing

Releases are cut by merging the release PR. GitHub Actions does the rest.

1. Each ticket gets its own branch and PR with a conventional title (`feat(#12): add series rules` —
   see `CLAUDE.md`), squash-merged into `main` so the title becomes the commit.
2. On every push to `main`, `.github/workflows/release-please.yml` updates a release PR titled
   `chore(main): release x.y.z`. It bumps `appVersionName` in `app/build.gradle.kts` (the line marked
   `x-release-please-version`) and `.release-please-manifest.json`, and adds a `CHANGELOG.md` entry
   from the `feat`, `fix` and `perf` commits since the last release. Other types don't appear.
3. Merge the release PR when you want to ship. release-please tags `vx.y.z` and creates the GitHub
   release, then calls `.github/workflows/release.yml`, which runs the tests, assembles the release
   APK and attaches `settee-vx.y.z.apk` and `settee.apk`. For the few minutes that takes, the
   release exists without an APK: the in-app updater ignores it until the APK lands, but the
   `releases/latest/download/settee.apk` link 404s.

Don't bump the version, write the changelog entry or push a tag by hand. To rebuild an existing
release's APKs, run the Release workflow from the Actions tab with its tag.

Before 1.0, a breaking change (`feat(#12)!: …`) bumps the minor version and a `feat` bumps the minor
version too; a `fix` or `perf` bumps the patch.

### First release

0.1.0 is the baseline and was never released. `bootstrap-sha` in `release-please-config.json` is the
`main` commit release-please started from, so the first release PR is 0.2.0 (or 0.1.1 if it only
holds fixes), listing work merged after that commit. Work before it is the hand-written 0.1.0 entry.
Once a release exists release-please ignores `bootstrap-sha`.

### One-time repository settings

- **Settings → Actions → General → Workflow permissions:** tick "Allow GitHub Actions to create and
  approve pull requests", or release-please can't open its PR.
- **Settings → General → Pull Requests:** allow squash merging, with the default commit message set
  to "Pull request title" (otherwise a single-commit PR's commit title is used instead of the
  checked PR title).
- **Checks on the release PR (optional):** a PR opened with the default `GITHUB_TOKEN` doesn't trigger
  other workflows, so the release PR shows no checks. That's fine while `main` has no required
  checks. If you add them, create a fine-grained personal access token for this repo with Contents
  and Pull requests read/write and store it as the `RELEASE_PLEASE_TOKEN` Actions secret; the
  workflow uses it when present.

## Signing

**Right now, release builds are signed with the Android debug key.** This is deliberate, and
it is temporary.

An *unsigned* APK cannot be installed on Android at all — the installer rejects it before the
user sees anything, with an error that explains nothing. That would mean no one can test the
app until signing infrastructure exists. So `app/build.gradle.kts` falls back to the debug key
when no keystore is configured, and the result installs fine.

The cost, stated plainly: **when the project moves to a real keystore, Android will refuse to
upgrade over a debug-signed install.** Everyone testing today will have to uninstall and
reinstall, losing their settings. That is fine for a handful of early testers and completely
unacceptable once there are hundreds. **Set up real signing before announcing the project
publicly.**

### Setting up real signing

1. Generate a keystore:

   ```bash
   keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias opentv
   ```

2. Add these repository secrets under **Settings → Secrets and variables → Actions**:
   `KEYSTORE_BASE64` (the output of `base64 -i upload.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD`.

3. That is all — `release.yml` and `build.gradle.kts` already pick them up. The release notes
   will report `signed: yes` instead of `debug-key`.

**The keystore must belong to the project, not to one person.** A single maintainer holding
the only copy is the same single point of failure this project exists to avoid — if they go
quiet, nobody can ship an upgrade, and every user is stranded on whatever version they have.
Share it among at least two maintainers, and back it up somewhere that is not one laptop.

Losing the keystore is unrecoverable. There is no reset.

## Version numbers

`appVersionName` in `app/build.gradle.kts` is the only number a release changes, and release-please
changes it. `versionCode` is worked out from it by `versionCodeOf`
(`buildSrc/src/main/kotlin/AppVersion.kt`) as major×10000 + minor×100 + patch (0.1.0 → 100,
1.2.3 → 10203), so minor and patch must stay below 100. Android uses `versionCode`, not
`versionName`, to decide what counts as an upgrade, which is why the two are never set by hand
separately.
