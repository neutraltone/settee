# Releasing

Releases are cut by merging the release PR. GitHub Actions does the rest.

1. Each ticket gets its own branch and PR with a conventional title (`feat(#12): add series rules` —
   see `CLAUDE.md`), squash-merged into `main` so the title becomes the commit.
2. On every push to `main`, `.github/workflows/release-please.yml` updates a release PR titled
   `chore(main): release x.y.z`. It bumps `appVersionName` in `app/build.gradle.kts` (the line marked
   `x-release-please-version`) and `.release-please-manifest.json`, and adds a `CHANGELOG.md` entry
   from the `feat`, `fix` and `perf` commits since the last release. Other types don't appear.
3. Merge the release PR when you want to ship. release-please tags `vx.y.z` and creates the GitHub
   release, then calls `.github/workflows/release.yml`, which assembles the release APK and
   attaches `settee-vx.y.z.apk` and `settee.apk`. It doesn't run the tests a second time: it waits
   for Build's Test and assemble on the release commit and uses its verdict, running the tests
   itself only when that check failed, was cancelled or never ran. For the few minutes that takes,
   the release exists without an APK: the in-app updater ignores it until the APK lands, but the
   `releases/latest/download/settee.apk` link 404s.

Don't bump the version, write the changelog entry or push a tag by hand. To rebuild an existing
release's APKs, run the Release workflow from the Actions tab with its tag. Re-running the Release
Please run doesn't do it: on a re-run release-please finds no new release, so it skips the build and
still shows green.

Before 1.0, a breaking change (`feat(#12)!: …`) bumps the minor version and a `feat` bumps the minor
version too; a `fix` or `perf` bumps the patch.

### First release

0.1.0 is the baseline and was never released. `bootstrap-sha` in `release-please-config.json` is the
`main` commit release-please started from, so the first release PR is 0.2.0 (or 0.1.1 if it only
holds fixes), listing work merged after that commit. Work before it is the hand-written 0.1.0 entry.
Once a release exists release-please ignores `bootstrap-sha`.

release-please opens no release PR until a `feat`, `fix` or `perf` commit lands. To release
without one, give any commit that reaches `main` a `Release-As: x.y.z` footer (the last line of
the body, after a blank line); the next push opens a release PR for that version. A squash commit
gets the PR title and an empty body, which drops the footer, so pass it when merging the PR that
carries the commit: `gh pr merge <number> --squash --body "Release-As: x.y.z"`.

The Downloader short code 6405610 (registered at go.aftvnews.com, see `README.md`) points at
`releases/latest/download/settee.apk`, so it always serves the newest release and never needs
changing.

### One-time repository settings

- **Settings → Actions → General → Workflow permissions:** tick "Allow GitHub Actions to create and
  approve pull requests", or release-please can't open its PR.
- **Settings → General → Pull Requests:** allow only squash merging, with the default commit message
  set to "Pull request title" (otherwise a single-commit PR's commit title is used instead of the
  checked PR title). A merge commit makes release-please list the change twice, once for the merge
  and once for the branch's commit.
- **Settings → Branches → `main`:** require a pull request, with no approvals, and these status
  checks: `Test and assemble`, `Android Lint`, `ktlint`, `detekt`, `Screenshot tools`, `gitleaks`
  and `PR title`. Leave out `Screenshots`, which only reports. Don't require branches to be up to
  date, or parallel PRs keep re-running each other's checks. Admins can bypass all of this.
- **Checks on the release PR:** a PR opened with the default `GITHUB_TOKEN` is authored by
  `github-actions`, which GitHub treats as a first-time contributor, so its check runs wait for a
  maintainer to approve them. To avoid that, create a fine-grained personal access token for this
  repo with Contents and Pull requests read/write and store it as the `RELEASE_PLEASE_TOKEN`
  Actions secret; the workflow uses it when present, and the release PR is then opened as you.
  The Screenshots workflow needs the same token to upload images to PR descriptions, which
  Actions' own token can't do; without it, PRs get no screenshots.

## Signing

**Android will not install an APK over one signed with a different key.** Without uninstalling
first, and so losing settings and sources, an update has to carry the same key as the installed
version. So every release must be signed with the one release key.

`release.yml` enforces that. After building, it runs `tools/check-apk-signer.sh` to confirm the
APK is signed by exactly the certificate in the release keystore, and that this is the
certificate pinned in `tools/release-cert.sha256`, the one every release has been signed with.
The pin is what catches the signing secrets being replaced with another key: the keystore and the
APK would still agree with each other. If the signing secrets are missing, or the APK carries any
other signer, the workflow fails without attaching any APK. release-please has already created the
release by then, so it stays without an APK: fix the secrets, then rebuild it by running the
Release workflow with its tag. Each release's notes include `signed: yes` and the certificate's
SHA-256 digest; the workflow has already checked it against the pin, so there is no need to compare
releases by hand.

Changing the release key on purpose means changing `tools/release-cert.sha256` in the same PR, and
every installed copy will then refuse the update until it is uninstalled.

Without a keystore, `app/build.gradle.kts` signs release builds with the Android debug key, so
`./gradlew assembleRelease` still produces an installable APK locally. An *unsigned* APK would
be rejected by the installer with an error that explains nothing. Those builds are never
published.

A build like that also installs as its own app, `uk.neutraltone.settee.pr`, called Settee Release
with a RELEASE badge on its icon and banner (`app/src/local`), since Android won't update the real
release with an APK signed by another key. It keeps its own data between installs, so
`adb install -r` of a newer one updates it in place. The same `KEYSTORE_PATH` check decides the key
and the badge, so a build signed with the release key never carries it.

