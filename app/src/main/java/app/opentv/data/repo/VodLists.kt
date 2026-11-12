/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.MovieDao
import app.opentv.data.db.SeriesDao
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.remote.TmdbMeta
import app.opentv.data.remote.XtreamApi

// How a downloaded movie or series list is saved.
//
// Each list is read off its download a batch at a time (see XtreamApi.CatalogueList) but saved in one
// transaction: a transaction a batch would reload every screen watching movies a hundred times on a
// big list, and a list cut off halfway saves nothing, as it always has.
//
// Fresh rows carry no local id, and an upsert without one hits the unique (source, provider id) index
// and then updates nothing, so a title's last_modified would never move after its first sync.
// Matching each to the row it replaces fixes that, keeps the local id that resume points hang off,
// and keeps what detail opens filled in. The rows are looked up a batch at a
// time too, since loading every stored title to match against costs as much memory as the list.

/** Saves [list] as [sourceId]'s movies, returning how many there were. */
internal suspend fun MovieDao.save(
    sourceId: Long,
    list: XtreamApi.CatalogueList<Movie>,
    progress: (CatalogRepository.SaveProgress) -> Unit = {},
): Int {
    var saved = 0
    inTransaction {
        list.forEachBatch({ progress(CatalogRepository.SaveProgress(it, saved)) }) { batch ->
            val stored = byProviderIds(sourceId, batch.map { it.streamId }).associateBy { it.streamId }
            upsertAll(batch.map { it.over(stored[it.streamId]) })
            saved += batch.size
        }
    }
    return saved
}

/** How many series a list had, and the ones the provider updated since they were last saved. */
internal data class SavedSeries(val count: Int, val moved: List<Series>)

/** Saves [list] as [sourceId]'s series. */
internal suspend fun SeriesDao.save(
    sourceId: Long,
    list: XtreamApi.CatalogueList<Series>,
    progress: (CatalogRepository.SaveProgress) -> Unit = {},
): SavedSeries {
    var saved = 0
    val moved = mutableListOf<Series>()
    inTransaction {
        list.forEachBatch({ progress(CatalogRepository.SaveProgress(it, saved)) }) { batch ->
            val stored = byProviderIds(sourceId, batch.map { it.seriesId }).associateBy { it.seriesId }
            val merged = batch.map { it.over(stored[it.seriesId]) }
            upsertAll(merged)
            saved += merged.size
            // A show seen for the first time has nobody caught up on it, so only ones that moved.
            merged.filterTo(moved) { it.addedMillis > (stored[it.seriesId]?.addedMillis ?: Long.MAX_VALUE) }
        }
    }
    return SavedSeries(saved, moved)
}

/** This freshly fetched movie in place of [old], keeping whatever it lacks that [old] had. */
internal fun Movie.over(old: Movie?): Movie = if (old == null) {
    this
} else {
    copy(
        id = old.id,
        posterUrl = posterUrl ?: old.posterUrl,
        rating = rating ?: old.rating,
        year = year ?: old.year,
        plot = plot ?: old.plot,
        durationSeconds = durationSeconds ?: old.durationSeconds,
        backdropUrl = backdropUrl ?: old.backdropUrl,
        cast = cast ?: old.cast,
        genre = genre ?: old.genre,
        tmdbId = tmdbId ?: old.tmdbId,
        releaseDate = releaseDate ?: old.releaseDate,
    ).keepingLookUpsOf(old)
}

/** [old]'s people and looked-up markers, which a list refresh knows nothing of. */
private fun Movie.keepingLookUpsOf(old: Movie): Movie = copy(
    // TMDB's people outrank the provider's, which would otherwise come back with every refresh.
    director = if (old.tmdbLookedUp) old.director ?: director else director ?: old.director,
    writer = old.writer ?: writer,
    studio = old.studio ?: studio,
    detailsLookedUp = detailsLookedUp || old.detailsLookedUp,
    tmdbLookedUp = tmdbLookedUp || old.tmdbLookedUp,
    tmdbAnsweredAt = newer(tmdbAnsweredAt, old.tmdbAnsweredAt),
)

/** The later of two times, either of which may be missing. */
internal fun newer(a: Long?, b: Long?): Long? = if (a == null || b == null) a ?: b else maxOf(a, b)

