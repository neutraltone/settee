# Architecture

This document explains the decisions that are not obvious from reading the code, and in
particular the ones made in direct response to how apps in this category typically fail.

## The shape of it

```
app/src/main/java/app/opentv/
├── core/           ServiceLocator — the whole dependency graph, one file
├── data/
│   ├── model/      Domain models, which double as Room entities
│   ├── db/         Room database and DAOs
│   ├── remote/     XtreamApi — the provider panel client
│   ├── parser/     M3uParser, XmltvParser — pure, streaming, heavily tested
│   ├── repo/       SourceRepository, CatalogRepository, EpgRepository
│   └── work/       SyncWorker — periodic background refresh
├── player/         PlayerController, PlaybackErrors
└── ui/             Compose screens and view models
```

Single module, single Gradle build. A multi-module setup would build faster in CI and is what
a team of ten would do. This is not a team of ten; it is a project whose survival depends on a
stranger with a broken channel being able to find the relevant code in under five minutes.

## No backend. At all.

OpenTV has no server. There is no account, no login, no cloud sync, no web dashboard for
editing your channel list from a browser.

This is the single most important architectural decision in the project, and it is worth being
explicit about why, because a web dashboard is a genuinely nice feature and users ask for it.

**A cloud dashboard has to be paid for every month, forever, per user.** An app funded by a
one-off payment or by donations cannot carry that. Every player that has tried has arrived at
the same place: the hosting bill grows with success, the revenue does not, and eventually the
servers go quiet — taking the app with them, because by then the app cannot start without
them. Users who paid discover that their "lifetime" purchase bought them a client for a
service that no longer exists.

So OpenTV stores everything on the device, in SQLite. The consequences are accepted honestly:

- Setting up a second TV means entering your details again. This is a real cost.
- Favourites and channel ordering do not follow you between devices.

And what is bought with it:

- The app costs nothing to run, so it cannot be switched off.
- Provider credentials never leave the device. Nobody has to trust the maintainers with them.
- It works with no internet connection to anything except your own provider.

If multi-device setup becomes painful enough, the answer is **export/import of a settings file**
— or, at most, optional sync through storage the *user* already owns and pays for. Never a
service the project has to fund.

## The EPG, and why it is written the way it is

A guide that quietly stops working is the defining bug of this app category. It is almost
always caused by the same implementation:

```
delete everything for this source
download the new guide
insert it
```

That is fine when it works. When the download stalls, the provider rate-limits, the box sleeps
mid-sync, or the XML is truncated, the user is left with an empty guide — and the only advice
anyone can give is "reinstall the app", which appears to help because it restarts the sync from
scratch. It fixes nothing. The next refresh fails the same way.

`EpgRepository` never deletes before it has the replacement:

1. Programmes are **written in batches of 500** as they stream out of the parser, each replacing
   the stored programme in its slot (`INSERT OR REPLACE` on the unique index over `(feedId,
   epgChannelId, startUtcMillis)`), so a refresh picks up the provider's corrections and a sync
   that dies at 60% leaves 60% of a *fresher* guide — strictly better than what was there. Not
   Room's `@Upsert`: parsed rows have `id = 0`, so its fallback `UPDATE … WHERE id = 0` matched
   nothing and kept the first download's details (#253).
2. Pruning happens **by age** (`deleteEndedBefore`), never by source, and only **after** a sync
   reports success.
3. A failed sync leaves the previous guide completely intact and returns the reason.

`XmltvParser` streams via `XmlPullParser` and emits programmes to a callback. A week of guide
data for a large provider is comfortably 150 MB of XML; nothing about that fits in the heap of
a cheap TV stick, and `String`-based parsing is an OOM waiting to happen.

**Times are stored as UTC epoch millis, always.** `XmltvParser.parseXmltvTime` normalises the
`+0100`-style offsets that XMLTV uses, treats a missing offset as UTC rather than guessing the
device zone, and converts to local time only at draw time. `XmltvTimeTest` changes the JVM's
default zone between assertions specifically to catch the "works on my machine, an hour out on
yours" bug.

### An open guide while a sync writes

