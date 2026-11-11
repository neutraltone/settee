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
internal suspend fun MovieDao.save(sourceId: Long, list: XtreamApi.CatalogueList<Movie>): Int {
    var saved = 0
    inTransaction {
        list.forEachBatch { batch ->
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
internal suspend fun SeriesDao.save(sourceId: Long, list: XtreamApi.CatalogueList<Series>): SavedSeries {
    var saved = 0
    val moved = mutableListOf<Series>()
    inTransaction {
        list.forEachBatch { batch ->
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
        director = director ?: old.director,
        releaseDate = releaseDate ?: old.releaseDate,
    )
}

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
    )
}
