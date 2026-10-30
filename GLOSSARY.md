# OpenTV

An IPTV player for TV boxes and phones: it plays live channels, movies and series from a provider the viewer already pays for, and remembers what each viewer has watched and liked.

## Viewers

**Profile**:
One viewer on a device: a name with its own Favourites, watch progress, Recent Searches and Subtitle Preference. No password, no account.
_Avoid_: User, account

**Who's watching?**:
The question the app asks when it starts on a device with more than one Profile: the viewer picks which Profile they are before Home opens.
_Avoid_: Profile picker, profile chooser, profile select

**Subtitle Preference**:
Whether a Profile wants subtitles shown, and in which language. Belongs to one Profile and follows it from one title to the next, whatever kind of title it is.
_Avoid_: Captions setting, CC, subtitle default

## Live TV

**Channel**:
A live station the viewer can watch, however many qualities or providers carry it. Rows with the same name and region fold into one Channel. A row with no region joins the one regional Channel of its name, if there's only one.
_Avoid_: Stream, row

**Quality**:
One provider's stream of a Channel, such as HD, FHD or 4K. A Channel's Qualities are the same wherever it's shown.
_Avoid_: Variant (in user-facing text), feed

**Parental Lock**:
Categories hidden on this device, for every Profile on it, from everywhere viewers browse and play, but not from the management screens. A PIN guards the setting; entering it shows them until the app closes.
_Avoid_: Hidden channels (that's the channel manager's per-channel hide), parental controls

## Watch progress

**Watched**:
A movie or episode the Profile has played past 93% of its length, or marked watched by hand.
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
The screen the app always opens on, gathering the Live Preview, Continue Watching and the Profile's Favourites.
_Avoid_: Dashboard, start page

**Live Preview**:
A muted, playing tile on Home of the live channel the Profile last watched on this device, shown only when live TV was the Profile's most recent playback. Settling on a channel full screen counts as watching it; zapping past one or seeing it in the guide's preview does not. A channel that has since left the channel list still shows, as a reminder that can't play; a channel the viewer hid does not.
_Avoid_: Last channel, boot channel
