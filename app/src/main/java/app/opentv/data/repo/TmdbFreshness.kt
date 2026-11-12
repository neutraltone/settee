/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

/**
 * How long TMDB's answer about a title stands before it's asked again: posters, synopses and cast
 * change, and a title TMDB had no match for may have one since. Six months keeps a large catalogue's
 * calls down to a trickle while nothing goes more than half a year out of date.
 */
const val TMDB_REFRESH_MILLIS = 183L * 24 * 60 * 60 * 1000

/** Whether a title TMDB last answered about at [answeredAt] (null: never) should be asked about at [now]. */
fun tmdbDue(answeredAt: Long?, now: Long): Boolean = answeredAt == null || answeredAt < now - TMDB_REFRESH_MILLIS
