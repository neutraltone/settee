# Favourites belong to a Profile, not the device

Upstream OpenTV stored Favourites as a flag on each channel, movie and series row, so everyone on a device shared one list, while watch progress was already per Profile. We made Favourites per Profile (channels, movies and series alike) so each viewer's Home and Favourites rows are their own, and sync matches them by Profile name exactly as watch progress does. Hidden channels and channel order stay device-wide: they tidy the provider's catalogue rather than express one viewer's taste.

Existing device-wide Favourites were not migrated: the app was rebranded and installed fresh, so there were none worth keeping.
