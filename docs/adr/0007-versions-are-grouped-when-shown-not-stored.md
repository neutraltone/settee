# A title's Versions are grouped when shown, not stored as one title

A provider lists each copy of a film or show as a row of its own: its Netflix copy, its Blu-ray copy, its 4K copy. On one provider's catalogue, grouping these Versions by TMDB id turns 29,744 film rows into about 22,900 titles and 9,263 series rows into about 7,000, and most groups are copies of the same Quality on different shelves rather than different Qualities. We show one title per group everywhere, but nothing stored changes: Favourites and resume points stay on the Version they were made on, keyed by source name and provider id as ADR 0004 requires, and the grouping is worked out each time titles are read. A title is a Favourite when any of its Versions is, and an episode is Watched when any Version of it is, matched by season and episode number.

The obvious design is a titles table that Favourites and progress point at. We rejected it because it would change what sync carries, so a device without grouping would see Favourites it can't place, and because after 1.0 every change to it needs a migration that keeps everyone's Favourites. Keeping storage per Version also means a wrong grouping, which a provider's bad TMDB id can cause, loses nothing: when the grouping rule changes, every star and resume point is still on the copy it was made on.

Versions group by TMDB id, falling back to the cleaned name and year only for a row without one, and two rows whose TMDB ids differ never group, even with the same name.

## Consequences

- Every list of films or shows pays for the grouping when it's read, so it has to stay off the main thread and within the Stick's frame budget (`docs/PERFORMANCE.md`).
- A resume point doesn't carry from one Version to another, since copies may not line up. A title's status and its Continue Watching card come from the Version played most recently.
- Unstarring a title clears the star on every Version of it, and an older device that doesn't group still shows the right copies starred.