/** This freshly fetched series in place of [old]; see the [Movie] version. */
internal fun Series.over(old: Series?): Series = if (old == null) {
    this
} else {
    copy(
        id = old.id,
        posterUrl = posterUrl ?: old.posterUrl,
        rating = rating ?: old.rating,
        year = year ?: old.year,
        plot = plot ?: old.plot,
        backdropUrl = backdropUrl ?: old.backdropUrl,
        cast = cast ?: old.cast,
        genre = genre ?: old.genre,
        tmdbId = tmdbId ?: old.tmdbId,
        tmdbAnsweredAt = newer(tmdbAnsweredAt, old.tmdbAnsweredAt),
    )
}

/**
 * This movie with its empty details filled from the provider's `get_vod_info` [info], and marked
 * looked up. Shared by opening a film and the background pass, so neither leaves out a field the
 * other fills. A blank genre (the provider had none before) takes one if [info] has it now.
 */
internal fun Movie.filledFrom(info: XtreamApi.VodInfo): Movie = copy(
    backdropUrl = backdropUrl ?: info.backdropUrl,
    cast = cast ?: info.cast,
    director = director ?: info.director,
    genre = genre?.ifBlank { null } ?: info.genre?.ifBlank { null } ?: genre,
    tmdbId = tmdbId ?: info.tmdbId,
    plot = plot ?: info.plot,
    rating = rating ?: info.rating,
    year = year ?: info.year,
    durationSeconds = durationSeconds ?: info.durationSeconds,
    releaseDate = releaseDate ?: info.releaseDate,
    detailsLookedUp = true,
)

/**
 * This movie with what TMDB said about it ([meta], null for no match) [at] that time, and marked
 * TMDB-looked-up. TMDB fills what's empty, and its directors, writers and studios replace whatever
 * was there: a provider's director is only the first name of its crew, and providers have no writers
 * or studios. The same when TMDB is asked again six months on, so the provider's values still win.
 */
internal fun Movie.withTmdb(meta: TmdbMeta?, at: Long): Movie = if (meta == null) {
    copy(tmdbLookedUp = true, tmdbAnsweredAt = at)
} else {
    copy(
        posterUrl = posterUrl ?: meta.posterUrl,
        backdropUrl = backdropUrl ?: meta.backdropUrl,
        plot = plot ?: meta.overview,
        cast = cast ?: meta.cast,
        genre = genre?.ifBlank { null } ?: meta.genre ?: genre,
        tmdbId = tmdbId ?: meta.tmdbId,
        rating = rating ?: meta.rating,
        year = year ?: meta.year,
        releaseDate = releaseDate ?: meta.releaseDate,
        tmdbLookedUp = true,
        tmdbAnsweredAt = at,
    ).withPeopleFrom(meta)
}

private fun Movie.withPeopleFrom(meta: TmdbMeta): Movie = copy(
    director = meta.director ?: director,
    writer = meta.writers ?: writer,
    studio = meta.studios ?: studio,
)

/**
 * This series with what TMDB said about it ([meta], null for no match) [at] that time. TMDB only
 * fills what's empty, the provider's values winning, as on a first look-up; TMDB has no director for TV.
 */
internal fun Series.withTmdb(meta: TmdbMeta?, at: Long): Series = if (meta == null) {
    copy(tmdbAnsweredAt = at)
} else {
    copy(
        posterUrl = posterUrl ?: meta.posterUrl,
        backdropUrl = backdropUrl ?: meta.backdropUrl,
        plot = plot ?: meta.overview,
        cast = cast ?: meta.cast,
        genre = genre ?: meta.genre,
        tmdbId = tmdbId ?: meta.tmdbId,
        rating = rating ?: meta.rating,
        year = year ?: meta.year,
        tmdbAnsweredAt = at,
    )
}

/** This series with its empty details filled from the provider's `get_series_info` [info]. */
internal fun Series.filledFrom(info: XtreamApi.SeriesInfo): Series = copy(
    backdropUrl = backdropUrl ?: info.backdropUrl,
    cast = cast ?: info.cast,
    genre = genre ?: info.genre,
    tmdbId = tmdbId ?: info.tmdbId,
    plot = plot ?: info.plot,
    rating = rating ?: info.rating,
    year = year ?: info.year,
)
