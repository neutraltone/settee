/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.db.CategoryDao
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.SeriesDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.ChannelHiddenChange
import app.opentv.data.model.CoverRow
import app.opentv.data.model.Episode
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesRef
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.ref
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.parser.M3uParser
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.TmdbClient
import app.opentv.data.remote.XtreamApi
import app.opentv.player.DecoderList
import app.opentv.player.playsQualityRanked
import app.opentv.sync.SyncHiddenChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps one channel per distinct quality signature, preserving order (so "best first" is
 * preserved when the caller has already sorted by rank).
 *
 * Two streams with the same rank AND the same label are treated as the same quality — one is
 * kept, the rest dropped. This is what collapses "RAW / RAW" and "HD / HD" duplicates down to
 * a single option, and turns a falsely-multi-quality channel back into a single one.
 */
internal fun distinctByQuality(channels: List<app.opentv.data.model.Channel>): List<app.opentv.data.model.Channel> {
    val seen = HashSet<String>()
    return channels.filter { seen.add("${it.qualityRank}|${it.qualityLabel.lowercase()}") }
}

/** A home/detail row: a genre label and the titles under it. Generic so movies and series share it. */
@androidx.compose.runtime.Immutable
data class GenreGroup<T>(val genre: String, val items: List<T>)

/**
 * One library entry a person is credited in — a movie or a series — for the Person screen's mixed
 * poster grid. A thin wrapper so the screen can render one grid yet still route a click to the right
 * detail page (movie vs series) without a second lookup.
 */
sealed interface PersonTitle {
    data class MovieItem(val movie: Movie) : PersonTitle
    data class SeriesItem(val series: Series) : PersonTitle
}

/** A movie or a show, for the rows that mix them: Home's Favourites and Recently Added. */
sealed interface VodTitle {
    val name: String

    data class OfMovie(val movie: Movie) : VodTitle {
        override val name get() = movie.name
    }

    data class OfSeries(val series: Series) : VodTitle {
        override val name get() = series.name
    }
}

/** One quality variant of a film, with the quality parsed from its name by [ChannelNameNormalizer]. */
data class MovieVariant(val movie: Movie, val qualityLabel: String, val qualityRank: Int)

/** One logical film with its switchable quality tiers, best first — the VOD analogue of a channel group. */
@androidx.compose.runtime.Immutable
data class MovieVariantGroup(
    /** The highest Quality this device can play: what a row shows and plays by default. */
    val primary: Movie,
    /** Every variant (including [primary]), best quality first. */
    val variants: List<MovieVariant>,
) {
    val hasMultipleQualities: Boolean get() = variants.size > 1
}

/** A standalone 4-digit release year (19xx/20xx) as it appears inside a VOD title. */
private val VOD_YEAR = Regex("""\b(19|20)\d{2}\b""")

/**
 * Collapses obvious quality variants of the same film — "The Godfather 1972 HD" and
 * "The Godfather 4K" — into one entry with switchable tiers, the VOD analogue of a channel's
 * quality group. Pure and in-memory: movies carry no stored groupKey, so the key is computed here
 * from the normalised, year-stripped name plus the year (the field, else the one embedded in the
 * name). Group order follows first appearance; variants within a group are best-quality first.
 *
 * It folds only genuine quality variants: edition tokens [ChannelNameNormalizer] does not know
 * (IMAX, EXTENDED, 3D) stay in the name and keep those cuts separate — intended. Two same-named
 * films that both lack any year will merge, an accepted edge for a best-effort collapse.
 */
internal fun collapseMovieVariants(movies: List<Movie>, decoders: DecoderList): List<MovieVariantGroup> =
    foldQualities(movies, Movie::name, Movie::year, decoders).map { (primary, variants) ->
        MovieVariantGroup(primary = primary, variants = variants.map { MovieVariant(it.item, it.label, it.rank) })
    }

/**
 * One cover on Movies or Shows, in a row or a category's grid: a show, or a film with its Qualities
 * folded into the one this device plays by default ([qualities] says how many there are). [name] is
 * the provider's, where a Quality is written, and [title] the cleaned one the card shows. Compose
 * infers it's stable from its fields, so the data layer needs no Compose annotation.
 */
data class TitleCover(val id: Long, val name: String, val title: String, val posterUrl: String?, val qualities: Int = 1)

/** A category's films as grid covers, each film's Qualities folded as [collapseMovieVariants] does. */
internal fun movieCovers(rows: List<CoverRow>, decoders: DecoderList): List<TitleCover> =
    foldQualities(rows, CoverRow::name, CoverRow::year, decoders).map { (primary, variants) ->
        primary.toCover(qualities = variants.size)
    }

/** A category's shows as grid covers. */
internal fun seriesCovers(rows: List<CoverRow>): List<TitleCover> = rows.map { it.toCover() }

private fun CoverRow.toCover(qualities: Int = 1) =
    TitleCover(id, name, VodTitleCleaner.clean(name), posterUrl, qualities)

private class RankedVariant<T>(val item: T, val label: String, val rank: Int)