Each batch re-runs every Room query on `programmes`, so an open guide used to regroup and rebuild
its rows hundreds of times during one sync (#257). Two things stop that:

- `EpgRepository.observeWindow` and `observeNow` are `settled()` (`data/repo/SettledListings.kt`):
  the first listings straight away, then the latest at most every two seconds, ending on the
  finished listings, and never the same listings twice in a row. Not a plain `debounce`: that
  delays start-up and shows nothing new until a long first download ends. Settled in the
  repository, not the screen, so anything else that watches the guide gets the same.
- `guideBuilds` (`ui/ProgrammeIndex.kt`) times the guide's next rebuild for more listings from the end
  of the last build, not from when the listings arrived: building "All channels" for a big
  provider outlasts the pause on a Fire TV Stick, and paced from arrival it rebuilt back to back.
  New channels and a new window's first listings never wait.

## Every Catalogue Refresh goes through one module

`data/repo/CatalogueRefresh` is asked by every trigger: the periodic worker, a manual refresh, adding a
provider, opening Movies or Shows, app launch and Guide settings. It decides what each fetches and in what
order, so the triggers can't drift apart.

- **Freshness is per provider and recorded by every VOD fetch,** so opening Movies straight after a
  refresh downloads nothing, and a provider added since the last fetch is still fetched. A list that failed
  or came back with nothing at all isn't recorded, so the next open tries again; saving a provider clears
  its record, because its login may now be another account's.
- **Each part of a provider (live, VOD) has one download at a time.** A second request with the same login
  details shares the running one's result, and a provider's parts take turns. Downloads run in the module's scope, so the first to ask
  leaving a screen doesn't take the download away from the others.
- **New Episode detection is waited for by refreshes nobody is watching and launched by ones a viewer is.**
  Each caller's own rule, whoever started the download they're sharing. Detection takes the provider's turn,
  so its panel requests don't overlap another download from that provider.
- **A VOD download with a login the provider no longer has doesn't run, or count as fresh if it already had.**
- **`RecordingEngine` books one rule's airings at a time,** because booking checks for a row and then inserts one.
- **Manual refreshes run as background work,** so they outlive the screen, which watches `state` instead.

## Catalogue refresh preserves what the user did

`ChannelDao.replaceCatalogue` merges rather than wipes: it reads only what it keeps of the stored
channels (`ChannelUserState`: the row id, `hidden`, `sortIndex`, custom name and both guide ids, not
names or URLs, so a large catalogue isn't held twice while it merges), carries those across to the
incoming rows by `streamId`, upserts, and only then
deletes channels the provider no longer lists. Favourites live in their own per-Profile table,
matched by source name and provider id (not stream URL, which Xtream changes with the live format
and the password), so a channel dropped and re-listed keeps them.

This is why `M3uParser` works so hard to produce a **stable** `streamId` for playlists with no
`tvg-id` — an FNV-1a hash of the URL rather than `String.hashCode()`. An unstable id means
every refresh looks like a completely new set of channels, and the user's hidden channels and
ordering silently vanish.

### Episodes load lazily, with two exceptions

A series' episodes are fetched when it is opened (`CatalogRepository.openSeries`, and
`ensureEpisodes` for its other Versions), never during catalogue sync: one `get_series_info` per
show across a 10,000-show catalogue would take hours. That one reply also back-fills a bare row's
details, so opening a show asks its panel once, and its episodes are stored before TMDB is asked. The first exception is New Episode detection. Whenever the catalogue's shows are merged, every
show whose `last_modified` moved forward goes to `ViewerLibrary.newEpisodes`, which fetches the
episodes only of shows some Profile on the device is caught up on, one at a time. That is normally
a handful of calls, not the catalogue. A refresh (the worker, or Refresh in settings) waits for it.
The Movies and Shows re-sync as the app opens launches it instead, so its spinner isn't held, but
can't skip it: once the stored times have moved, a later refresh would never see that show again.

The second is sync. Progress from another device can name an episode this one hasn't stored, so the
position also carries its `seriesId`, and `WaitingProgress` fetches that series in the background
after `SyncEngine.apply` has returned, so no sync screen waits on a provider. Each series is fetched
once per run, however many positions or peers name it, and a failed fetch stops the rest for that
source, so a provider that's down costs one timeout. A run fetches at most 25 series, and skips
disabled sources. No position is stored while it waits: the peer's bundle still carries it, so a
later sync tries again. Only an episode the provider's answer left out is noted, so it stops being
asked for. The fetched episodes are dated back to the position rather than now, because they're new
to this device, not to the viewer, and mustn't show as New Episodes (ADR 0004).

Episode lists carry no time the provider added each episode, so each row records when this device
first stored it (`firstSeenMillis`). A New Episode is an Up Next first seen after the Profile
Watched the episode before it. That is worked out on the fly rather than stored per Profile, so it
works for every Profile and needs nothing synced. A re-fetch matches rows by provider episode id,
keeping the local id that resume points use and the first-seen time.

### TMDB fills in the background, and again every six months

With the viewer's TMDB Key, the background pass after a catalogue sync (`MovieGenres` for films,
`SeriesTmdbPass` for series) asks TMDB about every title once, 200 at a time with a minute's rest
between, and asks again once its answer is six months old: posters and casts change, and a title TMDB
had no match for may have one since. Each title stores when TMDB last answered (`tmdbAnsweredAt`), a
match or not; a refresh carries it across like every other looked-up detail, and a title TMDB couldn't
be reached about keeps its old date, so it's asked again next time rather than in six months. TMDB only
fills what the provider left empty, the second time as the first. Opening a series asks at once when
its details are missing or that old, so a series the pass hasn't reached still fills in. Without a key
nothing is asked, and nothing TMDB filled in is cleared or aged out.

