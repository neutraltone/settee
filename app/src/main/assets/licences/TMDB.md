# TMDB's logo

Settings › TMDB and About show TMDB's approved "Alt short" logo (blue), which TMDB offers to apps that use
its API on [Logos & Attribution](https://www.themoviedb.org/about/logos-attribution). It's not the GPL's:
TMDB owns the mark, and Settee shows it only to attribute TMDB, as section 3 of the
[TMDB API Terms of Use](https://www.themoviedb.org/api-terms-of-use) requires of every app using the API.
Those terms grant no other right in it (section 4), and ask that it stay less prominent than the app's own
marks and not suggest that TMDB endorses the app. Beside it, Settee says it uses the TMDB API but is not
endorsed or certified by TMDB.

| File | Source | SHA-256 of the source |
|---|---|---|
| `res/drawable/tmdb_logo.xml` | `blue_short.svg` from that page, downloaded 2026-11-12 | `8e7b30f73a4020692ccca9c88bafe5dcb6f8a62a4c6bc55cd9ba82bb2cd95f6c` |

The drawable is the SVG's one path and gradient, converted as they are: nothing redrawn, recoloured or
cropped. To update it, convert TMDB's current file the same way and change this row.
