# Settings can belong to a Profile, not just the device

Every setting used to be device-wide (`AppSettings`), but some express one viewer's taste rather than how the box is set up: the first is the Subtitle Preference, which should follow a Profile from one title to the next and across devices, as watch progress and Favourites do (ADR 0001). We added a per-Profile settings store for these, synced per Profile name with newest-wins, and left settings about the device itself (theme, providers, recording, sync) in `AppSettings`.

The store holds one row per setting, keyed by Profile and setting name, each with the time it was last changed, rather than one row per Profile with a column per setting. Moving another setting to a Profile then needs no schema change, which after 1.0 would mean a hand-written migration each time, and sync can merge every setting the same way, newest-wins per setting. The cost is that values are stored as text and read through a typed wrapper.

An unset setting follows the device's default and is never written or synced just because a Profile was created. A default stored at creation, such as the device language, would otherwise be carried to a box set up differently, or overwrite a choice the viewer actually made there.
