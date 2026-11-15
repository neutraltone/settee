/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

/**
 * [tmdbId] when it names a title; null for the 0 or nothing panels send for a title they don't know.
 * Taken at face value, 0 would make every such title one, and send TMDB looking for title 0.
 */
fun knownTmdbId(tmdbId: String?): String? = tmdbId?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

/**
 * The TMDB id to keep when merging [own] with [other]: [own] when it names a title, kept as it was,
 * else [other], else [own] as it was. A plain `own ?: other` would let a panel's 0 block a real id.
 */
fun tmdbIdOr(own: String?, other: String?): String? = own.takeIf { knownTmdbId(it) != null } ?: other ?: own
