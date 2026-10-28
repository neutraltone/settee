# Favourites belong to a Profile, not the device

Upstream OpenTV stored Favourites as a flag on each channel, movie and series row, so everyone on a device shared one list, while watch progress was already per Profile. We made Favourites per Profile (channels, movies and series alike) so each viewer's Home and Favourites rows are their own, and sync matches them by Profile name exactly as watch progress does. Hidden channels and channel order stay device-wide: they tidy the provider's catalogue rather than express one viewer's taste.

Existing device-wide Favourites were not migrated: the app was rebranded and installed fresh, so there were none worth keeping.

A Favourite is identified by its source's name and the provider's own stream or series id, not by stream URL as first planned: Xtream stream URLs embed the account password and the live format, so changing either would silently drop every Favourite, and provider series ids alone repeat across providers. The cost is that renaming a source drops its Favourites, which is a deliberate act where a password change is not.
