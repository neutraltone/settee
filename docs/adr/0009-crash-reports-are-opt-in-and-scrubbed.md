# Crash reports are opt-in and scrubbed

Settee used to promise that it collected nothing: no crash reporter, no telemetry. But a crash or a freeze on a viewer's Fire TV Stick went unnoticed unless they mentioned it, and even then there was no stack trace or app version to fix it from. Bugs that only show up on a real stick, with a real Provider and 1 GB of memory, stayed invisible. So Settee now sends Crash Reports to Sentry (#570). It keeps as much of the old promise as reporting allows, and these rules are why the change is acceptable at all:

- **Off until the viewer turns it on.** Nothing is sent by default. A viewer can turn reports on in three places only: the last step of Setup, after the OpenSubtitles step, where "Not now" has the Highlight so pressing OK through never opts anyone in; Settings → Privacy, where they can also turn it off again; and the question asked the next time Settee opens after a crash or freeze it held (Send, Don't send, Always send). Two Don't sends and that question stops being asked. The choice belongs to the device, so it isn't synced and doesn't change between Profiles. Turning it off throws away anything held or waiting to go.
- **An allow-list decides what leaves the device, not a deny-list.** A report has the app version, the Android or Fire OS version, the device model, free memory, the route name of the screen that was open (never its arguments), a random install id and the stack trace. Every other tag, extra, context and breadcrumb is removed in the SDK's before-send and before-breadcrumb hooks, so this also covers anything a later SDK version adds. In what's left, URLs lose their host, login, query string and fragment, and anything shaped like a MAC is masked.
- **Nothing about Providers, Profiles or what's watched ever leaves the device.** No Provider address, username, password or MAC; no Provider, Profile or channel names; no titles. Sentry is set not to store IP addresses. Provider failures (HTTP errors, dead streams, panels returning web pages) are never reported, and of handled errors only a closed list that only Settee's own code can cause is.
- **Only official release builds can send.** The Sentry DSN is built in from a CI secret, the same way as the TMDB Key. Debug builds, forks and anyone's build without the key have no DSN, and in those builds crash reporting doesn't exist: no SDK starts, and the viewer is never asked.

Loosening any of these, such as turning reports on by default, adding a field to the allow-list, reporting Provider failures or letting another build send, needs a new ADR that replaces this one.

We turned down three alternatives:

- **On by default, with a way to opt out.** It would catch more crashes, but it would quietly break a promise Settee had published, and a viewer who never opens Settings would never know.
- **Redacting reports the way Diagnostics does.** Settings → Diagnostics' report removes the Provider addresses and logins it recognises (`diagnostics/Redaction.kt`), which is fine for a file saved to the viewer's own NAS. A crash report goes to a third party, and the next leak is usually something nobody thought to look for, so only what's listed may leave.
- **Self-hosting Sentry or GlitchTip.** It would keep reports off a third party's servers, but Settee never runs a server of its own.

## Consequences

- `core/CrashReports` is the only code that imports Sentry's SDK. Screens, view models and `OpenTvApp` go through it, so a rule changes in one place.
- The SDK is started by hand, never by its auto-init content provider, so nothing runs before Settee has checked the DSN and the viewer's choice. Performance tracing, profiling, session replay, screenshots, view hierarchy, interaction, network and logcat breadcrumbs, and `sendDefaultPii` all stay off.
- The scrubber's tests send Xtream-, M3U- and Stalker-shaped URLs and a MAC through the module, with a fake transport in place of Sentry's, and check what would have left the device. A change that leaks a credential fails CI.
- A DSN can always be pulled out of an APK. It can only send, and Sentry's filters (spike protection, a rate limit, dropping releases that aren't Settee's) are what limit misuse.
- The daily routine that files GitHub issues from Sentry writes each issue in its own words and never pastes an event, so if the scrubber ever misses something, it still doesn't end up in a public issue.
- `docs/PRIVACY.md` lists what a report contains and what it never contains, and has to change whenever the allow-list does.
