/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Movie
import app.opentv.data.model.Series

/** What the ranking reads from a title, which [Movie] and [Series] each hold themselves. */
internal class Ranking<T>(val genre: (T) -> String?, val rating: (T) -> Double?, val added: (T) -> Long)

internal val MOVIE_RANKING = Ranking(Movie::genre, Movie::rating, Movie::addedMillis)
internal val SERIES_RANKING = Ranking(Series::genre, Series::rating, Series::addedMillis)

/** Splits a provider genre string ("Action, Thriller" / "Action|Thriller") into clean genres. */
internal fun splitGenres(raw: String?): List<String> = raw?.split(',', '|')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()
    .orEmpty()

/**
 * "Recommended for you": the pure genre-affinity ranking pass. Tallies the genres of the titles
 * [tallied] picks, then scores the ones [leftOut] doesn't reject by how strongly they match, ties
 * broken by rating, then recency. With no usable tally, or no matches, it falls back to top-rated,
 * then recently-added.
 */
internal fun <T> recommendFrom(
    all: List<T>,
    ranking: Ranking<T>,
    limit: Int,
    tallied: (T) -> Boolean,
    leftOut: (T) -> Boolean,
): List<T> {
    val affinity = HashMap<String, Int>()
    for (title in all) {
        if (!tallied(title)) continue
        for (genre in splitGenres(ranking.genre(title))) affinity[genre] = (affinity[genre] ?: 0) + 1
    }

    val candidates = all.filterNot(leftOut)

    fun topRatedFallback(): List<T> = candidates
        .sortedWith(compareByDescending<T> { ranking.rating(it) ?: -1.0 }.thenByDescending { ranking.added(it) })
        .take(limit)

    if (affinity.isEmpty()) return topRatedFallback()

    val ranked = candidates
        .map { it to splitGenres(ranking.genre(it)).sumOf { genre -> affinity[genre] ?: 0 } }
        .filter { it.second > 0 }
        .sortedWith(
            compareByDescending<Pair<T, Int>> { it.second }
                .thenByDescending { ranking.rating(it.first) ?: -1.0 }
                .thenByDescending { ranking.added(it.first) },
        )
        .map { it.first }
        .take(limit)

    return ranked.ifEmpty { topRatedFallback() }
}
