# Privacy

Settee collects nothing about you. There is no analytics SDK, no telemetry, and no network call to
any domain the project controls, because the project controls none. The only reports Settee can ever
send are crash reports, once you've turned them on, and an error report you choose to send by
pressing Send report: see [Crash reports](#crash-reports) below.

## What is stored, and where

Everything lives in a SQLite database in the app's private storage on your device:

- Your provider's address, username and password
- The channel, movie and series catalogue downloaded from your provider
- The guide (EPG)
- Your favourites, hidden channels and resume positions

None of it is uploaded anywhere. There is no account to create and no server to sync with.

## Who your device talks to

Only your provider, and only at the address you entered. Channel logos and posters are fetched
from whatever URLs your provider puts in its catalogue, which are usually its own servers but
may be third parties — that is your provider's choice, not ours.

[TMDB](https://www.themoviedb.org), for the posters, synopses and cast your provider left out, and
the IMDb match that add-ons need. With no setup at all, after every catalogue refresh Settee asks
TMDB about every film and show in your catalogue, by title and year (or the TMDB id your provider
gave), and asks again whenever you open a title's page. It sends the title being looked up and
Settee's own key, nothing about you or what you watch, though TMDB sees your device's IP address as
any website does. A build made without Settee's key, such as a fork, never asks TMDB.

[TheIntroDB](https://theintrodb.org) and [IntroDB](https://introdb.app), for when an episode's
credits start, so the Next Episode card comes up then rather than near the end. When an episode
starts, and again in the background for the episode after it, Settee asks TheIntroDB for that
episode by the show's TMDB id, season and episode number, and asks IntroDB the same by the show's
IMDb id only when TheIntroDB hasn't timed its credits. Unlike TMDB's lookups, these say which
episode you're watching: nothing else about you, though both see your device's IP address. What
they answer stays on your device, isn't synced, and is asked again only for an episode nobody had
timed, after a week. Turn off Settings → Display → Skip intros and credits and neither is asked.

[Sentry](https://sentry.io), for crash reports, only if you've turned them on, and for an error report
when you press Send report. See below.

## Crash reports

When Settee crashes or stops responding, it can send a report to Sentry so the bug can be found and
fixed. So can two errors Settee shows rather than crashing, because they usually mean a bug in Settee: a
provider's list that arrived whole but that Settee couldn't read, and Settee's own database failing to
open after an update. For the first, the report says where in Settee's code reading failed, never what
the provider sent. A list that arrives cut off is your provider's problem, and isn't reported.

Crash reports are **off until you turn them on**: on the last step of setting Settee up, in
Settings → Privacy, or by answering Send or Always send when Settee asks about a crash the next time
it opens. Until you answer, that crash is kept only on this device, with any web addresses and
MAC addresses in it already cut out, and Don't send deletes it. Say Don't send twice and it stops asking.
Turning reports on in Settings or during setup sends a crash that's being kept, as Always send would.
Settings → Privacy turns them off again at any time, and turning them off throws away any report still
waiting to go, and any crash being kept. A freeze or crash from while reports were off is never sent
later just because they're turned on, other than the one kept crash. The choice belongs to each
device: it isn't synced and doesn't depend on who's watching.

A report contains only:

- Settee's version
- the Android or Fire OS version
- the device model
- how much memory was free
- the screen that was open, by its name only (such as the guide, or a film's page), not which channel
  or title was on it
- a random id made when you first turn reports on, so ten crashes on one device can be told apart from
  one crash on ten devices
- the stack trace: which parts of Settee's own code were running, with any web addresses cut down to
  their path and anything shaped like a MAC address hidden
- an id for the exact build, the same in every copy of a release, so the stack trace can be matched
  to Settee's code

For a crash kept until you answer, the screen, the free memory and the versions are the ones from
when it happened, not from when the report is sent.

A report never contains your provider's address, username, password or MAC, the names of your
providers, profiles or channels, or what you were watching. Settee removes everything not on the list
above before a report leaves your device, rather than looking for things to take out, so nothing new
slips through. Sentry is set not to store your IP address, though like any website it sees it when the
report arrives. Problems with your provider, such as a stream that won't play or a login it refuses,
are never reported unless you press Send report on that error yourself (see
[Error reports](#error-reports) below).

Only Settee's own release builds can send a report. A build made without Settee's Sentry key, such as
a fork or a developer's own build, never sends one, never asks, and never offers Send report.

Reports are read by Settee's maintainer, and once a day by an automated check, an AI agent run by
the maintainer, that decides which crashes are worth looking into. A crash that keeps happening may
become an issue on Settee's public GitHub, written up from scratch to say what broke and where. A
report itself, or any part of one, is never posted.

### Settee's own services failing

With crash reports on, Settee also reports when one of the services it uses with its own key or
account turns down its request: OpenSubtitles, TMDB, IntroDB or TheIntroDB, or the check for a new
version on GitHub. That's a bug in Settee, such as a key that's been refused or a service that has
changed how it answers. A report contains only which service, what Settee was asking it to do (such
as signing in or searching), and the kind of failure (such as `http-403`, or a reply Settee couldn't
read), with the version and device details listed above. It never contains anything Settee sent or
the service answered: not what was searched for, not which film or episode, not your OpenSubtitles
username or password. Your internet being down, a service that's busy or briefly unavailable, a
service's own daily limit, and your OpenSubtitles password being wrong are never reported.

### Slow journeys

With crash reports on, Settee also times a few things it does: opening the app until Home has its
first row, opening Movies or Shows until the first row shows, refreshing your catalogue, and starting
something playing until its first picture. It sends a report only when Settee's own part took clearly
longer than it normally does on a Fire TV Stick, not counting time spent waiting for your provider or
the internet, and at most once for each of these each time Settee is opened. When things are fine,
nothing is sent and nothing about the timing is kept. A report contains only which of these it was,
how long Settee's own part took and how long was spent waiting on the network (each rounded to a
tenth of a second), how much memory was free, the screen it was on, and what kind of provider it was
(Xtream, M3U, Stalker or Stremio), with the version and device details listed above. It never
contains your provider's address, name or login, what was playing, or which channel or title.

### Error reports

Some errors that could be Settee's fault, such as films that won't load, a stream that won't play
or a sign-in that fails, offer **Send report**. Pressing it sends that one error to Sentry, even with
crash reports off, and doesn't turn them on; it then shows Sent, and that error can't be sent again.
Errors that are certainly not Settee's, such as no internet or a wrong password, don't offer it. This
is the only way a problem with your provider is ever reported, and only when you press it. A report
contains only what kind of error it was (the code that starts the line of detail, not the sentence
on screen), the line of detail under it (such as the error code, the HTTP status and the stream's
format) with any web addresses and MAC addresses cut out, the screen it was on, what kind of provider it was (Xtream, M3U, Stalker or
Stremio), the stack trace if there is one, and the version and device details listed above, with the
random id only if crash reports have been turned on on this device. It never contains your
provider's address, name, username, password or MAC, the channel or title, or the stream's address.

Settee sends at most 20 of these three kinds of report a day from one device, all together, so one
device having a bad evening can't crowd out everyone else's. Crashes and freezes don't count towards
that.

## Cleartext HTTP

The app allows plain HTTP (`usesCleartextTraffic="true"`). The overwhelming majority of IPTV
panels are HTTP-only; refusing them would make the app useless to most of the people it is for.

Be aware of what that means: **on an HTTP connection, your provider username and password are
sent unencrypted**, and anyone able to observe the network between you and your provider can
read them. This is a property of how these services are built, not of OpenTV. If your provider
offers an HTTPS endpoint, use it.

## Uninstalling

Removing the app removes the database and everything in it.

## Reporting a privacy problem

Open an issue. If it involves something you would rather not post publicly, say so in the issue
without the details and a maintainer will arrange somewhere better.
