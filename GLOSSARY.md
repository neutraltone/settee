# OpenTV

An IPTV player for TV boxes and phones: it plays live channels, movies and series from a provider the viewer already pays for, and remembers what each viewer has watched and liked.

## Viewers

**Profile**:
One viewer, known across their devices by name: a name with its own Favourites, watch progress, Recent Searches and Subtitle Preference. No password, no account.
_Avoid_: User, account

**Removed Profile**:
A Profile name taken off this device. It stays off, whatever other devices send, until the name is added here again. Other devices keep their Profile of that name.
_Avoid_: Deleted profile

**Profile Rename**:
One Profile name becoming another at a known time. It spreads to the viewer's other devices, and anything still labelled with the old name lands on the new one. If the new name already exists there, the two Profiles merge.
_Avoid_: Profile merge (that's only one outcome)

**Who's watching?**:
The question the app asks when it starts on a device with more than one Profile: the viewer picks which Profile they are before Home opens.
_Avoid_: Profile picker, profile chooser, profile select

**Profiles**:
The screen behind the Rail's Profile, listing every Profile on this device: where they're added, renamed, removed and switched between.
_Avoid_: Profile settings, accounts

**Subtitle Preference**:
Whether a Profile wants subtitles shown, and in which language. Belongs to one Profile and follows it from one title to the next, whatever kind of title it is. When the last pick came from OpenSubtitles, it also remembers whether that was a Forced or Hearing impaired subtitle.
_Avoid_: Captions setting, CC, subtitle default

**OpenSubtitles**:
The free online subtitle library Settee searches when a film or episode has no subtitles the viewer wants. It only supplies subtitles, never anything to play.
_Avoid_: Subtitle provider, Provider (that's the IPTV service)

**OpenSubtitles Account**:
The viewer's own free account on OpenSubtitles, signed in on this box so downloads count against their own daily allowance. It belongs to the box, not a Profile, and isn't synced. Settee's key is Settee's own: viewers never bring one.
_Avoid_: OpenSubtitles key, API key, login

**Downloaded Subtitle**:
A subtitle file fetched from OpenSubtitles for one title and kept on this box, where it's offered alongside the stream's own tracks from then on.
_Avoid_: External subtitle, sideloaded subtitle, OpenSubtitles track

## Providers

**Provider**:
The IPTV service the viewer already pays for, connected by an Xtream Codes login, an M3U playlist or a Stalker portal. It supplies the channels, movies and shows; Settee only plays them.
_Avoid_: Source, playlist (that's only one way to connect)

**EPG Source**:
Where a provider's guide data comes from, such as an XMLTV link. The term is for setup; everywhere else the viewer just sees the guide.
_Avoid_: Source on its own, feed

**Catalogue Refresh**:
Fetching a provider's channels, movies, shows and guide again and merging them into what's on the device. It never wipes what the viewer set: hidden channels, custom names, Favourites and progress all carry across. Runs every six hours, when the viewer asks, when a provider is added, and when Movies or Shows is opened and that provider's titles are more than 12 hours old. Adding a provider fetches its movies and shows too only during Setup; otherwise they wait until Movies or Shows is opened.
_Avoid_: Sync (that's between the viewer's devices), reload, update

**Setup**:
What follows saving a Provider when none was saved before: its first Catalogue Refresh, shown with its progress until the library is ready to browse. It comes after Onboarding, the page where the viewer chooses how to add the Provider, and isn't part of it.
_Avoid_: Onboarding (that's the page before), sync, first run

**Quality**:
How big a picture a stream promises, such as HD, FHD or 4K. A Channel's streams differ by Quality, and the highest one this device can play is the one that plays by default; a Channel's Qualities are the same wherever it's shown. For a Movie or Series, Quality is one of the things a Version is.
_Avoid_: Variant (in user-facing text), feed

**TMDB**:
The free online film and TV database Settee asks for what a Provider left out: posters, backdrops, synopses, cast and the IMDb match add-ons need. It never supplies anything to play. Settee asks it with its own TMDB Key, with nothing for the viewer to set up.
_Avoid_: Provider, metadata provider, scraper

**TMDB Key**:
Settee's own key from TMDB, built into release builds, which lets every box ask TMDB for details. Viewers never enter one. A build made without it (a fork, or a developer's build with no key of their own) never asks TMDB.
_Avoid_: Token, API key (except when pointing at the field of that name on TMDB's site), connecting your TMDB account

**Version**:
One of a provider's copies of a Movie or Series, such as its Netflix copy and its 4K copy. A title shows once however many Versions it has. A Version may differ from the others in Quality, language, subtitles, sound, or in being a cam copy; Settee never switches to a Version in another language on its own. On screen, choosing between them is the Quality pill; the word Version isn't shown.
_Avoid_: Copy, variant, duplicate

**Add-on**:
A Stremio add-on the viewer adds by its address. It supplies streams for films and episodes, asked for by their IMDb id, and never a catalogue Settee refreshes or syncs. Once one is set up, Search also shows titles the Provider doesn't carry, which play only through add-ons.
_Avoid_: Plugin, extension, source

**Add-ons source**:
Where a title found through an Add-on is kept once it's opened: a built-in source, named Add-ons on every device, with no address and no Catalogue Refresh, never listed with the Providers. Its titles are kept by IMDb id and behave like a Provider's: progress, Watched, Continue Watching, Favourites and sync all work, and when a Provider carries the same title, the Add-ons source's copy is one more Version of it. Removing every Add-on leaves its titles in place.
_Avoid_: Add-on provider, Stremio provider, virtual provider

**Torrents**:
The switch in the Add-ons settings that lets torrent streams from Add-ons play. Off until the viewer turns it on, after being told that everyone sharing the file can see this box's internet address. It belongs to the box, not a Profile, and isn't synced.
_Avoid_: P2P, BitTorrent setting, seeding

## Live TV

**Channel**:
A live station the viewer can watch, however many qualities or providers carry it. Rows with the same name and region fold into one Channel. A row with no region joins the one regional Channel of its name, if there's only one.
_Avoid_: Stream, row

**Guide**:
Live TV's grid of Channels against time, with the Categories Sidebar to its left.
_Avoid_: EPG (in user-facing text), grid, channel list

**Channel List**:
The panel over live TV listing Channels with what's on, for browsing without leaving the picture. The guide once had a one-channel-per-row layout; it's gone, and was never a Channel List.
_Avoid_: List layout, mini guide

**Programme Details**:
What OK on a programme in the guide opens, with its time, synopsis and what can be done with it (watch, record, remind).
_Avoid_: Record dialog, programme dialog

**Parental Lock**:
Categories hidden on this device, for every Profile on it, from everywhere viewers browse and play, but not from the management screens. A PIN guards the setting; entering it shows them until the app closes. A lock spreads to the viewer's other devices when they sync; an unlock stays on the device it was made on.
_Avoid_: Hidden channels (that's the channel manager's per-channel hide), parental controls

## Watch progress

**Movie**:
A single film from the provider, played on its own rather than as part of a Series.
_Also_: Film, in conversation; the app always says Movie

**Series**:
A whole show from the provider, made up of its seasons and their episodes.
_Avoid_: Show (in the app's own wording, though the tab is called Shows), programme (that's what's on a Channel in the Guide)

**Season**:
One numbered part of a series, holding its episodes in order. On screen it is always "Season", including counts such as "2 seasons".
_Avoid_: Series (for this meaning), series 2

**Watched**:
A movie or episode the Profile has played past 93% of its length, or marked watched by hand, in any of its Versions.
_Avoid_: Finished, seen, completed

**In Progress**:
A movie or episode the Profile has started but not yet Watched.
_Avoid_: Half-watched, partially watched, resumable

**Unwatched**:
A movie or episode the Profile has never played, or has marked unwatched. Episodes are never assumed Watched just because a later one was.

**Up Next**:
For a series, the episode after the one the Profile most recently Watched: the next in its season, or the first of the following season. A series with no later episode has no Up Next until the provider adds one.
_Avoid_: Next episode (when meaning this specific concept)

**Next Episode**:
The episode after the one playing now, in season then episode order: the player offers it as the credits start and plays it on its own unless told not to. Unlike Up Next it ignores what the Profile last Watched, so rewatching episode 2 of a series whose episode 5 is Watched offers episode 3. The card that offers it reads "Up Next" on screen.
_Avoid_: Autoplay episode, following episode

**New Episode**:
An Up Next that the provider added after the Profile had Watched every episode the app knew of in that series. It stays a New Episode until the Profile starts it. A series the Profile is still part-way through never has one, however many episodes the provider adds.
_Avoid_: New release, fresh episode

**Continue Watching**:
The row of things a Profile can pick back up: each In Progress movie, plus one card per series showing its In Progress episode or else its Up Next. A series with a New Episode comes back to it, placed by when the episode was noticed.
_Avoid_: Resume list, recently watched

## Curation

**Favourite**:
A channel, movie or series a Profile has marked to keep close at hand. Belongs to one Profile.
_Avoid_: My List, bookmark, saved

**Recent Search**:
A search a Profile made and then opened a result from. Each Profile keeps its last ten.
_Avoid_: Search history

**Home**:
The screen the app always opens on, gathering the Live Preview, Continue Watching, what's Recently Added and the Profile's Favourites.
_Avoid_: Dashboard, start page

**Recently Added**:
The movies and shows the providers added most recently, newest first, whoever is watching. It says when a title reached the provider, not when it was released.
_Avoid_: New, new releases

**Live Preview**:
A muted, playing tile on Home of the live channel the Profile last watched on this device, shown only when live TV was the Profile's most recent playback. Settling on a channel full screen counts as watching it; zapping past one or seeing it in the guide's preview does not. A channel that has since left the channel list still shows, as a reminder that can't play; a channel the viewer hid does not.
_Avoid_: Last channel, boot channel

**Home Row**:
One of Home's rows of titles or channels a Profile can move or hide: Continue Watching, Because you watched, Recently Added shows, Recently Added movies, Favourite shows, Favourite movies and Favourite channels. Each Profile keeps its own order and hidden rows, which follow it to its other devices. The Live Preview is not a Home Row: it can be turned off, but always comes first.
_Avoid_: Shelf, section, rail

## Recordings

**Recording**:
A programme captured from a channel to storage on this device, a NAS or USB, or booked to be. It can be booked, in progress, recorded or failed; a failed one captured nothing.
_Avoid_: DVR, PVR, capture

**Cut short**:
A recorded Recording that stopped by itself before its end, and says why.
_Avoid_: partial, incomplete, failed

**Reminder**:
A prompt when a programme starts, which can also switch to its channel. It never captures anything.
_Avoid_: Alert, notification

**Series link**:
Books every airing of a show on one channel as the guide reveals them, including before the next airing is known.
_Avoid_: Series rule, season pass

## On screen

**Highlight**:
The one thing on screen the remote is currently on: what OK would press. There is always exactly one.
_Avoid_: Focus, selection, cursor

**Rail**:
The left-hand navigation: Home, Live TV, Movies, Shows and Recordings, with Search, the Profile and Settings set apart from them. It shows icons until it has the Highlight, then opens with a label for each.
_Avoid_: Sidebar (that's the Categories Sidebar), menu, nav

**Settings**:
Where the box is set up: its sections down the left, and the highlighted section's settings beside them, so nothing has to be opened to see what's inside.
_Avoid_: Preferences, options (that's an item's options), menu

**Crash Report**:
What Settee sends to Sentry by itself about one crash, freeze, failure of one of Settee's own services or Slow Journey, and only from a device whose viewer has turned crash reports on: which version, which device, which screen, and where in Settee's own code it failed. Never anything about the viewer, their Profiles, their Provider or what they watch.
_Avoid_: Telemetry, analytics, diagnostics (that's the report Settings → Diagnostics saves to the viewer's own NAS)

**Slow Journey**:
One of a few things Settee times (opening the app, opening Movies or Shows, a Catalogue Refresh, starting playback) when Settee's own part of it, leaving out time spent waiting on the Provider or the network, took clearly longer than its threshold. Only a slow one is sent, at most once a launch, and only from a device with Crash Reports on.
_Avoid_: Performance trace, timing, metrics

**Error Report**:
One error that could be Settee's fault, as the viewer saw it, sent to Sentry: its kind and detail line, the screen, the Provider's kind and who sent it, never the Provider's address, name or login. With Crash Reports on, Settee sends it as the error is shown, once per failure, and the detail line ends "Sent to Settee". With them off, the error's Report asks whether to send it: Send sends that one and leaves them off, Always send turns them on.
_Avoid_: Feedback, bug report, Crash Report (that one is a crash or freeze)

**Privacy**:
The Settings section where a viewer turns Crash Reports on or off for this device, and sees what one contains.
_Avoid_: Data, permissions

**Side Panel**:
The panel down the right of the screen, over what's there, for a choice, an item's details, or a question before acting. Back closes it.
_Avoid_: Dialog, popup, modal

**Categories Sidebar**:
The list of a tab's categories on Live TV, Movies and Shows. It stays tucked away until Left from the leftmost item opens it on the current category. The channel manager shows it always open.
_Avoid_: Sidebar on its own, filter, chips

**Movie Listings**:
The Movies tab: a Hero over rows of covers of every Movie there is to watch, where the viewer picks one to open. With Series Listings, these are the Listings.
_Avoid_: Listings Page, browse, catalogue screen, grid (that's one category's covers)

**Series Listings**:
The tab labelled Shows: a Hero over rows of covers of every Series there is to watch, where the viewer picks one to open.
_Avoid_: TV Series Listing, Shows tab, Listings Page

**Movie Details**:
What a Movie opens on, from the Listings, Home or Search: its Hero, then its people and More like this. With Series Details, these are the Details.
_Avoid_: Details Page, detail screen, info page, a film's page

**Series Details**:
What a Series opens on: its Hero, then its season pills and the Episode List. Episodes have no page of their own.
_Avoid_: Details Page, series listing (that's Series Listings), a show's page

**Episode List**:
The list of one Season's episodes on Series Details, carrying on into the next Season.
_Avoid_: Episode Listing (Listings are whole tabs)

**Hero**:
The top of the Listings and the Details: the backdrop, with the title, status and synopsis over it. On the Listings it shows whichever title has the Highlight.
_Avoid_: Backdrop (that's the picture itself), banner, billboard, spotlight

**Title Logo**:
A Movie's or Series' name drawn as artwork, from TMDB, shown in a Hero in place of the written title.
_Avoid_: Logo on its own (channels have logos), wordmark, clear logo
