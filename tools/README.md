# tools

Developer utilities. Not part of the app, not shipped in the APK.

## check-apk-signer.sh

Checks that an APK is signed by exactly the certificate in the release keystore, and prints the
certificate's SHA-256 digest. `release.yml` runs it before publishing; see `docs/RELEASING.md`.

```sh
read -rs KEYSTORE_PASSWORD && export KEYSTORE_PASSWORD   # keeps it out of shell history
tools/check-apk-signer.sh settee.apk settee-release.jks settee
```

It needs `keytool` (any JDK) and `apksigner` (Android build-tools). Set `APKSIGNER` to the
binary, or `ANDROID_HOME` for the newest one under `build-tools/`.

## check/check.py

Runs Gradle's tests and checks and prints only the result: each failed test with its message and
the line it failed on, each compiler, ktlint, detekt and lint finding, and any test that took over
30 seconds. Gradle's full output goes to `app/build/check-<mode>.log`. It exits with Gradle's own
result, and works from any checkout or worktree.

```sh
tools/check/check.py test RecordingsContentTest   # the named test classes, while working
tools/check/check.py quick                        # ktlintFormat and detekt, after each change
tools/check/check.py all                          # what a PR's checks run, without stopping at the first failure
tools/check/check.py sweep                        # kill test processes left running over half an hour, anywhere
python3 -m unittest discover -s tools/check       # its own tests
```

A run in which no test finishes for two minutes is reported as `FROZEN`, and one that outlasts its time
(8 minutes, 25 for `all`, or `--timeout MINUTES`) as `TIMED OUT`. Either way the test process is killed
and the last test to finish is named. A frozen test JVM ignores every signal but SIGKILL, Gradle's
request to stop included, and otherwise spins for hours; CLAUDE.md says why it freezes.

`all` is for when CI can't be used: day to day the full checks are CI's (see CLAUDE.md). It runs the debug
unit tests only; CI's `./gradlew test` runs the release variant's as well.

## check-message.sh

The commit convention, `type(#issue): description`, as one POSIX `sh` script. The
`.githooks/commit-msg` hook and the PR title check in `.github/workflows/pr-hygiene.yml` both call
it. `check-message-test.sh` is its table of passing and failing examples.

```sh
tools/check-message.sh "feat(#12): add series rules"   # exit 0, or the rule on stderr
tools/check-message-test.sh                            # run the examples
```

## stalker_mock.py

A tiny, dependency-free mock **Stalker / Ministra portal** for testing OpenTV's Stalker
support without a real provider line. It speaks the slice of the protocol the app's client
calls (`handshake`, `get_genres`, `get_all_channels`, `create_link`) and hands back a few
channels pointing at **free, legal test streams** (Big Buck Bunny, Apple BipBop, Sintel), so
you can exercise the whole path — add source → handshake → channel list → play —
deterministically, and actually see video.

```sh
python3 tools/stalker_mock.py          # listens on 0.0.0.0:8080
python3 tools/stalker_mock.py 9000     # ...or a port you pick
```

It prints the exact values to enter in OpenTV (**Add source → Stalker portal**). Point the box
at the machine running it; the control API is served locally, the video comes from public CDNs.
Every request is logged, so you can watch the handshake and each call land — handy when a real
portal misbehaves and you want to mimic it. See the docstring at the top of the file for details.

Not affiliated with Infomir. No copyrighted content is served or proxied.