The key is checked with TMDB before it's saved (`TmdbKeys`), so a mistyped one never sits behind every
lookup failing quietly. It may be TMDB's v3 API Key or its v4 Read Access Token; `TmdbClient` sends
whichever it is the way TMDB wants, from one place. It stays on the box: sync never carries it.

## SQLite's bound-variable limit is a real constraint here

Two queries are written in a non-obvious way because of it, and both are marked in the code.

SQLite caps the number of bound variables in a statement — 999 on older Android versions,
32766 on newer. A real IPTV catalogue has thousands of channels and a large playlist has tens
of thousands. So neither of these works, despite being the natural thing to write:

- `DELETE FROM channels WHERE streamId NOT IN (:everyCurrentId)` — replaced by a sync-stamp
  comparison (`Channel.lastSeenMillis`), which has no limit.
- `SELECT * FROM programmes WHERE epgChannelId IN (:everyChannelId)` — replaced by a
  time-window query with grouping done in memory by the caller.

Both would pass every test written against a small synthetic dataset and fail on exactly the
providers where it matters most.

The `IN (:streamIds)` lookups that do exist (`ChannelDao.byStreamIds` and the batch form of
`hiddenChangesFor`) only ever see one chunk of a refresh, 500 ids at most, which
`replaceCatalogue` enforces itself rather than trusting its caller's batch size.

## Playback

`PlayerController` owns exactly one `ExoPlayer` for the lifetime of the screen and only ever
swaps its media item.

The naive implementation of "change channel" releases the player and builds a new one. On a
low-end box that is slow enough that a user holding channel-up queues several constructions and
teardowns, and the codec ends up in a state where nothing plays until the app is force-stopped.
That is the entire mechanism behind "changing channels too quickly causes streams to fail", and
it is self-inflicted.

So: requests are debounced (350 ms), an in-flight switch is cancelled the moment a newer one
arrives, and the player is `stop()`ed rather than released. Holding channel-up costs one actual
tune — the one you stopped on.

### Retries are tuned for IPTV, not for CDNs

ExoPlayer's default policy gives up almost immediately on a 403 or a 5xx. That is right for a
CDN and wrong here: a provider under load returns 403, 429 or 503 for a few seconds and then
serves the stream perfectly. Users experience the default as "this channel doesn't work" when
it demonstrably works on the second attempt.

`PlayerController`'s `IptvLoadErrorPolicy` retries those, and reads that fail or drop (from a
provider, the NAS or a recording), with exponential backoff (500 ms doubling to 8 s, 5 attempts);
then the player restarts silently, up to two times. `401`, `404` and `410` are **not** retried —
those will not fix themselves.

