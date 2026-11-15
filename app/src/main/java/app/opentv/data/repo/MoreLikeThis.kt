/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.TitlePick
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.knownTmdbId
import app.opentv.data.model.recommendedTmdbIds

/** What a title's More like this is chosen for: a film or a show, as far as choosing reads it. */
internal data class LikeThis(
    val id: Long,
    val sourceId: Long,
    val categoryId: String?,
    val genre: String?,
    val tmdbId: String?,
    /** TMDB's recommendations, in its order; empty when they don't count (no key, no TMDB id). */
    val recommended: List<String>,
)

internal fun Movie.likeThis(withTmdb: Boolean) = LikeThis(
    id = id,
    sourceId = sourceId,
    categoryId = categoryId,
    genre = genre,
    tmdbId = knownTmdbId(tmdbId),
    recommended = if (withTmdb && knownTmdbId(tmdbId) != null) recommendedTmdbIds else emptyList(),
)

internal fun Series.likeThis(withTmdb: Boolean) = LikeThis(
    id = id,
    sourceId = sourceId,
    categoryId = categoryId,
    genre = genre,
    tmdbId = knownTmdbId(tmdbId),
    recommended = if (withTmdb && knownTmdbId(tmdbId) != null) recommendedTmdbIds else emptyList(),
)

/**
 * Chooses a film's or a show's More like this from one kind's table, read as [TitlePick]s so a big
 * provider's thousands of dramas aren't loaded whole on a Fire TV Stick.
 *
 * TMDB's recommendations come first, the title's own provider's before another's (another's may not
 * play the same), then in TMDB's order; genre matches fill the rest, so a title TMDB knows little
 * like still gets a full row. One cover per title, matched by TMDB id (ADR 0007), and never the
 * title itself or another Version of it.
 */
internal class MoreLikeThis(
    private val sharingGenre: suspend (genre: String, excludeId: Long) -> List<TitlePick>,
    private val inCategory: suspend (sourceId: Long, categoryId: String?, excludeId: Long) -> List<TitlePick>,
    private val withTmdbIds: suspend (List<String>) -> List<TitlePick>,
) {
    /** The ids of [title]'s suggestions, best first, at most [limit]. */
    suspend fun idsFor(title: LikeThis, limit: Int, now: Long): List<Long> {
        val picked = LinkedHashMap<Long, TitlePick>()
        val titles = HashSet<String>().apply { title.tmdbId?.let(::add) }
        fun take(pick: TitlePick) {
            if (picked.size >= limit || pick.id == title.id || pick.id in picked) return
            val tmdbId = knownTmdbId(pick.tmdbId)
            if (tmdbId != null && !titles.add(tmdbId)) return
            picked[pick.id] = pick
        }
        recommended(title).forEach(::take)
        if (picked.size < limit) sharingGenres(title, now).forEach(::take)
        return picked.keys.toList()
    }

    private suspend fun recommended(title: LikeThis): List<TitlePick> {
        val ids = title.recommended.filter { it != title.tmdbId }.distinct()
        if (ids.isEmpty()) return emptyList()
        val place = ids.withIndex().associate { (index, id) -> id to index }
        return withTmdbIds(ids).sortedWith(
            compareByDescending<TitlePick> { it.sourceId == title.sourceId }
                .thenBy { place[knownTmdbId(it.tmdbId)] ?: Int.MAX_VALUE }
                .thenBy { it.id },
        )
    }

    /**
     * Ranked by the rules For you uses ([score]), after the two that matter more for "like this": its
     * own provider's first, then those sharing the most of its genres. With no genre, its category's.
     */
    private suspend fun sharingGenres(title: LikeThis, now: Long): List<TitlePick> {
        val genres = splitGenres(title.genre)
        val candidates = if (genres.isEmpty()) {
            inCategory(title.sourceId, title.categoryId, title.id)
        } else {
            genres.flatMap { sharingGenre(it, title.id) }.distinctBy { it.id }
        }
        val ranking = Ranking(TitlePick::genre, TitlePick::rating, TitlePick::addedMillis, now)
        val shared = candidates.associate { it.id to splitGenres(it.genre).count(genres::contains) }
        return candidates
            .filter { genres.isEmpty() || shared.getValue(it.id) > 0 }
            .sortedWith(
                compareByDescending<TitlePick> { it.sourceId == title.sourceId }
                    .thenByDescending { shared.getValue(it.id) }
                    .then(ranking.byScore),
            )
    }
}
