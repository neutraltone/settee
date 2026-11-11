/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.model.Movie
import app.opentv.data.model.Series

/**
 * What the ranking reads from a title, which [Movie] and [Series] each hold themselves, and [now],
 * which decides how new a title counts as.
 */
internal class Ranking<T>(val genre: (T) -> String?, val rating: (T) -> Double?, val added: (T) -> Long, val now: Long)

internal fun movieRanking(now: Long) = Ranking(Movie::genre, Movie::rating, Movie::addedMillis, now)

internal fun seriesRanking(now: Long) = Ranking(Series::genre, Series::rating, Series::addedMillis, now)

/** Splits a provider genre string ("Action, Thriller" / "Action|Thriller") into clean genres. */
internal fun splitGenres(raw: String?): List<String> = raw?.split(',', '|')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.distinct()
    .orEmpty()

/**
 * How good a pick [title] is: its rating, plus up to [NEW_BONUS] for having been added
 * recently, shrinking to nothing over [NEW_FOR_MILLIS]. A rating of [UNTRUSTED_RATING] or more counts
 * as [AVERAGE_RATING], as does none (or 0): providers pass on TMDB's average, and their 10s are
 * nearly all titles with a handful of votes. Weighing ratings by their votes would be better, but no
 * panel sends a vote count, and asking TMDB for one per title is the bulk lookup [MovieReleaseDates]
 * explains we don't make.
 */
internal fun <T> Ranking<T>.score(title: T): Double {
    val rating = rating(title)?.takeIf { it > 0.0 && it < UNTRUSTED_RATING } ?: AVERAGE_RATING
    val newness = (1.0 - (now - added(title)).toDouble() / NEW_FOR_MILLIS).coerceIn(0.0, 1.0)
    return rating + NEW_BONUS * newness
}

/** Best [score] first; titles scoring the same go newest first. */
internal val <T> Ranking<T>.byScore: Comparator<T>
    get() = compareByDescending<T> { score(it) }.thenByDescending { added(it) }

private const val UNTRUSTED_RATING = 9.5
private const val AVERAGE_RATING = 6.0
private const val NEW_BONUS = 2.0
private const val NEW_FOR_MILLIS = 365 * 86_400_000.0

/** A genre gets a row once it has this many titles the Profile hasn't started. */
internal const val GENRE_ROW_MIN_TITLES = 8

/** The most titles a genre row holds. */
internal const val GENRE_ROW_TITLES = 20

/**
 * For you's genre rows: one for every genre with at least [GENRE_ROW_MIN_TITLES] titles [leftOut]
 * doesn't reject, each holding its best [GENRE_ROW_TITLES] by [score]. The genres of [recent] (the
 * titles the Profile played last, newest first; each title's genres in the provider's order) come
 * first, then the others, the most titles first. A title is in a row for every genre it lists.
 */
internal fun <T> genreRowsFrom(
    all: List<T>,
    ranking: Ranking<T>,
    recent: List<T>,
    leftOut: (T) -> Boolean,
): List<GenreGroup<T>> {
    // Scored once each: sorting a 10,000-film genre would otherwise score its films again on every
    // comparison, which a Fire TV Stick feels.
    val buckets = HashMap<String, MutableList<Scored<T>>>()
    for (title in all) {
        if (leftOut(title)) continue
        val scored = Scored(title, ranking.score(title), ranking.added(title))
        for (genre in splitGenres(ranking.genre(title))) buckets.getOrPut(genre) { mutableListOf() }.add(scored)
    }
    val shown = buckets.filterValues { it.size >= GENRE_ROW_MIN_TITLES }
    val watchedFirst = recent.asSequence().flatMap { splitGenres(ranking.genre(it)) }.filter { it in shown }
    val others = shown.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<Scored<T>>>> { it.value.size }.thenBy { it.key })
        .map { it.key }
    return (watchedFirst + others).distinct()
        .map { genre ->
            GenreGroup(genre, shown.getValue(genre).sortedWith(BEST_FIRST).take(GENRE_ROW_TITLES).map { it.title })
        }
        .toList()
}

private class Scored<T>(val title: T, val score: Double, val added: Long)

private val BEST_FIRST = compareByDescending<Scored<*>> { it.score }.thenByDescending { it.added }

/**
 * "Recommended for you": the pure genre-affinity ranking pass. Tallies the genres of the titles
 * [tallied] picks, then scores the ones [leftOut] doesn't reject by how strongly they match, ties
 * broken by [score]. With no usable tally, or no matches, it falls back to the best [score]s.
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
    val byScore = ranking.byScore

    fun topRatedFallback(): List<T> = candidates.sortedWith(byScore).take(limit)

    if (affinity.isEmpty()) return topRatedFallback()

    val ranked = candidates
        .map { it to splitGenres(ranking.genre(it)).sumOf { genre -> affinity[genre] ?: 0 } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy(byScore) { it.first })
        .map { it.first }
        .take(limit)

    return ranked.ifEmpty { topRatedFallback() }
}