Whether anything else is retried is left to ExoPlayer's own policy, which deliberately doesn't
retry a stream that won't parse, a missing file, a cleartext refusal or running out of memory.
Providers often answer an expired account or a blocked channel with HTTP 200 and an HTML page,
and backing that off, then restarting, kept the spinner up for about 50 seconds before the error
showed (#255). `PlaybackRecovery` decides the restarts: a stream that won't parse from the start
isn't restarted silently, but Retry stays on offer, as it does for running out of memory; one that
played and then broke is restarted, since that's more likely a bad segment than an HTML page. A
format this device can't decode gets neither.

### Errors say what to do

`PlaybackErrors` turns playback failures into a sentence and a detail line, and `ErrorWording`
does the same for everything else. `ERROR_CODE_IO_BAD_HTTP_STATUS` tells a viewer nothing; "your
account may be in use on another device, so stop it there and try again" tells them what to try.

This matters more than it sounds. The alternative habit — hiding the error code because users
complained about seeing it — treats the symptom while the stream still fails, and removes the
only diagnostic the user had. So the code isn't hidden, it moves: `ErrorMessage` draws it, with
the HTTP status and the stream's format, in a quiet line under the sentence. Both are built from
the failure's type, never an exception's message, which can carry a provider URL, login or MAC.

[`docs/ERRORS.md`](ERRORS.md) lists every failure, its string and its detail line.

### Downloaded Subtitles are drawn by Settee, not side-loaded into ExoPlayer

A subtitle fetched from OpenSubtitles could go to ExoPlayer as a `SubtitleConfiguration` on the
media item. It doesn't, because of Timing: a side-loaded track's times are fixed when the item is
prepared, so every 0.1-second step would mean replacing the media item and buffering again, where
the viewer needs to watch the lines move as they adjust. Instead `DownloadedSubtitleCues` parses
the SRT once (with Media3's own `SubripParser`) and the film player draws the lines at the
player's position plus the offset, in a `SubtitleView` styled as the stream's own. The stream is
never touched, so picking a subtitle can't lose the position, and while one shows the stream's
text is switched off. In the subtitle list they're ordinary rows; `chooseSubtitle` weighs them
against the stream's tracks when a title starts, the stream's own track winning in a language.

OpenSubtitles is Settee's one bundled key (`BuildConfig.OPENSUBTITLES_API_KEY`, from a repository
secret or `local.properties`, never committed): its terms ban apps that make viewers bring their
own. A build without one has no `ServiceLocator.openSubtitles`, and with it nothing that offers
OpenSubtitles. The files and their index live in `filesDir/subtitles` rather than Room: they never
sync, are files anyway, and a table would cost a migration for what one JSON index does.

## Defensive parsing of Xtream responses

`XtreamApi` reads `JsonElement` by hand instead of deserialising into `@Serializable` data
classes, and every accessor returns null rather than throwing.

Xtream Codes is not a standard. Different panel builds return the same field as a number, a
quoted number, an empty string, `null`, or omit it. `stream_id` alone flips between `12345` and
`"12345"`. Strict deserialisation means one unusual field aborts the parse of the entire
catalogue, which the user experiences as "the app won't load my channels" with nothing to go on.

It is ugly. It is correct. Do not "clean it up" into typed models without a very large corpus
of real panel responses to test against.

The big lists (live channels, movies and series) never arrive whole. `XtreamApi` saves each reply
to a temp file as it downloads and hands it back as a `CatalogueList`, which reads one array
element at a time (`decodeToSequence`) and gives the caller 500 at a time. A large panel's list is
tens of megabytes, and holding it as a string, a JSON tree and the parsed rows at once ran a Fire
TV Stick's 128 MB heap out. Each list is saved in one transaction across its batches; for live
channels `ChannelDao.replaceCatalogue` merges each batch as it comes and deletes stale channels
only after the last, so a reply cut off partway changes nothing.

Similarly, `syncXtream` treats VOD as optional: plenty of accounts are live-TV only, and a 404
on `get_vod_streams` must never cost the user their channel list.

## Dependency injection

`ServiceLocator` is a hand-rolled container. Hilt is the conventional choice and is
deliberately not used: it adds an annotation processor, a build-time graph and a class of error
message that is genuinely hard to read, in exchange for wiring about a dozen objects. For a
project hoping for drive-by contributions from people fixing their own bugs, "you can read the
whole graph in one file" is worth more than the ceremony.

## Testing

The parsers are pure functions over strings and streams, which makes them cheap to test
exhaustively — and they are where the bugs that ruin someone's evening actually live. Every
malformed case in `M3uParserTest` is something that should cost the user one channel, never the
whole playlist.

Things worth testing that are not yet covered, and would be very welcome contributions:

- `EpgRepository.sync` against a mid-stream failure, asserting the old guide survives
- `XtreamApi` against captured real-world panel responses (with credentials stripped)
