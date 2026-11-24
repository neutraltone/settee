# Crash reports also carry Settee's service failures, slow journeys and errors that could be Settee's

ADR 0009 let Settee send Crash Reports, and kept them to crashes, freezes and two handled errors only Settee's own code can cause. It said Provider failures and slowness are never reported. But most of what spoils a viewer's evening doesn't crash anything: OpenSubtitles turns down Settee's sign-in with a 403, Movies takes twenty seconds to open on the stick, films won't load and the screen shows an error. The owner heard about these only if a viewer mentioned them, with nothing to go on (#629). So Crash Reports now carry three more kinds, each sent through `CrashReports` and its allow-list scrubber like the rest:

- **Settee's own service failures.** OpenSubtitles, TMDB, IntroDB/TheIntroDB and the GitHub update check run on Settee's keys and accounts, so a failure caused by Settee's request is Settee's bug. Each is a handled kind (`OPENSUBTITLES`, `TMDB`, `INTRODB`, `UPDATE_CHECK`) and its event names only the service, the operation (`sign-in`, `account`, `search`, `download`, `details`, `timings`, `latest-release`) and the HTTP status or failure class (`http-403`, `unreadable-reply`), nothing from the request or the reply. Sent only while crash reports are on.
- **Slow Journeys.** A few journeys are timed in Settee's own code, and one event is sent only when Settee's own part of a journey was clearly too slow. Nothing is sent when things are fine. Sent only while crash reports are on.
- **Error Reports.** An error message that could be Settee's fault is sent as it's shown while crash reports are on, once per failure. While they're off it offers **Report**, which asks whether to send that one failure, as Send does after a crash.

## Which HTTP statuses are reported

Only the ones that point at Settee's key, request or parsing:

- **Reported:** 400, 401, 403, 404 and 410 from a service's API endpoint, and a reply Settee couldn't read, with the exceptions below.
- **A 401 is reported only where Settee's key was all the request carried**: TMDB, IntroDB and TheIntroDB, the update check, and an OpenSubtitles search or download made without a session. A 401 to OpenSubtitles' sign-in is the viewer's password, and a 401 to a request carrying the viewer's session token (a download, the account's allowance) is that session having ended, which Settee renews by asking them to sign in again: neither is Settee's bug, and neither is reported.
- **A 404 is reported only where it can't mean "nothing here".** A TMDB search answers no match with an empty list, never a 404, so a search's 404 means Settee asked for an address TMDB doesn't have, and is reported. A TMDB look-up by id answers 404 for a title it doesn't know, and IntroDB and TheIntroDB answer 404 for an episode no one has timed; each answers a wrong address with a 404 too, and nothing in the reply tells the two apart, so those 404s are never reported: one that meant a wrong address would be lost among the answers. The update check's 404 (no latest release, or the wrong repository) is reported.
- **Never reported:** no network, a timeout, a DNS failure, any 5xx, 429 or a quota reply (the service's or the viewer's connection, not Settee's bug).

What the viewer sees doesn't change: these are reported beside the error Settee already shows, never instead of it.

## Slow Journeys

Each journey is timed by a `JourneyTimer`: when it starts, when it ends, and how long it spent waiting on the network inside it, which the HTTP clients mark. Settee's own time is the total minus that wait, so a slow Provider or a slow connection never makes a journey count as slow. Timing is a couple of `SystemClock.elapsedRealtime()` reads and allocates nothing on hot paths, but for one small listener per HTTP call that remembers when the call started.

A journey's network wait is only the time its own calls were in flight, not any call in the app. A call counts for a journey only if it started while the journey was running, so a guide download or a recording already going doesn't count. And a stream counts only for `playback-start`: the players and recordings read streams through their own HTTP client, whose calls only a start of playback waits on, so a Live Preview or a recording playing on while Movies opens never hides a slow Movies. Calls a journey didn't make but that started during it, such as a background sync that happens to start then, still count; that can only make it report less, never wrongly.

An event is sent when Settee's own time passes the journey's threshold. Each threshold is about 2–3× the median the Fire TV Stick measured in [PERFORMANCE.md](../PERFORMANCE.md), rounded, so "too slow" means clearly slower than the stick normally is. Where the stick has no measurement of a journey, the threshold is a cautious guess marked provisional, to be replaced with 2–3× a median once one is measured:

| Journey | From | To | Stick median | Threshold |
|---|---|---|---:|---:|
| `cold-start` | process start | Home's first row drawn with its cards | 2.2 s (Home's Continue Watching, #582) | 6 s |
| `open-movies` | Movies selected | its first row drawn | none measured from selecting the tab: about 2 s, inferred from 4.3 s after process start, which includes starting up and about 2 s of key presses (#582) | 5 s, provisional |
| `open-shows` | Shows selected | its first row drawn | none, as for Movies (4.3 s after process start, #582) | 5 s, provisional |
| `catalogue-refresh` | refresh started | catalogue written | none | 60 s, provisional |
| `playback-start` | asked to play | first frame drawn | none | 4 s, provisional |

The thresholds are constants beside the timer (`JourneyTimer.Journey`), marked provisional there too, and this table changes with them. Only `cold-start` has a measured median. The provisional ones are replaced, each with 2–3× its median, once the stick measures that journey: `tools/perf/stick_baseline.py` and docs/PERFORMANCE.md measure cold start today, and each other journey needs a measurement of its own there first. An event carries the journey's name, Settee's own time and the network wait (each rounded to 100 ms), free memory, the screen and the Source's kind (Xtream, M3U, Stalker or Stremio), with the usual app and device fields.

- **At most one per journey per launch.** A second slow run of the same journey in one process sends nothing.
- **A freeze wins.** A slow journey that also froze the app is reported once, as the freeze. Before Android 11, Sentry catches a freeze as it happens, while the journey may still be running, and the journey's report is then dropped. From Android 11 on, Sentry reports freezes only from Android's exit history at the next launch, and that history only holds freezes that killed the process: the journey a freeze like that froze never reached its end, so it was never reported, and a freeze found that way is never taken to be one of the new launch's journeys.
- **Debug builds never send one**, but log to logcat when a journey passes its threshold, so slowness shows during development.

## Error Reports

Error Reports are made on the errors where a failure could be Settee's: catalogue and VOD lists, a series' episodes, playback, a Provider's refresh failure and sign-in, and the services' sign-ins. Never on an error that's certainly the viewer's own, such as no internet or a wrong password (`ErrorReport.offeredFor`).

**With crash reports on, Settee sends them itself**, as each error is shown, and the error's detail line ends "· Sent to Settee". Nothing to press. The viewer already said yes to reports in Settings (or Setup, or Always send after a crash), and that switch now says it covers crash and error reports; asking again on every error would put privacy plumbing on every failure and make the error read as a second one. Volume is bounded twice: each failure is sent once per process, and the daily cap below covers them with everything else. "Once per failure" is keyed on the screen open, the Source's kind and the detail line: an error is composed again on every recomposition, each time its screen comes back, and a Source's saved refresh failure on every launch, and none of those is a new failure. The key holds nothing the event doesn't. It isn't saved, so a launch that still shows the failure sends it once more: one more launch seeing it is worth an event, and the cap bounds the rest.

**With crash reports off, the viewer chooses.** Report is the last button in the error's own row, after Try again, Provider setup or Back, so Try again keeps the Highlight it starts with and Report is Right along the row; where the error's own action is a list row (a provider's Refresh again, OpenSubtitles' sign-in row), Report is the next thing Down from it. OK opens a Side Panel, Send a report?, saying what's sent: Send (where it starts), Don't send, and Always send, which turns crash reports on and then sends it. Send doesn't change the viewer's choice; Always send does, because the panel says so and the viewer picked it. Back, like Don't send, sends nothing. Once sent, Report goes, the Highlight goes back to the error's main action, and the detail line ends "· Sent to Settee". On a live channel's error, where Up and Down change channel, Report sits beside Try again: Right from Try again reaches it, Left goes back, Up, Down and the channel keys still change channel, and Right from Report still opens the qualities. A live error without Try again shows no Report, since the Highlight stays on the full-screen catcher there, but is still sent while reports are on.

The event carries what the viewer saw: the error's kind, the detail line (code · status · format) after scrubbing, the screen, the Source's kind, who sent it (`sent-by`: `settee` or `viewer`), and the usual app and device fields. The kind is the detail line's first code (such as `HTTP 503`, `IoBadHttpStatus` or `BadReply`), not the sentence: that code is what `ErrorWording` and `PlaybackErrors` chose the sentence by, it's the same in every language, and it survives being saved, as a Source's last refresh failure is, where no category object does. No stack trace is sent: a failure reaches the screen as a `ViewerError`, a sentence and a detail line, with no exception left to send, and the screen's code would say nothing about the failure. It never carries the Source's address, name or credentials, the channel or title, or a stream URL.

**A Provider failure leaves the device only as an Error Report of an error the viewer saw**, sent with their yes: the standing one in Settings, or Send for that one failure. Failures Settee retries or works around without showing an error are never reported. This reverses the first version of this ADR, which had Provider failures leave only when the viewer pressed Send report on each: that put a second, equal-weight button and a note about privacy on every error, and asked again people who had already said yes.

## The daily cap

Each install sends at most **20 non-crash events a day**: service failures, Slow Journeys and Error Reports together, with ADR 0009's two handled errors (an Xtream reply Settee couldn't parse, a database migration) counted among them, since they go through the same `CrashReports.report`. The cap is `DailyCap.MOST_A_DAY`, counted in `AppSettings` by UTC day, so a restart doesn't start the day again and every device's day is the same length. An event counts only once it's going: one dropped because crash reports are off, or a slow journey reported as the freeze it was, doesn't use up the day. Past the cap the rest of the day's are dropped, never held to send later. An Error Report over the cap still says "Sent to Settee": the viewer did what they could, and telling them a report was refused because of other reports would only puzzle them. Crashes and freezes aren't capped by Settee; Sentry's rate limit on the key still applies to everything, and is checked against the new volume (#571). The cap is what keeps one struggling stick, or a run of failing screens, from using up the free plan and burying everyone else's crashes.

## What still never leaves the device

Every rule of ADR 0009 still holds for the new kinds. Nothing about Providers (address, name, username, password, MAC), Profiles, channel names or titles; no stream URLs. Only builds with Settee's DSN can send, and in builds without one none of the three exists, Report included. `CrashReports` and its helpers stay the only code that touches Sentry, and every new kind is on its closed list. No Sentry tracing, profiling, sessions or release health, and no timing sent for journeys that weren't slow.

This ADR replaces the parts of ADR 0009 that say Provider failures and slowness are never reported, and nothing else in it. Loosening any of this, such as reporting Provider failures the viewer never saw or while crash reports are off without their Send, dropping the once-per-failure rule, adding a status, a journey or a field, raising the cap, or sending timing when nothing was slow, needs a new ADR that replaces this one.

We turned down three alternatives:

- **Sentry's performance monitoring.** Tracing would time every journey, but it sends a transaction from every launch, fine or not, which is the usage tracking ADR 0009 rules out, and it would use up the free plan.
- **Reporting every Provider failure automatically, scrubbed.** A flaky panel can fail hundreds of times an evening inside Settee's retries, burying Settee's own bugs, and each failure describes the viewer's Provider even with its address removed. Only errors the viewer was shown are reported, each once, with their yes.
- **Asking on every error, whatever the viewer's choice.** The first version of this ADR did: a Send report button and a note under every error. With reports on it asked people who had already agreed, and made each error look like two; it's offered only while reports are off.
- **Thresholds against total time.** Simpler, but a slow Provider or a slow connection would be reported as Settee being slow, and the events would describe the viewer's Provider.

## Consequences

- `PRIVACY.md` has a paragraph for each new kind saying what it contains and never contains, and changes whenever any of them does.
- The scrubber's tests send Xtream-, M3U- and Stalker-shaped URLs and a MAC through each new kind, so a leak fails CI.
- The daily triage routine treats the kinds differently (`docs/agents/sentry-triage.md`): a service failure like a crash, a Slow Journey only when more than one install hits it or it regresses across releases, and Error Reports as leads to group, filed only when several installs report the same kind.
- `docs/ERRORS.md` says which errors are reported, and how Report is reached.
- The triage routine reads `sent-by` to tell Settee's automatic Error Reports, which are more numerous, from those a viewer chose to send.
