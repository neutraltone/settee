/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppDispatchers
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.SourceDao
import app.opentv.data.db.TitlePick
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.TmdbEpisode
import app.opentv.data.remote.TmdbHit
import app.opentv.data.remote.searchTitles
import app.opentv.data.remote.seasonEpisodes
import app.opentv.data.remote.titleByImdbId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A film or show TMDB knows and the Provider doesn't carry, as search shows it before it's opened and kept. */
data class AddonTitle(
    val tmdbId: String,
    /** What add-ons are asked by, and the kept row's provider id. */
    val imdbId: String,
    val name: String,
    val year: Int?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    /** A film when true, else a show. */
    val isMovie: Boolean = true,
)

/**
 * Titles the Provider doesn't carry (ADR 0011): found through TMDB once an add-on is set up, and kept as
 * rows of the built-in Add-ons source once opened, keyed by IMDb id, so from then on watch state,
 * Favourites, sync and Versions treat them as any other title. A show's seasons and episodes come from
 * TMDB as it's opened ([addonEpisodes]), the way a Provider's come from its panel.
 */
class AddonTitles(
    private val db: OpenTvDatabase,
    private val tmdb: TmdbClient,
    private val dispatchers: AppDispatchers,
    /** The viewer's add-ons: what they have streams for is all search offers, as nothing else could play. */
    private val addons: () -> List<StremioAddon>,
) {
    // Two titles kept at once mustn't each make an Add-ons source.
    private val keeping = Mutex()

    /**
     * TMDB's films and shows for [query] that this device has no row for, by TMDB id, each with the IMDb
     * id add-ons are asked by; one without is left out, since no add-on could be asked about it. Films
     * only when some add-on has film streams, shows only when some has show streams. Empty without such
     * an add-on or a TMDB Key, and when TMDB doesn't answer: the Provider's results are still there.
     */
    suspend fun search(query: String): List<AddonTitle> {
        val current = addons()
        val films = current.any { it.servesStreams(MOVIE) }
        val shows = current.any { it.servesStreams(SERIES) }
        if (!(films || shows) || !tmdb.isConfigured()) return emptyList()
        val hits = withContext(dispatchers.io) { tmdb.searchTitles(query) }.getOrNull().orEmpty()
            .filter { if (it.isMovie) films else shows }
            .take(MAX_RESULTS)
        // Films and shows apart: TMDB numbers each on its own, so a film can share a show's id.
        val (filmHits, showHits) = hits.partition { it.isMovie }
        val (storedFilms, storedShows) = withContext(dispatchers.io) {
            filmHits.storedBy { db.movies().picksWithTmdbIds(it) } to
                showHits.storedBy { db.series().picksWithTmdbIds(it) }
        }
        val wanted = hits.filter { it.tmdbId !in (if (it.isMovie) storedFilms else storedShows) }
        // Side by side: one request per title, and the viewer is waiting on the slowest.
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

    /**
     * [title] as a show of the Add-ons source, stored the first time it's opened and found by its IMDb id
     * after that. Its episodes come from TMDB as its page opens ([addonEpisodes]).
     */
    suspend fun keepShow(title: AddonTitle): Series = withContext(dispatchers.io) {
        keeping.withLock {
            val source = addonsSource()
            db.series().byProviderId(source.id, title.imdbId)?.let { return@withLock it }
            db.series().upsertAll(listOf(title.toSeries(source.id)))
            checkNotNull(db.series().byProviderId(source.id, title.imdbId))
        }
    }

    /**
     * The film kept from an add-on with the IMDb id [imdbId], kept now from TMDB if this device never kept
     * it: a title synced from another device arrives as no more than its id (ADR 0011). Add-ons or not,
     * so Continue Watching and Favourites match the other device. Null when TMDB doesn't know it; a
     * failure without a TMDB Key or when TMDB didn't answer, for a later sync to try again.
     */
    suspend fun keepSyncedFilm(imdbId: String): Result<Movie?> =
        keptOrLookedUp(imdbId, isMovie = true, kept = { db.movies().byProviderId(it, imdbId) }, keep = ::keepFilm)

    /** As [keepSyncedFilm], for a show. Its episodes aren't fetched: `ensureEpisodes` does that. */
    suspend fun keepSyncedShow(imdbId: String): Result<Series?> =
        keptOrLookedUp(imdbId, isMovie = false, kept = { db.series().byProviderId(it, imdbId) }, keep = ::keepShow)

    private suspend fun <T : Any> keptOrLookedUp(
        imdbId: String,
        isMovie: Boolean,
        kept: suspend (sourceId: Long) -> T?,
        keep: suspend (AddonTitle) -> T,
    ): Result<T?> = withContext(dispatchers.io) {
        val already = db.sources().addons()?.let { kept(it.id) }
        when {
            already != null -> Result.success(already)
            !tmdb.isConfigured() -> Result.failure(IllegalStateException("No TMDB Key"))
            else -> tmdb.titleByImdbId(imdbId, isMovie)
                .fold({ hit -> Result.success(hit?.let { keep(it.toTitle(imdbId)) }) }, { Result.failure(it) })
        }
    }

    /**
     * The IMDb id for a film, for Stremio add-on stream lookups: a film kept from an add-on is stored by
     * it, any other is resolved through Settee's TMDB key (the provider's TMDB id when present, else a
     * title+year search). Null without a key or a match, so the add-on feature stays inert rather than
     * guessing, and a failure when TMDB didn't answer.
     */
    suspend fun imdbIdOf(movie: Movie): Result<String?> = withContext(dispatchers.io) {
        if (db.sources().keptFromAddons(movie)) return@withContext Result.success(movie.streamId)
        tmdb.imdbId(title = movie.name, year = movie.year, isMovie = true, tmdbId = movie.tmdbId)
    }

    /**
     * The show's IMDb id: its own id when it already is one, as Stremio-style series and shows kept from
     * an add-on are, else TMDB's.
     */
    suspend fun imdbIdOf(series: Series): Result<String?> = if (IMDB_ID.matches(series.seriesId)) {
        Result.success(series.seriesId)
    } else {
        withContext(dispatchers.io) {
            tmdb.imdbId(title = series.name, year = series.year, isMovie = false, tmdbId = series.tmdbId)
        }
    }

    private suspend fun addonsSource(): Source = db.sources().addons() ?: run {
        val id = db.sources().insert(Source(name = Source.ADDONS_NAME, kind = SourceKind.ADDONS, url = ""))
        checkNotNull(db.sources().byId(id))
    }

    private fun imdbIdOf(hit: TmdbHit): String? =
        tmdb.imdbId(hit.name, hit.year, isMovie = hit.isMovie, tmdbId = hit.tmdbId).getOrNull()

    private companion object {
        /** A screen's worth: each costs a request for its IMDb id. */
        const val MAX_RESULTS = 10
        const val MOVIE = "movie"
        const val SERIES = "series"
        val IMDB_ID = Regex("tt\\d+")
    }
}

/**
 * The aired episodes TMDB lists for [series], a show kept from an add-on, as its episodes to store. Each
 * is keyed by the show's IMDb id plus season and episode (`tt…:1:3`), which is what add-ons are asked
 * with. Empty for a show without a TMDB id; a failure when TMDB didn't answer. Blocking, like [TmdbClient].
 */
internal fun TmdbClient.addonEpisodes(series: Series, nowUtcMillis: Long): Result<List<Episode>> {
    val tmdbId = series.tmdbId ?: return Result.success(emptyList())
    return seasonEpisodes(tmdbId).map { listed ->
        // None yet to air: no add-on has it, and Up Next would point at it.
        listed.filter { it.airedMillis != null && it.airedMillis <= nowUtcMillis }
            .map { it.toEpisode(series) }
    }
}

private fun TmdbEpisode.toEpisode(series: Series) = Episode(
    sourceId = series.sourceId,
    seriesId = series.seriesId,
    episodeId = "${series.seriesId}:$season:$number",
    season = season,
    episodeNumber = number,
    title = title,
    plot = overview,
    durationSeconds = runtimeMinutes?.let { it * SECONDS_PER_MINUTE },
    stillUrl = stillUrl,
    // None of its own: it plays through the add-on stream picker.
    streamUrl = "",
)

/**
 * [addonEpisodes] for [series], handed to [store] when TMDB lists any: an empty answer stores nothing, as
 * an empty panel reply doesn't, so it never retires the episodes already kept. Nothing for no series.
 */
internal suspend fun TmdbClient.storeAddonEpisodes(
    series: Series?,
    nowUtcMillis: Long,
    store: suspend (List<Episode>) -> Unit,
): Result<Unit> {
    series ?: return Result.success(Unit)
    return addonEpisodes(series, nowUtcMillis)
        .mapCatching { if (it.isNotEmpty()) store(it) }
        .onFailure { if (it is CancellationException) throw it }
}

private const val SECONDS_PER_MINUTE = 60

private suspend fun List<TmdbHit>.storedBy(picks: suspend (List<String>) -> List<TitlePick>): Set<String> =
    if (isEmpty()) emptySet() else picks(map { it.tmdbId }).mapNotNullTo(HashSet()) { it.tmdbId }

private fun TmdbHit.toTitle(imdbId: String) =
    AddonTitle(tmdbId, imdbId, name, year, posterUrl, backdropUrl, overview, isMovie)

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

private fun AddonTitle.toSeries(sourceId: Long) = Series(
    sourceId = sourceId,
    seriesId = imdbId,
    name = name,
    categoryId = null,
    posterUrl = posterUrl,
    rating = null,
    year = year,
    plot = overview,
    backdropUrl = backdropUrl,
    tmdbId = tmdbId,
)

/** Whether [movie] was kept from an add-on: it has no stream of its own, and plays through the add-on picker. */
suspend fun SourceDao.keptFromAddons(movie: Movie): Boolean = addons()?.id == movie.sourceId

/**
 * Whether this episode is one of a show kept from an add-on: those alone have no stream URL of their own
 * (ADR 0011), so OK on one opens the add-on stream picker rather than the player.
 */
val Episode.playsThroughAddons: Boolean get() = streamUrl.isEmpty()
