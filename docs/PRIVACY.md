# Privacy

Settee collects nothing about you. There is no analytics SDK, no telemetry, and no network call to
any domain the project controls, because the project controls none. The only report Settee can ever
send is a crash report, and only once you've turned crash reports on: see
[Crash reports](#crash-reports) below.

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

[Sentry](https://sentry.io), for crash reports, and only if you've turned them on. See below.

## Crash reports

When Settee crashes or stops responding, it can send a report to Sentry so the bug can be found and
fixed. Crash reports are **off until you turn them on**: on the last step of setting Settee up, in
Settings → Privacy, or by answering Send or Always send when Settee asks about a crash the next time
it opens. Until you answer, that crash is kept only on this device, with any web addresses and
MAC addresses in it already cut out, and Don't send deletes it. Say Don't send twice and it stops asking. Settings → Privacy turns them off again at any
time, and turning them off throws away any report still waiting to go. The choice belongs to each
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

A report never contains your provider's address, username, password or MAC, the names of your
providers, profiles or channels, or what you were watching. Settee removes everything not on the list
above before a report leaves your device, rather than looking for things to take out, so nothing new
slips through. Sentry is set not to store your IP address, though like any website it sees it when the
report arrives. Problems with your provider, such as a stream that won't play or a login it refuses,
are never reported.

Only Settee's own release builds can send a report. A build made without Settee's Sentry key, such as
a fork or a developer's own build, never sends one and never asks.

Reports are read by Settee's maintainer, and once a day by an automated check, an AI agent run by
the maintainer, that decides which crashes are worth looking into. A crash that keeps happening may
become an issue on Settee's public GitHub, written up from scratch to say what broke and where. A
report itself, or any part of one, is never posted.

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
