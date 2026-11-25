/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.TmdbHit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A film TMDB knows and the Provider doesn't carry, as search shows it before it's opened and kept. */
data class AddonTitle(
    val tmdbId: String,
    /** What add-ons are asked by, and the kept row's provider id. */
    val imdbId: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
)

/**
 * Titles the Provider doesn't carry (ADR 0011): found through TMDB once an add-on is set up, and kept as
 * rows of the built-in Add-ons source once opened, keyed by IMDb id, so from then on watch state,
 * Favourites, sync and Versions treat them as any other title. Films only so far.
 */
class AddonTitles(
    private val db: OpenTvDatabase,
    private val tmdb: TmdbClient,
    private val dispatchers: AppDispatchers,
    /** Whether the viewer has any add-on: without one, nothing found here could play. */
    private val hasAddons: () -> Boolean,
) {
    // Two films kept at once mustn't each make an Add-ons source.
    private val keeping = Mutex()

    /**
     * TMDB's films for [query] that this device has no row for, by TMDB id, each with the IMDb id add-ons
     * are asked by; one without is left out, since no add-on could be asked about it. Empty without an
     * add-on or a TMDB Key, and when TMDB doesn't answer: the Provider's results are still there.
     */
    suspend fun search(query: String): List<AddonTitle> {
        if (!hasAddons() || !tmdb.isConfigured()) return emptyList()
        val hits = withContext(dispatchers.io) { tmdb.searchTitles(query) }.getOrNull().orEmpty()
            .filter { it.isMovie }
            .take(MAX_RESULTS)
        val stored = withContext(dispatchers.io) {
            hits.takeIf { it.isNotEmpty() }
                ?.let { found -> db.movies().picksWithTmdbIds(found.map { it.tmdbId }) }
                .orEmpty()
                .mapNotNullTo(HashSet()) { it.tmdbId }
        }
        val wanted = hits.filter { it.tmdbId !in stored }
        // Side by side: one request per film, and the viewer is waiting on the slowest.
        return coroutineScope {
            wanted.map { hit -> async(dispatchers.io) { imdbIdOf(hit)?.let { hit.toTitle(it) } } }.awaitAll()
        }.filterNotNull()
    }

    /**
     * [title] as a film of the Add-ons source, stored the first time it's opened and found by its IMDb
     * id after that. It has no stream URL: it plays through the add-on stream picker. Its cast and the
     * rest come from TMDB as its page opens, as any film's do.
     */
    suspend fun keepFilm(title: AddonTitle): Movie = withContext(dispatchers.io) {
        keeping.withLock {
            val source = addonsSource()
            db.movies().byProviderId(source.id, title.imdbId)?.let { return@withLock it }
            db.movies().upsertAll(listOf(title.toMovie(source.id)))
            checkNotNull(db.movies().byProviderId(source.id, title.imdbId))
        }
    }

    private suspend fun addonsSource(): Source = db.sources().addons() ?: run {
        val id = db.sources().insert(Source(name = Source.ADDONS_NAME, kind = SourceKind.ADDONS, url = ""))
        checkNotNull(db.sources().byId(id))
    }

    private fun imdbIdOf(hit: TmdbHit): String? =
        tmdb.imdbId(hit.name, hit.year, isMovie = true, tmdbId = hit.tmdbId).getOrNull()

    private fun TmdbHit.toTitle(imdbId: String) =
        AddonTitle(tmdbId, imdbId, name, year, posterUrl, backdropUrl, overview)

    private fun AddonTitle.toMovie(sourceId: Long) = Movie(
        sourceId = sourceId,
        streamId = imdbId,
        name = name,
        categoryId = null,
        posterUrl = posterUrl,
        rating = null,
        year = year,
        plot = overview,
        durationSeconds = null,
        containerExtension = null,
        streamUrl = "",
        // Never Recently Added: the viewer found it, the Provider didn't add it.
        addedMillis = 0,
        backdropUrl = backdropUrl,
        tmdbId = tmdbId,
    )

    private companion object {
        /** A screen's worth: each costs a request for its IMDb id. */
        const val MAX_RESULTS = 10
    }
}

/** Whether [movie] was kept from an add-on: it has no stream of its own, and plays through the add-on picker. */
suspend fun SourceDao.keptFromAddons(movie: Movie): Boolean = addons()?.id == movie.sourceId