### Setting up the release key

This is the owner's job. The passwords and backups stay with them, not with CI or an agent.

1. Generate a keystore somewhere that is not inside the repository:

   ```bash
   keytool -genkeypair -v -keystore settee-release.jks -keyalg RSA -keysize 4096 \
     -validity 10000 -alias settee
   ```

   Use a password generated by 1Password. A modern `keytool` writes a PKCS12 keystore, which
   asks for one password and uses it for both the keystore and the key. So `KEYSTORE_PASSWORD`
   and `KEY_PASSWORD` below are the same value. Setting them differently makes the release
   build fail.

2. Back it up in 1Password **before** going any further: one item holding the
   `settee-release.jks` file as an attachment, the keystore password, the key alias (`settee`)
   and the key password (the same value, see step 1). **Losing the keystore or its password is
   unrecoverable:** every install would then have to be removed and set up again with a new key.

3. Add the four Actions secrets. `gh secret set` reads the value from stdin or a prompt, so the
   passwords never end up in shell history (`base64 -i` is the macOS form):

   ```bash
   base64 -i settee-release.jks | gh secret set KEYSTORE_BASE64 --repo neutraltone/settee
   gh secret set KEYSTORE_PASSWORD --repo neutraltone/settee
   gh secret set KEY_ALIAS --repo neutraltone/settee --body settee
   gh secret set KEY_PASSWORD --repo neutraltone/settee
   ```

   `release.yml` and `build.gradle.kts` already read them. Then delete the local
   `settee-release.jks`; 1Password holds the copy.

4. Put the certificate's SHA-256 digest in `tools/release-cert.sha256` (lowercase, no colons; run
   `tools/check-apk-signer.sh` against a release-signed APK to print it), and merge that before the
   next release PR. Then check that the release's notes say `signed: yes`.

5. Confirm that upgrades work on the Fire Stick: install that release, merge the following release PR, and
   install it over the first (through the in-app updater or `adb install -r settee.apk`). It
   must install without asking you to uninstall, and keep your sources.

## Crash report stack traces

R8 renames Settee's classes in a release, so a crash report's stack trace is unreadable without
that release's mapping. The Sentry Gradle plugin bakes an id for the mapping into the APK, which every
report from it carries, and `release.yml`'s Assemble step uploads the mapping to Sentry under that id
(#577). It needs, once:

```bash
gh secret set SENTRY_AUTH_TOKEN --repo neutraltone/settee   # an org auth token with project:releases
gh variable set SENTRY_ORG --repo neutraltone/settee --body <org slug>
gh variable set SENTRY_PROJECT --repo neutraltone/settee --body <project slug>
```

Without the token the build skips the upload and still succeeds, as it does on this Mac, forks and
PR CI; that release's reports then show renamed classes. The id is a hash of the mapping, so running
Release again for that tag once the token is set should upload a matching one (it also re-attaches
the APKs). To check it worked, look for the release's ProGuard file under the Sentry project's Debug
Files.

After the APKs are published, a second job in Release tells Sentry which commits went into the
release and, when release-please ran it, records a `production` deploy (#622; a tag rebuilt by hand
gets no deploy, so it can't pose as the newest). It uses the same name the release's crash reports
carry (`appVersionName`, the tag without its `v`). Sentry then shows a new crash's suspect commits,
can resolve an issue in the next release, and marks one that comes back later as a regression. It
works out the commits since the previous release through Sentry's GitHub integration, which needs,
once: Sentry → Settings → Integrations → GitHub, installed on `neutraltone/settee` with that
repository added. It uses the same token and variables as the mapping upload, is skipped without the
token, and runs in its own job so it never fails a release: if Sentry is down, the APKs are already
out and its commits are simply missing there. To check it worked, the release in Sentry (Releases →
the version) lists its commits.

## When an update can't open the database

Every schema from 9 (v0.5.0) on must migrate; only 1–8 and upstream OpenTV's 11 are rebuilt empty
(`WIPED_SCHEMAS` in `OpenTvDatabase`, #334). A database Settee can't open, from a migration that doesn't
match its schema or from a newer build after a downgrade, shows "Settee can't read what it saved" at
launch instead of any screen. That screen checks for a newer release straight away and offers it, and
Check again looks again, so once a fixed release is out the in-app updater still gets there. Until then
the screen tells the viewer that clearing the app's data is the way out and what it loses. The ways out:

- **Keeps the data:** reinstall over the top with Downloader code **6405610** (it installs the latest
  `settee.apk`), or `adb install -r settee.apk`. This works once a release with a fix is out.
- **Loses the data:** Fire TV Settings → Applications → Manage Installed Applications → Settee →
  Clear data (or uninstall). Providers, Favourites and progress go with it.

So before a release that changes the schema, install it on the Fire Stick over the previous signed
release and check that providers, Favourites and progress are still there. From 1.0 a mistake here
can't be fixed by an in-app update. For 1.0 that means the last 0.x over signed v0.6.0 (schema 12 →
13 → 14 → 15).

## Version numbers

`appVersionName` in `app/build.gradle.kts` is the only number a release changes, and release-please
changes it. `versionCode` is worked out from it by `versionCodeOf`
(`buildSrc/src/main/kotlin/AppVersion.kt`) as major×10000 + minor×100 + patch (0.1.0 → 100,
1.2.3 → 10203), so minor and patch must stay below 100. Android uses `versionCode`, not
`versionName`, to decide what counts as an upgrade, which is why the two are never set by hand
separately.