/** See [collapseMovieVariants]: each film's primary, and all its variants best first. */
private fun <T> foldQualities(
    items: List<T>,
    nameOf: (T) -> String,
    yearOf: (T) -> Int?,
    decoders: DecoderList,
): List<Pair<T, List<RankedVariant<T>>>> {
    val groups = LinkedHashMap<String, MutableList<RankedVariant<T>>>()
    for (item in items) {
        val name = nameOf(item)
        val embeddedYear = VOD_YEAR.find(name)?.value?.toIntOrNull()
        val normalized = ChannelNameNormalizer.normalize(VOD_YEAR.replace(name, " "))
        val year = yearOf(item) ?: embeddedYear
        val key = normalized.groupKey + "|" + (year?.toString() ?: "")
        groups.getOrPut(key) {
            mutableListOf()
        }.add(RankedVariant(item, normalized.qualityLabel, normalized.qualityRank))
    }
    return groups.values.map { variants ->
        val ordered = variants.sortedWith(compareByDescending<RankedVariant<T>> { it.rank }.thenBy { nameOf(it.item) })
        val primary = ordered.firstOrNull { decoders.playsQualityRanked(it.rank) } ?: ordered.first()
        primary.item to ordered
    }
}

/**
 * Channels, movies and series.
 *
 * Same principle as [EpgRepository]: a refresh that fails must never leave the user with less
 * than they started with. Catalogue writes go through [ChannelDao.replaceCatalogue], which
 * merges rather than wipes and carries hidden flags and manual ordering across.
 */
class CatalogRepository(
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val episodeDao: EpisodeDao,
    private val viewerLibrary: ViewerLibrary,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {

    /** Where work that outlives a sync runs, such as [MovieGenres]. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Waits for the work [background] is still doing. Tests close their database straight after a
     * sync, and work still reading it then fails into whichever test runs next.
     */
    internal suspend fun awaitBackground() = background.coroutineContext.job.children.toList().joinAll()

    /** TMDB back-fill for VOD detail pages, gated on a user-supplied key. See [TmdbClient]. */
    private val tmdb = TmdbClient(http, settings)

    /** Sources with a [fillGenresInBackground] job running. */
    private val sourcesGettingGenres: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // TMDB only with the viewer's own key, and only for a film the provider had no genre for.
    private val movieGenres = MovieGenres(movieDao, api) { movie ->
        if (!tmdb.isConfigured()) return@MovieGenres null
        withContext(Dispatchers.IO) {
            tmdb.movieMeta(VodTitleCleaner.clean(movie.name), movie.year, movie.tmdbId)?.genre
        }
    }

    sealed interface SyncResult {
        data class Success(val channelCount: Int, val movieCount: Int, val seriesCount: Int) : SyncResult

        data class Failed(val reason: String, val cause: Throwable?) : SyncResult
    }

    /** Series the provider updated, for New Episode detection, and whether every list came back. */
    data class VodFetch(val updated: List<Series>, val complete: Boolean)

    fun observeChannels(sourceId: Long? = null, categoryId: String? = null): Flow<List<Channel>> =
        channelDao.observe(sourceId, categoryId)

    fun observeChannelsIn(categoryIds: List<String>): Flow<List<Channel>> = channelDao.observeInCategories(categoryIds)

    /**
     * Channels in a set of categories INCLUDING hidden ones, optionally scoped to one source — the
     * channel manager's browse feed. Separate from [observeChannelsIn] (which drops `hidden` rows
     * for the guide) because the manager must show hidden channels so they can be un-hidden.
     */
    fun observeChannelsInIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>> =
        channelDao.observeInCategoriesIncludingHidden(sourceId, categoryIds)

    /** Reactive number of visible channels on disk — the UI uses this to tell "guide still
     * building" (channels exist) apart from "nothing loaded" (a failed or empty sync). */
    fun observeChannelCount(): Flow<Int> = channelDao.observeVisibleCount()

    fun observeCategories(kind: StreamKind): Flow<List<Category>> = categoryDao.observe(kind)

    fun observeMovies(categoryId: String? = null): Flow<List<Movie>> = movieDao.observe(categoryId)

    fun observeSeries(categoryId: String? = null): Flow<List<Series>> = seriesDao.observe(categoryId)

    fun observeMovieCovers(sourceId: Long, categoryId: String): Flow<List<CoverRow>> =
        movieDao.observeCovers(sourceId, categoryId)

    fun observeSeriesCovers(sourceId: Long, categoryId: String): Flow<List<CoverRow>> =
        seriesDao.observeCovers(sourceId, categoryId)

    fun observeEpisodes(sourceId: Long, seriesId: String): Flow<List<Episode>> =
        episodeDao.observeForSeries(sourceId, seriesId)

    fun searchChannels(query: String): Flow<List<Channel>> = channelDao.search(query)

    fun searchChannelsIncludingHidden(query: String): Flow<List<Channel>> = channelDao.searchIncludingHidden(query)

    fun searchMovies(query: String): Flow<List<Movie>> = movieDao.search(query)

    fun searchSeries(query: String): Flow<List<Series>> = seriesDao.search(query)

    suspend fun channel(id: Long): Channel? = channelDao.byId(id)

    suspend fun movie(id: Long): Movie? = movieDao.byId(id)

    /**
     * The IMDb id for a film, for Stremio add-on stream lookups. Resolved through the user's TMDB
     * key (using the provider's TMDB id when present, else a title+year search). Null without a key
     * or a match — the add-on feature stays inert rather than guessing.
     */
    suspend fun imdbIdFor(movie: Movie): String? = withContext(Dispatchers.IO) {
        tmdb.imdbId(title = movie.name, year = movie.year, isMovie = true, tmdbId = movie.tmdbId)
    }

    suspend fun episode(id: Long): Episode? = episodeDao.byId(id)

    suspend fun series(id: Long): app.opentv.data.model.Series? = seriesDao.byId(id)

    /** The series an episode belongs to, by the provider's id for it. */
    suspend fun seriesOf(episode: Episode): app.opentv.data.model.Series? =
        seriesDao.byProviderId(episode.sourceId, episode.seriesId)

    // ---- Netflix-style home feeds ---------------------------------------------------------------
    // All local: derived from the catalogue already on disk plus the active profile's watch history.
    // The plain catalogue rows (recently added) are Flows so they fill in live as a VOD sync lands;
    // the computed rows (recommended, by-genre, more-like-this) are one-shot suspend reads, cheap
    // enough to recompute on screen open over a few thousand titles (one in-memory pass each — a
    // per-genre LIKE query would multiply round-trips and match substrings). A later UI agent wraps
    // these into home rows and detail screens.

    /** "Recently Added" movies, newest first. Reactive. */
    fun recentlyAddedMovies(limit: Int = 30): Flow<List<Movie>> = movieDao.observeRecentlyAdded(limit)

    /**
     * Home's Recently Added: the [limit] movies and shows the providers added or last updated most
     * recently, newest first, leaving out a kind that's switched off and titles never dated. Reactive.
     */
    fun recentlyAdded(
        movies: Boolean = true,
        shows: Boolean = true,
        limit: Int = RECENTLY_ADDED,
    ): Flow<List<VodTitle>> = combine(
        if (movies) movieDao.observeRecentlyAdded(limit) else flowOf(emptyList()),
        if (shows) seriesDao.observeRecentlyAdded(limit) else flowOf(emptyList()),
    ) { movieRows, seriesRows ->
        (
            movieRows.map { it.addedMillis to VodTitle.OfMovie(it) } +
                seriesRows.map { it.addedMillis to VodTitle.OfSeries(it) }
            )
            // A title the provider never dated says nothing about when it arrived, so it isn't
            // recent. Providers stamp a whole import alike, so the name settles the order there,
            // rather than every movie of the batch coming before every show.
            .filter { it.first > 0 }
            .sortedWith(compareByDescending<Pair<Long, VodTitle>> { it.first }.thenBy { it.second.name })
            .take(limit)
            .map { it.second }
    }

    /** "Recently Added" series, newest first. Reactive. */
    fun recentlyAddedSeries(limit: Int = 30): Flow<List<Series>> = seriesDao.observeRecentlyAdded(limit)

    /** Every movie, newest first — for a caller that wants to build its own groupings. */
    suspend fun allMovies(): List<Movie> = withContext(Dispatchers.IO) { movieDao.all() }

    /** Every series, newest first. */
    suspend fun allSeries(): List<Series> = withContext(Dispatchers.IO) { seriesDao.all() }

    /** Changes when the movies or what the home rows rank them by do; see [VodViewModel.loadHomeFeeds]. */
    suspend fun movieFingerprint(): String = withContext(Dispatchers.IO) { movieDao.fingerprint() }

    /** Changes when the series or what the home rows rank them by do; see [VodViewModel.loadHomeFeeds]. */
    suspend fun seriesFingerprint(): String = withContext(Dispatchers.IO) { seriesDao.fingerprint() }

    /**
     * For you's genre rows for movies, from an ALREADY-LOADED list: [VodViewModel.loadHomeFeeds] reads
     * [allMovies] once and hands that one list to this and [recommendedMoviesFrom], so opening Movies
     * scans the (20k-title) table once instead of once per row. Every genre with enough movies the
     * Profile hasn't [started], the genres of its [recent] ones (local ids, newest first) leading. See
     * [genreRowsFrom].
     */
    suspend fun moviesByGenreFrom(
        all: List<Movie>,
        started: Set<Long>,
        recent: List<Long>,
        now: Long = System.currentTimeMillis(),
    ): List<GenreGroup<Movie>> = withContext(Dispatchers.Default) {
        val byId = all.associateBy { it.id }
        genreRowsFrom(all, movieRanking(now), recent.mapNotNull(byId::get), leftOut = { it.id in started })
    }

    /**
     * For you's genre rows for series. See [moviesByGenreFrom]. A [dismissed] series is left out and
     * doesn't lead, as in [recommendedSeriesFrom].
     */
    suspend fun seriesByGenreFrom(
        all: List<Series>,
        started: Set<SeriesRef>,
        dismissed: Set<SeriesRef>,
        recent: List<SeriesRef>,
        now: Long = System.currentTimeMillis(),
    ): List<GenreGroup<Series>> = withContext(Dispatchers.Default) {
        val byRef = all.associateBy { it.ref }
        genreRowsFrom(
            all,
            seriesRanking(now),
            recent.filterNot { it in dismissed }.mapNotNull(byRef::get),
            leftOut = { it.ref in started || it.ref in dismissed },
        )
    }

    /**
     * "Recommended for you" from an ALREADY-LOADED movie list, so the home screen scans the
     * (20k-title) table once for the whole home feed rather than once per row. See [recommendFrom].
     * The [started] titles feed the tally and stay out of the row. A movie marked Unwatched isn't
     * among them, so it can come back.
     */
    suspend fun recommendedMoviesFrom(
        all: List<Movie>,
        started: Set<Long>,
        limit: Int = 30,
        now: Long = System.currentTimeMillis(),
    ): List<Movie> = withContext(Dispatchers.Default) {
        recommendFrom(all, movieRanking(now), limit, tallied = { it.id in started }, leftOut = { it.id in started })
    }

    /**
     * "Recommended for you" for series, from an already-loaded list. A [dismissed] series, one removed
     * from Continue Watching, is neither offered nor tallied, which is stricter than a movie marked
     * Unwatched, since that one can come back.
     */
    suspend fun recommendedSeriesFrom(
        all: List<Series>,
        started: Set<SeriesRef>,
        dismissed: Set<SeriesRef>,
        limit: Int = 30,
        now: Long = System.currentTimeMillis(),
    ): List<Series> = withContext(Dispatchers.Default) {
        recommendFrom(
            all,
            seriesRanking(now),
            limit,
            tallied = { it.ref in started && it.ref !in dismissed },
            leftOut = { it.ref in started || it.ref in dismissed },
        )
    }

    /**
     * "More Like This" for a movie: other titles sharing a genre, most genres in common first,
     * same-source titles preferred, the film itself excluded. Falls back to other titles in the
     * same source/category when the movie has no genre metadata to match on.
     */
    suspend fun moreLikeThis(movie: Movie, limit: Int = 20): List<Movie> = withContext(Dispatchers.IO) {
        val genres = splitGenres(movie.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext movieDao.similarByCategory(movie.sourceId, movie.categoryId, movie.id, limit)
        }
        movieDao.all().asSequence()
            .filter { it.id != movie.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Movie, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == movie.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    // ---- Related by person (Plex-style "click an actor / director") ----------------------------

    /** Movies a person is billed in, best-rated first. Blank query yields nothing. */
    suspend fun moviesWithActor(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesWithActor(it, limit) }.orEmpty()
    }

    /** Movies a person directed, best-rated first. */
    suspend fun moviesByDirector(name: String, limit: Int = 40): List<Movie> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { movieDao.moviesByDirector(it, limit) }.orEmpty()
    }

    /** Series a person is billed in, best-rated first. */
    suspend fun seriesWithActor(name: String, limit: Int = 40): List<Series> = withContext(Dispatchers.IO) {
        name.trim().takeIf { it.isNotEmpty() }?.let { seriesDao.seriesWithActor(it, limit) }.orEmpty()
    }

    /**
     * Everything in the library featuring a person — the data behind the Person screen. Merges movies
     * they act in, movies they directed and series they act in, de-duplicating movies that credit the
     * same person as both actor and director. Movies (best-rated first) lead, then series.
     */
    suspend fun titlesWithPerson(name: String, perKind: Int = 40): List<PersonTitle> = withContext(Dispatchers.IO) {
        val query = name.trim()
        if (query.isEmpty()) return@withContext emptyList()

        val movies = LinkedHashMap<Long, Movie>()
        for (m in movieDao.moviesWithActor(query, perKind)) movies[m.id] = m
        for (m in movieDao.moviesByDirector(query, perKind)) movies.putIfAbsent(m.id, m)

        val orderedMovies = movies.values.sortedWith(
            compareByDescending<Movie> { it.rating ?: -1.0 }.thenByDescending { it.addedMillis },
        )
        val series = seriesDao.seriesWithActor(query, perKind)

        buildList(orderedMovies.size + series.size) {
            orderedMovies.forEach { add(PersonTitle.MovieItem(it)) }
            series.forEach { add(PersonTitle.SeriesItem(it)) }
        }
    }

    /** "More Like This" for a series. See [moreLikeThis]. */
    suspend fun moreLikeThisSeries(series: Series, limit: Int = 20): List<Series> = withContext(Dispatchers.IO) {
        val genres = splitGenres(series.genre).toSet()
        if (genres.isEmpty()) {
            return@withContext seriesDao.similarByCategory(series.sourceId, series.categoryId, series.id, limit)
        }
        seriesDao.all().asSequence()
            .filter { it.id != series.id }
            .map { it to sharedGenreCount(it.genre, genres) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Series, Int>> { it.second }
                    .thenByDescending { it.first.sourceId == series.sourceId }
                    .thenByDescending { it.first.rating ?: -1.0 },
            )
            .map { it.first }
            .take(limit)
            .toList()
    }

    /**
     * Loads a movie and, if its detail fields are still bare, back-fills them from the provider's
     * `get_vod_info` and persists the merged row before returning it. Safe to call whenever a detail
     * screen opens: it runs off the main thread, only touches the network when something is missing,
     * tolerates any failure (returning the row unchanged), and copies the stored row so nothing
     * already on it is lost. Non-Xtream sources are returned as-is.
     */
    suspend fun movieDetail(id: Long): Movie? = withContext(Dispatchers.IO) {
        val movie = movieDao.byId(id) ?: return@withContext null
        var result = movie
        val source = sourceDao.byId(movie.sourceId)

        // 1) Provider back-fill from get_vod_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.movieInfo(source, result.streamId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    director = result.director ?: info.director,
                    genre = result.genre.ifUnknown() ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                    durationSeconds = result.durationSeconds ?: info.durationSeconds,
                    releaseDate = result.releaseDate ?: info.releaseDate,
                )
            }
        }

        // 2) TMDB fallback for whatever the provider still left blank — only when the user set a
        //    key, and only while a headline visual field is missing (so it stops re-fetching once
        //    filled). This is the "match Plex" back-fill for posters, backdrops, synopsis and cast.
        //    Only here, on open: a bulk lookup across the catalogue would spend the user's TMDB quota
        //    on titles nobody looks at.
        if (tmdb.isConfigured() && result.lacksTmdbFields) {
            val meta = runCatching {
                tmdb.movieMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    director = result.director ?: meta.director,
                    genre = result.genre.ifUnknown() ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                    releaseDate = result.releaseDate ?: meta.releaseDate,
                )
            }
        }

        if (result != movie) movieDao.upsertAll(listOf(result))
        result
    }

    /** Loads a series and lazily back-fills its detail fields from `get_series_info`. See [movieDetail]. */
    suspend fun seriesDetail(id: Long): Series? = withContext(Dispatchers.IO) {
        val series = seriesDao.byId(id) ?: return@withContext null
        var result = series
        val source = sourceDao.byId(series.sourceId)

        // 1) Provider back-fill from get_series_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.seriesInfo(source, result.seriesId) }.getOrNull()
            if (info != null) {
                result = result.copy(
                    backdropUrl = result.backdropUrl ?: info.backdropUrl,
                    cast = result.cast ?: info.cast,
                    genre = result.genre ?: info.genre,
                    tmdbId = result.tmdbId ?: info.tmdbId,
                    plot = result.plot ?: info.plot,
                    rating = result.rating ?: info.rating,
                    year = result.year ?: info.year,
                )
            }
        }

        // 2) TMDB fallback for anything still missing (see [movieDetail]); TMDB has no director for TV.
        if (tmdb.isConfigured() && (result.backdropUrl == null || result.plot == null || result.cast == null)) {
            val meta = runCatching {
                tmdb.seriesMeta(VodTitleCleaner.clean(result.name), result.year, result.tmdbId)
            }.getOrNull()
            if (meta != null) {
                result = result.copy(
                    posterUrl = result.posterUrl ?: meta.posterUrl,
                    backdropUrl = result.backdropUrl ?: meta.backdropUrl,
                    plot = result.plot ?: meta.overview,
                    cast = result.cast ?: meta.cast,
                    genre = result.genre ?: meta.genre,
                    tmdbId = result.tmdbId ?: meta.tmdbId,
                    rating = result.rating ?: meta.rating,
                    year = result.year ?: meta.year,
                )
            }
        }

        if (result != series) seriesDao.upsertAll(listOf(result))
        result
    }

    // -- feed helpers --

    /** A movie counts as "already enriched" once any headline detail field is set, so [movieDetail]
     *  re-fetches only truly-bare rows. A movie the provider has no metadata for re-fetches each open;
     *  acceptable, and it self-limits the moment anything comes back. */
    // Not by genre: [MovieGenres] fills that in for films nobody has opened, which would leave them
    // without the provider's artwork and cast when they are.
    private val Movie.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || director != null

    /** Null for a genre [MovieGenres] found the provider has none for, so another source can fill it. */
    private fun String?.ifUnknown(): String? = this?.ifBlank { null }

    /** Whether a headline field TMDB can fill on detail open is still blank. */
    private val Movie.lacksTmdbFields: Boolean
        get() = backdropUrl == null || plot == null || cast == null

    private val Series.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null

    /** How many of a title's genres are in the wanted set — the More-Like-This overlap. */
    private fun sharedGenreCount(genre: String?, wanted: Set<String>): Int = splitGenres(genre).count { it in wanted }

    /** The viewer hid or showed a channel, on the TV or the web manager; dated, so the change can travel. */
    suspend fun setChannelHidden(id: Long, hidden: Boolean, now: Long = System.currentTimeMillis()) =
        channelDao.changeHidden(id, hidden, now)

    /** Sets (or clears, on blank) a channel's manual rename. Trimmed; a blank name clears it. */
    suspend fun setChannelCustomName(id: Long, name: String?) =
        channelDao.setCustomName(id, name?.trim()?.takeIf { it.isNotBlank() })

    suspend fun setChannelSortIndex(id: Long, sortIndex: Int) = channelDao.setSortIndex(id, sortIndex)

    // --- Web channel manager: one-shot reads for the socket-thread server ------------------------
    // The manager server handles one HTTP request at a time off the UI thread and wants plain
    // lists, not Flows. These are read-only pass-throughs; every mutation still goes through the
    // suspend setters above (setChannelHidden/CustomName/SortIndex).

    /** Enabled providers, for the manager's source selector. */
    suspend fun enabledSources(): List<Source> = sourceDao.enabled()

    /** Every live category (raw, un-folded), for the manager's category list. */
    suspend fun liveCategories(): List<Category> = categoryDao.allByKind(StreamKind.LIVE)

    /** Channel counts per (source, category) — the number shown next to each category. */
    suspend fun categoryChannelCounts(): List<app.opentv.data.db.CategoryChannelCount> =
        channelDao.channelCountsByCategory()

    /** One category's channels, hidden included, optionally scoped to a single source. */
    suspend fun channelsInCategoryForManager(sourceId: Long?, categoryId: String): List<Channel> =
        channelDao.channelsInCategoryIncludingHidden(sourceId, categoryId)

    /** The channels a viewer hid or showed on this device, for sync. */
    suspend fun exportHiddenChannels(): List<SyncHiddenChannel> = channelDao.hiddenForSync()

    /**
     * Merges another device's hides and unhides, newest change wins. A dated change is kept even if
     * the provider isn't listing that channel right now. Returns how many channels it changed here.
     */
    suspend fun importHiddenChannels(items: List<SyncHiddenChannel>): Int {
        val sources = sourceDao.directory()
        return items.count { item ->
            val sourceId = sources.id(item.source) ?: return@count false
            val here = channelDao.hiddenChange(item.source, item.id)?.changedAtMillis
            if (!isNewerChange(item.changedAtMillis, here)) return@count false
            if (item.changedAtMillis > 0) {
                channelDao.upsertHiddenChange(
                    ChannelHiddenChange(item.source, item.id, item.hidden, item.changedAtMillis),
                )
            }
            val wasHidden = channelDao.isHidden(sourceId, item.id)
            channelDao.setHidden(sourceId, item.id, item.hidden)
            wasHidden != null && wasHidden != item.hidden
        }
    }

    /**
     * Series episodes are fetched lazily — panels are slow and most series are never opened.
     * Episodes not stored before are stamped as first seen at [nowUtcMillis]. Returns false when the
     * fetch failed; a source with nothing to fetch hasn't failed.
     */
    suspend fun ensureEpisodes(
        source: Source,
        seriesId: String,
        nowUtcMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (source.kind != SourceKind.XTREAM) return true
        return runCatching { api.episodes(source, seriesId) }
            .onSuccess { fetched ->
                if (fetched.isEmpty()) return@onSuccess
                // Matched to the stored rows like a catalogue refresh: an upsert without the local
                // id would update nothing, and an episode must keep when it was first seen.
                val stored = episodeDao.forSeries(source.id, seriesId).associateBy { it.episodeId }
                episodeDao.upsertAll(
                    fetched.map { ep ->
                        stored[ep.episodeId]?.let { ep.copy(id = it.id, firstSeenMillis = it.firstSeenMillis) }
                            ?: ep.copy(firstSeenMillis = nowUtcMillis)
                    },
                )
            }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "Episode fetch failed for series $seriesId", it) }
            .isSuccess
    }

    /** Live channels only — the fast path so the guide can show before VOD and the guide load. */
    suspend fun fetchLive(source: Source, nowUtcMillis: Long): SyncResult = withContext(Dispatchers.IO) {
        try {
            when (source.kind) {
                SourceKind.XTREAM -> syncXtreamLive(source, nowUtcMillis)
                SourceKind.M3U -> syncM3u(source, nowUtcMillis)
                SourceKind.STALKER -> syncStalkerLive(source, nowUtcMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Catalogue sync failed for source ${source.id}", e)
            SyncResult.Failed(e.message ?: "The catalogue could not be downloaded.", e)
        }
    }

    /**
     * Movies + series — best-effort, so a huge VOD list never costs anyone live TV. [VodFetch.complete]
     * says whether the lists all came back with something, so a failed fetch isn't remembered as
     * fresh; otherwise silent, since an account with no VOD is normal, not an error. The stored
     * update times have moved by the time this returns, so [detectNewEpisodes] gets this one chance
     * at [VodFetch.updated].
     */
    suspend fun fetchVod(source: Source, nowUtcMillis: Long): VodFetch = withContext(Dispatchers.IO) {
        if (source.kind != SourceKind.XTREAM) return@withContext VodFetch(emptyList(), complete = true)
        runCatching { syncXtreamVod(source, nowUtcMillis) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "VOD sync failed for source ${source.id}", it) }
            .getOrDefault(VodFetch(emptyList(), complete = false))
    }

    suspend fun detectNewEpisodes(source: Source, updated: List<Series>, nowUtcMillis: Long) =
        withContext(Dispatchers.IO) {
            viewerLibrary.newEpisodes.seriesUpdated(updated) { ensureEpisodes(source, it.seriesId, nowUtcMillis) }
        }

    private suspend fun syncXtreamLive(source: Source, nowUtcMillis: Long): SyncResult {
        // Authenticate first so a wrong password produces a clear message rather than
        // four separate confusing failures further down.
        api.authenticate(source)

        val liveCategories = api.liveCategories(source)
        val channels = api.liveStreams(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "The server returned no channels. The account may have no package assigned.",
                null,
            )
        }

        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    /**
     * Stalker/Ministra live sync. Mirrors [syncXtreamLive] but the channels it stores carry a
     * [Channel.cmd] instead of a directly-playable URL — [PlaybackSources] mints the real URL at
     * play time. The handshake happens inside [StalkerApi] on first call; a bad MAC or URL throws
     * here with a clear message, caught by [fetchLive].
     */
    private suspend fun syncStalkerLive(source: Source, nowUtcMillis: Long): SyncResult {
        val liveCategories = stalkerApi.liveCategories(source)
        val channels = stalkerApi.liveChannels(source)
        if (channels.isEmpty()) {
            return SyncResult.Failed(
                "The portal returned no channels. The MAC may not be authorised, or its package is empty.",
                null,
            )
        }
        categoryDao.upsertAll(liveCategories)
        val categoryNames = liveCategories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(channels, categoryNames), nowUtcMillis)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(channels.size, 0, 0)
    }

    private suspend fun syncXtreamVod(source: Source, nowUtcMillis: Long): VodFetch {
        // VOD is optional: plenty of accounts have live TV only, and a 404 on get_vod_streams
        // must not cost the user their channel list. Movies and series are gated independently so
        // a user who only turned off, say, Series still gets their movie library refreshed.
        val moviesOn = settings.moviesEnabled.value
        val seriesOn = settings.seriesEnabled.value
        if (!moviesOn && !seriesOn) return VodFetch(emptyList(), complete = false)

        // A failed list still leaves the others to be saved, but it must not count as fetched.
        var complete = true
        suspend fun <T> attempt(wanted: Boolean, empty: T, fetch: suspend () -> T): T {
            if (!wanted) return empty
            return runCatching { fetch() }.getOrElse {
                if (it is CancellationException) throw it
                complete = false
                empty
            }
        }
        suspend fun categories(wanted: Boolean, fetch: suspend () -> List<Category>) {
            val categories = attempt(wanted, emptyList(), fetch)
            if (categories.isNotEmpty()) categoryDao.upsertAll(categories)
        }

        // See VodLists for how each list is saved.
        var movies = 0
        categories(moviesOn) { api.movieCategories(source) }
        attempt(moviesOn, Unit) { movies = api.movies(source).use { movieDao.save(source.id, it) } }
        var series = SavedSeries(0, emptyList())
        categories(seriesOn) { api.seriesCategories(source) }
        attempt(seriesOn, Unit) { series = api.series(source).use { seriesDao.save(source.id, it) } }
        if (movies > 0) fillGenresInBackground(source)
        return VodFetch(
            series.moved,
            // Nothing at all may be a struggling panel's empty reply, so it isn't worth trusting for
            // 12 hours; an account with no VOD just asks again, which is a quick request.
            complete && (movies > 0 || series.count > 0),
        )
    }

    /**
     * Works through [source]'s films without a genre in the background ([MovieGenres.fillAll]): the
     * catalogue is saved by now, and the genre rows fill in as it goes. After a sync, and whenever
     * Movies or Shows opens or the app starts, so it carries on where it left off. One job per
     * source, one lookup after another, so a struggling panel doesn't get hundreds of calls at once;
     * asked again while it's running, it adds no second one.
     */
    /** [fillGenresInBackground] for every provider that has films: Xtream's. */
    suspend fun fillEveryProvidersGenres() =
        enabledSources().filter { it.kind == SourceKind.XTREAM }.forEach(::fillGenresInBackground)

    fun fillGenresInBackground(source: Source) {
        if (!sourcesGettingGenres.add(source.id)) return
        background.launch {
            try {
                runCatching { movieGenres.fillAll(source) }
                    .onFailure { Log.w(TAG, "Movie genres failed for source ${source.id}", it) }
            } finally {
                sourcesGettingGenres.remove(source.id)
            }
        }
    }

    private suspend fun syncM3u(source: Source, nowUtcMillis: Long): SyncResult {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent)
            .build()

        val parsed = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return SyncResult.Failed("Playlist download failed (HTTP ${response.code}).", null)
            }
            val stream = response.body.byteStream()
            M3uParser.parse(stream, source.id)
        }

        if (parsed.channels.isEmpty()) {
            return SyncResult.Failed(
                "No channels found in that playlist. Check the URL points at an M3U file.",
                null,
            )
        }

        // Synthesise categories from group-title so the UI has something to group by.
        val categories = parsed.channels
            .mapNotNull { it.categoryId }
            .distinct()
            .sorted()
            .mapIndexed { index, name ->
                Category(
                    id = name,
                    sourceId = source.id,
                    name = name,
                    kind = StreamKind.LIVE,
                    sortIndex = index,
                )
            }

        categoryDao.upsertAll(categories)
        val categoryNames = categories.associate { it.id to it.name }
        channelDao.replaceCatalogue(source.id, normalized(parsed.channels, categoryNames), nowUtcMillis)

        // If the playlist declared its own guide URL and the user did not set one, adopt it.
        if (source.epgUrl.isNullOrBlank() && !parsed.declaredEpgUrl.isNullOrBlank()) {
            sourceDao.update(source.copy(epgUrl = parsed.declaredEpgUrl))
        }

        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(parsed.channels.size, 0, 0)
    }

    /**
     * Stamps every channel with its normalised identity before it is stored.
     *
     * This one pass powers both headline features: [Channel.groupKey] folds quality
     * variants of a channel into a single row and is the join key for EPG matching, and
     * [Channel.displayName] is the cleaned name the UI shows instead of `UK| BBC ONE FHD`.
     */
    private fun normalized(channels: List<Channel>, categoryNames: Map<String, String>): List<Channel> {
        val stamped = channels.map { channel ->
            val n = ChannelNameNormalizer.normalize(channel.name)
            var rank = n.qualityRank
            var label = n.qualityLabel
            if (label.isEmpty() || rank == 0) {
                // Some providers put the quality in the CATEGORY, not the channel:
                // 'UK| GENERAL HD/RAW' and 'UK| GENERAL hevc' holding identically named
                // channels. Without this, the player's switch is four buttons all
                // reading 'Standard' — grouped correctly, labelled uselessly.
                val categoryName = channel.categoryId?.let { categoryNames[it] }
                if (categoryName != null) {
                    val c = ChannelNameNormalizer.normalize(categoryName)
                    if (label.isEmpty()) label = c.qualityLabel
                    if (rank == 0) rank = c.qualityRank
                }
            }
            channel.copy(
                displayName = n.baseName,
                groupKey = n.channelKey,
                qualityRank = rank,
                qualityLabel = label,
                // Providers ship decorative separator rows ('#### UK GENERAL ####') as
                // channels. They are headings, not channels — hide them on import.
                hidden = channel.hidden || isSeparatorRow(channel.name),
            )
        }

        return stamped
    }

    /** Decorative list headings: starts AND ends with a run of banner characters. */
    private fun isSeparatorRow(rawName: String): Boolean {
        val t = rawName.trim()
        return t.length >= 6 &&
            t.take(3).all { it in SEPARATOR_CHARS } &&
            t.takeLast(3).all { it in SEPARATOR_CHARS }
    }

    /**
     * Re-cleans every stored channel with the current normaliser, no network needed.
     *
     * displayName, groupKey, quality and separator-hiding are computed at import time, so a
     * change to the normaliser (a new superscript char, a new junk pattern) does not reach
     * channels already in the database until the next full catalogue sync — which can be
     * hours away. This runs the same pass over existing rows locally, so a code fix shows up
     * on the next launch instead of the next sync. Bump [NORMALIZER_VERSION] to trigger it.
     */
    suspend fun renormalizeAll(): Int = withContext(Dispatchers.IO) {
        val existing = channelDao.everyRow()
        if (existing.isEmpty()) return@withContext 0

        val bySource = existing.groupBy { it.sourceId }
        var changed = 0
        for ((_, channels) in bySource) {
            val names = channelCategoryNames(channels)
            val renamed = normalized(channels, names)
            // Only write rows that actually changed, to keep the write small.
            val diff = renamed.filterIndexed { i, c ->
                val old = channels[i]
                c.displayName != old.displayName ||
                    c.groupKey != old.groupKey ||
                    c.qualityLabel != old.qualityLabel ||
                    c.qualityRank != old.qualityRank ||
                    c.hidden != old.hidden
            }
            if (diff.isNotEmpty()) {
                diff.chunked(500).forEach { channelDao.upsertAll(it) }
                changed += diff.size
            }
        }
        Log.i(TAG, "Re-normalised $changed channels with the current normaliser")
        changed
    }

    /** Best-effort category-id → name map for a set of channels. */
    private suspend fun channelCategoryNames(channels: List<Channel>): Map<String, String> {
        val ids = channels.mapNotNull { it.categoryId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return categoryDao.namesFor(ids).associate { it.id to it.name }
    }

    /**
     * Switches an Xtream source's live-stream container (HLS ↔ MPEG-TS) and rewrites its live
     * channels' playback URLs in place, so the change takes effect immediately without a re-sync.
     *
     * Xtream only: an M3U source's channel URLs come straight from its playlist and must not be
     * rebuilt from credentials. The `channels` table is live-only (movies and series have their own
     * tables), so every row here is a live channel whose URL derives from [XtreamApi.liveStreamUrl].
     */
    suspend fun setLiveFormat(sourceId: Long, format: LiveStreamFormat) = withContext(Dispatchers.IO) {
        val source = sourceDao.byId(sourceId) ?: return@withContext
        if (source.kind != SourceKind.XTREAM || source.liveFormat == format) return@withContext
        val updated = source.copy(liveFormat = format)
        sourceDao.update(updated)
        // Rebuild each live channel's URL for the new container.
        channelDao.forSource(sourceId).forEach { channel ->
            val newUrl = api.liveStreamUrl(updated, channel.streamId)
            if (newUrl != channel.streamUrl) channelDao.updateStreamUrl(channel.id, newUrl)
        }
    }

    suspend fun deleteSource(sourceId: Long) = withContext(Dispatchers.IO) {
        channelDao.deleteForSource(sourceId)
        categoryDao.deleteForSource(sourceId)
        movieDao.deleteForSource(sourceId)
        seriesDao.deleteForSource(sourceId)
        episodeDao.deleteForSource(sourceId)
        sourceDao.delete(sourceId)
    }

    companion object {
        private const val TAG = "CatalogRepository"
        val SEPARATOR_CHARS = setOf('#', '*', '=', '~', '-', '_', '•', '█', '▓', '|')

        /**
         * Bump this whenever the normaliser changes in a way that should re-process
         * already-imported channels. The app compares it against a stored value on launch
         * and runs [renormalizeAll] once when it moves.
         */
        const val NORMALIZER_VERSION = 3
    }
}

/** About as many as fit across Home twice: enough to browse, few enough to stay recent. */
private const val RECENTLY_ADDED = 20
