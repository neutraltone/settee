/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.data.db.CategoryDao
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.MoviePick
import app.opentv.data.db.SeriesDao
import app.opentv.data.db.SourceDao
import app.opentv.data.db.StagedBatches
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
import app.opentv.data.model.personKey
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
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
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

/** A movie or a show, for what mixes them: Home's Favourites and Recently Added, and a person's page. */
sealed interface VodTitle {
    val name: String

    /** Its row's id in its own table: a film's and a show's can be the same number. */
    val id: Long
    val kind: StreamKind

    data class OfMovie(val movie: Movie) : VodTitle {
        override val name get() = movie.name
        override val id get() = movie.id
        override val kind get() = StreamKind.MOVIE
    }

    data class OfSeries(val series: Series) : VodTitle {
        override val name get() = series.name
        override val id get() = series.id
        override val kind get() = StreamKind.SERIES
    }
}

/**
 * One cover on Movies or Shows, in a row or a category's grid: a film or show, however many of its
 * Versions are listed. [name] is the provider's, where a Quality is written, [title] the cleaned one the
 * card shows, and [badge] what its Versions say of its Quality ([titleBadge]). Compose infers it's stable
 * from its fields, so the data layer needs no Compose annotation.
 */
data class TitleCover(
    val id: Long,
    val name: String,
    val title: String,
    val posterUrl: String?,
    val badge: String? = null,
)

/**
 * A category's films or shows as grid covers, one per title however many of its Versions the category
 * holds (ADR 0007), in the order the rows came. The first Version this device plays stands for it.
 */
internal fun titleCovers(rows: List<CoverRow>, titles: TitleIndex.Titles, decoders: DecoderList): List<TitleCover> =
    rows.groupBy { titles.of(it.id) ?: it.id }.values.map { versions ->
        val shown = versions.firstOrNull { decoders.playsQualityRanked(versionTraits(it.name, null).qualityRank) }
            ?: versions.first()
        TitleCover(
            id = shown.id,
            name = shown.name,
            title = VodTitleCleaner.clean(shown.name),
            posterUrl = shown.posterUrl ?: versions.firstNotNullOfOrNull { it.posterUrl },
            badge = titles.badgeFor(shown.id, shown.name),
        )
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
    /**
     * Words a failed download for the viewer; nothing here shows an exception's own message. Public
     * so the channel manager, which adds and tests providers through this module, words them the same.
     */
    val wording: ErrorWording,
) {

    /** TMDB back-fill for VOD detail pages, gated on the viewer's TMDB Key. See [TmdbClient]. */
    private val tmdb = TmdbClient(http, settings)

    /** Where work that outlives a sync runs, such as [MovieGenres]. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Waits for the work [background] is still doing. Tests close their database straight after a
     * sync, and work still reading it then fails into whichever test runs next.
     */
    internal suspend fun awaitBackground() = background.coroutineContext.job.children.toList().joinAll()

    /** Sources with a [fillGenresInBackground] job running. */
    private val sourcesGettingGenres: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // TMDB only with the viewer's own key. Every film once: its directors, writers and studios are
    // worth the calls, and TMDB's free key allows far more than a batch of 200 a minute.
    private val movieGenres by lazy {
        MovieGenres(
            movieDao,
            api,
            pauseMillis = genrePauseMillis,
            tmdb = object : MovieTmdb {
                override val ready get() = tmdb.isConfigured()

                override suspend fun lookUp(movie: Movie) = withContext(Dispatchers.IO) { tmdbMetaOf(movie) }
            },
        )
    }

    /** [SeriesTmdbPass], which asks TMDB about series as [movieGenres] does films. */
    private val seriesTmdb by lazy {
        SeriesTmdbPass(
            seriesDao,
            tmdb = object : SeriesTmdb {
                override val ready get() = tmdb.isConfigured()

                override suspend fun lookUp(series: Series) = withContext(Dispatchers.IO) { tmdbMetaOf(series) }
            },
            pauseMillis = genrePauseMillis,
        )
    }

    /**
     * [MovieGenres]' rest between batches. It runs in real time on [background], which tests wait
     * for, so they set 0 before their first sync: a 1,200-film test otherwise sleeps six minutes.
     * A property and not a constructor parameter, as the constructor has more than enough.
     */
    internal var genrePauseMillis: Long = MovieGenres.BATCH_PAUSE_MILLIS

    /**
     * Where a live list is staged, decoded, before it's saved; null is the JVM's temp folder, the
     * app's cache on Android. A property for the reason [genrePauseMillis] is.
     */
    internal var staging: File? = null

    private fun tmdbMetaOf(movie: Movie) =
        tmdb.movieMetaOrFailure(VodTitleCleaner.clean(movie.name), movie.year, movie.tmdbId)

    private fun tmdbMetaOf(series: Series) =
        tmdb.seriesMetaOrFailure(VodTitleCleaner.clean(series.name), series.year, series.tmdbId)

    sealed interface SyncResult {
        data class Success(val channelCount: Int, val movieCount: Int, val seriesCount: Int) : SyncResult

        /** [reason] is what the viewer sees; [cause] is for the log only. */
        data class Failed(val reason: ViewerError, val cause: Throwable?) : SyncResult
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

    // ---- Related by person (Plex-style "click an actor / director") ----------------------------

    /**
     * Everything in the library featuring a person — the data behind the Person screen: movies they
     * act in or directed (once each, though a film may credit them as both), then series they act
     * in, each best-rated first. Matched on their [personKey], so only that person, however a
     * provider spells the accents.
     */
    suspend fun titlesWithPerson(name: String, perKind: Int = 40): List<VodTitle> = withContext(Dispatchers.IO) {
        val key = personKey(name)
        if (key.isEmpty()) return@withContext emptyList()
        val movies = movieDao.moviesWithPerson(key, perKind)
        val series = seriesDao.seriesWithPerson(key, perKind)
        movies.map(VodTitle::OfMovie) + series.map(VodTitle::OfSeries)
    }

    /**
     * Loads a movie and, if its detail fields are still bare, back-fills them from the provider's
     * `get_vod_info` and persists the merged row before returning it. Safe to call whenever a detail
     * screen opens: it runs off the main thread, only touches the network when something is missing,
     * tolerates any failure (returning the row unchanged), and copies the stored row so nothing
     * already on it is lost. Non-Xtream sources are returned as-is.
     */
    suspend fun movieDetail(id: Long, now: Long = System.currentTimeMillis()): Movie? = withContext(Dispatchers.IO) {
        val movie = movieDao.byId(id) ?: return@withContext null
        var result = movie
        val source = sourceDao.byId(movie.sourceId)

        // 1) Provider back-fill from get_vod_info, for a still-bare row on an Xtream source.
        if (!result.isEnriched && source?.kind == SourceKind.XTREAM) {
            val info = runCatching { api.movieInfo(source, result.streamId) }.getOrNull()
            if (info != null) result = result.filledFrom(info)
        }

        // 2) TMDB, with the viewer's key, once per film and again six months on: for what the
        //    provider left blank and for the directors, writers and studios. Usually the background
        //    pass has asked already; this is for a film opened before the pass reached it.
        if (tmdb.isConfigured() && tmdbDue(result.tmdbAnsweredAt, now)) {
            tmdbMetaOf(result).onSuccess { result = result.withTmdb(it, now) }
        }

        if (result != movie) movieDao.upsertAll(listOf(result))
        result
    }

    /**
     * Loads a series and lazily back-fills a still-bare row from `get_series_info`. See [movieDetail].
     * The browse backdrop asks this as the highlight moves, so the reply's episodes aren't stored:
     * that would write every highlighted show's episodes, stamped as first seen while browsing. The
     * series page uses [openSeries].
     */
    suspend fun seriesDetail(id: Long, now: Long = System.currentTimeMillis()): Series? = withContext(Dispatchers.IO) {
        seriesDetails(id, now, opening = false).lastOrNull()
    }

    /**
     * A series being opened on its page: one `get_series_info` gives its episodes and, for a
     * still-bare row, its details, and the series is emitted once they're stored, so the page shows
     * with its episodes before TMDB is asked; then again with what TMDB filled in, if it was asked.
     * A row whose details are already in is emitted before that request instead.
     */
    fun openSeries(id: Long, now: Long = System.currentTimeMillis()): Flow<Series> =
        seriesDetails(id, now, opening = true)

    /** [seriesDetail], or with [opening] [openSeries]: the panel always asked, and its episodes stored. */
    private fun seriesDetails(id: Long, now: Long, opening: Boolean): Flow<Series> = flow {
        val series = seriesDao.byId(id) ?: return@flow
        var result = series
        val panel = sourceDao.byId(series.sourceId)?.takeIf { it.kind == SourceKind.XTREAM }

        // 1) The provider's get_series_info, on an Xtream source: on opening for its episodes, and
        //    for a still-bare row its details.
        // A row whose details are in is shown first, with its stored episodes: a slow panel would
        // otherwise keep the page on Loading for details it already has (#326). The page reads the
        // episodes the request stores from the database, so the row isn't shown again for them.
        val shownFirst = panel != null && opening && series.isEnriched
        if (shownFirst) emit(series)
        if (panel != null && (opening || !series.isEnriched)) {
            val info = fetchSeries(panel, series.seriesId, now, storeEpisodes = opening)
            if (info != null && !result.isEnriched) result = result.filledFrom(info)
        }
        if (result != series) seriesDao.upsertAll(listOf(result))
        if (!shownFirst) emit(result)

        // 2) TMDB, with the viewer's key, at once when something is still missing or TMDB's answer
        //    is six months old, so a series the background pass hasn't reached still fills in.
        if (tmdb.isConfigured() && result.wantsTmdb(now)) {
            tmdbMetaOf(result).onSuccess { meta ->
                val answered = result.withTmdb(meta, now)
                seriesDao.upsertAll(listOf(answered))
                emit(answered)
            }
        }
    }.flowOn(Dispatchers.IO)

    // -- feed helpers --

    /** A movie counts as "already enriched" once any headline detail field is set, so [movieDetail]
     *  re-fetches only truly-bare rows. A movie the provider has no metadata for re-fetches each open;
     *  acceptable, and it self-limits the moment anything comes back. */
    // Not by genre: [MovieGenres] fills that in for films nobody has opened, which would leave them
    // without the provider's artwork and cast when they are.
    private val Movie.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || director != null

    private val Series.isEnriched: Boolean
        get() = backdropUrl != null || cast != null || genre != null

    /** Whether opening this series asks TMDB: something TMDB fills is missing, or its answer is six months old. */
    private fun Series.wantsTmdb(now: Long): Boolean =
        backdropUrl == null || plot == null || cast == null || tmdbDue(tmdbAnsweredAt, now)

    /**
     * A film's More like this, ranked by the rules For you uses ([score]), after two things that
     * matter more for "like this": its own provider's films first, since another's may not play the
     * same, then the films sharing the most of its genres. With no genre, others from its category.
     */
    suspend fun moreLikeThis(
        movie: Movie,
        limit: Int = MORE_LIKE_THIS_LIMIT,
        now: Long = System.currentTimeMillis(),
    ): List<Movie> = withContext(Dispatchers.IO) {
        val genres = splitGenres(movie.genre)
        val candidates = if (genres.isEmpty()) {
            movieDao.picksInCategory(movie.sourceId, movie.categoryId, movie.id)
        } else {
            genres.flatMap { movieDao.picksSharingGenre(it, movie.id) }.distinctBy { it.id }
        }
        val ranking = Ranking(MoviePick::genre, MoviePick::rating, MoviePick::addedMillis, now)
        val shared = candidates.associate { it.id to splitGenres(it.genre).count(genres::contains) }
        val picked = candidates
            .filter { genres.isEmpty() || shared.getValue(it.id) > 0 }
            .sortedWith(
                compareByDescending<MoviePick> { it.sourceId == movie.sourceId }
                    .thenByDescending { shared.getValue(it.id) }
                    .then(ranking.byScore),
            )
            .take(limit)
            .map { it.id }
        val byId = movieDao.byIds(picked).associateBy { it.id }
        picked.mapNotNull(byId::get)
    }

    /** The viewer hid or showed a channel, on the TV or the web manager; dated, so the change can travel. */
    suspend fun setChannelHidden(id: Long, hidden: Boolean, now: Long = System.currentTimeMillis()) =
        channelDao.changeHidden(id, hidden, now)

    /** Sets (or clears, on blank) a channel's manual rename. Trimmed; a blank name clears it. */
    suspend fun setChannelCustomName(id: Long, name: String?) =
        channelDao.setCustomName(id, name?.trim()?.takeIf { it.isNotBlank() })

    /**
     * Puts a category's channels in the order of [ids], each one's position becoming its sortIndex,
     * in one transaction. Only channels whose place changed are written; returns how many were.
     */
    suspend fun setChannelOrder(ids: List<Long>): Int = channelDao.setOrder(ids)

    // --- Web channel manager: one-shot reads for the socket-thread server ------------------------
    // The manager server handles its HTTP requests off the UI thread and wants plain
    // lists, not Flows. These are read-only pass-throughs; every mutation still goes through the
    // suspend setters above (setChannelHidden, setChannelCustomName, setChannelOrder).

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
    ): Boolean = source.kind != SourceKind.XTREAM ||
        fetchSeries(source, seriesId, nowUtcMillis, storeEpisodes = true) != null

    /**
     * Asks the panel for [seriesId]'s `get_series_info`, storing its episodes when [storeEpisodes],
     * and returns its details, or null when the fetch failed. One request serves both, so opening a
     * series never asks for the same reply twice.
     */
    private suspend fun fetchSeries(
        source: Source,
        seriesId: String,
        nowUtcMillis: Long,
        storeEpisodes: Boolean,
    ): XtreamApi.SeriesInfo? = runCatching {
        val reply = api.seriesInfo(source, seriesId)
        if (storeEpisodes && reply.episodes.isNotEmpty()) storeEpisodes(source, seriesId, reply.episodes, nowUtcMillis)
        reply.info
    }
        .onFailure { if (it is CancellationException) throw it }
        // The exception's kind only: some clients put the request's address, with the account's
        // login in it, in the message, and the browse backdrop can fail here on every highlight.
        .onFailure { Log.w(TAG, "Series info failed for series $seriesId: ${it.javaClass.simpleName}") }
        .getOrNull()

    private suspend fun storeEpisodes(source: Source, seriesId: String, fetched: List<Episode>, nowUtcMillis: Long) {
        // Matched to the stored rows like a catalogue refresh: an upsert without the local id would
        // update nothing, and an episode must keep when it was first seen.
        val stored = episodeDao.forSeries(source.id, seriesId).associateBy { it.episodeId }
        episodeDao.upsertAll(
            fetched.map { ep ->
                stored[ep.episodeId]?.let { ep.copy(id = it.id, firstSeenMillis = it.firstSeenMillis) }
                    ?: ep.copy(firstSeenMillis = nowUtcMillis)
            },
        )
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
            SyncResult.Failed(wording.ofProvider(e, R.string.error_catalogue), e)
        } catch (e: OutOfMemoryError) {
            // A list too big for the heap. The half-built list went with the frames that threw, so
            // the app carries on and the next sync can try again; a crash would help nobody. Logged
            // without the stack trace, whose text could need more memory than is left.
            Log.w(TAG, "Catalogue sync ran out of memory for source ${source.id}")
            val tv = R.string.error_channel_list_memory_tv
            SyncResult.Failed(wording.ofHardware(tv, R.string.error_channel_list_memory_device, OUT_OF_MEMORY), e)
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
        val categoryNames = liveCategories.associate { it.id to it.name }
        // Decoded a batch at a time off the download, as movies and shows are: a very large line-up
        // held whole, as a string, a JSON tree and the channels at once, can run a stick's heap out.
        // Cleaning a channel looks at it alone, so it happens per batch too. The cleaned batches are
        // staged on disk and saved only once the whole reply has decoded: decoded inside the save's
        // transaction, they held SQLite's writer for the length of the decode, and progress and guide
        // writes queued behind it (#328). A reply cut off partway throws here, before anything's saved.
        val staged = api.liveStreams(source).use { list ->
            StagedBatches.stage(staging, Channel.serializer()) { stage ->
                list.forEachBatch { batch -> stage(normalized(batch, categoryNames)) }
            }
        }
        val saved = staged.use { channelDao.replaceCatalogue(source.id, nowUtcMillis, it) }
        if (saved == 0) {
            return SyncResult.Failed(wording.of(R.string.error_no_channels, NO_CHANNELS), null)
        }

        categoryDao.upsertAll(liveCategories)
        sourceDao.markCatalogSynced(source.id, nowUtcMillis)
        return SyncResult.Success(saved, 0, 0)
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
            return SyncResult.Failed(wording.of(R.string.error_no_channels, NO_CHANNELS), null)
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
        val movies = if (moviesOn) fetchMovies(source) else ListFetch.SKIPPED
        val series = if (seriesOn) fetchShows(source) else ListFetch.SKIPPED
        return VodFetch(
            series.updated,
            // Nothing at all may be a struggling panel's empty reply, so it isn't worth trusting for
            // 12 hours; an account with no VOD just asks again, which is a quick request.
            movies.complete && series.complete && (movies.count > 0 || series.count > 0),
        )
    }

    /**
     * One of an Xtream provider's lists, movies or shows: how many titles it had, whether it came
     * back (a failed list still leaves the other to be saved, but mustn't count as fetched), and for
     * shows, the ones updated since they were last saved.
     */
    data class ListFetch(val count: Int, val complete: Boolean, val updated: List<Series> = emptyList()) {
        companion object {
            val SKIPPED = ListFetch(0, complete = true)
        }
    }

    /** How far through saving a list: its [share], 0 to 1, and how many titles are [saved] so far. */
    data class SaveProgress(val share: Float, val saved: Int)

    /**
     * [source]'s movies, saved as in VodLists; the films' details then fill in in the background.
     * [progress] hears how far through saving the downloaded list it is.
     */
    suspend fun fetchMovies(source: Source, progress: (SaveProgress) -> Unit = {}): ListFetch =
        withContext(Dispatchers.IO) {
            val categories = fetchList { categoryDao.upsertAll(api.movieCategories(source)) }
            var count = 0
            val list = fetchList { count = api.movies(source).use { movieDao.save(source.id, it, progress) } }
            if (count > 0) fillGenresInBackground(source)
            ListFetch(count, categories && list)
        }

    /** [source]'s shows, saved as in VodLists, with [progress] as for [fetchMovies]. */
    suspend fun fetchShows(source: Source, progress: (SaveProgress) -> Unit = {}): ListFetch =
        withContext(Dispatchers.IO) {
            val categories = fetchList { categoryDao.upsertAll(api.seriesCategories(source)) }
            var saved = SavedSeries(0, emptyList())
            val list = fetchList { saved = api.series(source).use { seriesDao.save(source.id, it, progress) } }
            if (saved.count > 0) fillGenresInBackground(source)
            ListFetch(saved.count, categories && list, saved.moved)
        }

    /** How many of [step]'s titles (movies or shows) [sourceId] has stored. */
    suspend fun storedCount(sourceId: Long, step: SetupStep): Int = when (step) {
        SetupStep.MOVIES -> movieDao.countForSource(sourceId)
        SetupStep.SHOWS -> seriesDao.countForSource(sourceId)
        SetupStep.CHANNELS, SetupStep.GUIDE -> 0
    }

    /** Whether [fetch] worked; anything but cancelling counts as a list that didn't come back. */
    private suspend fun fetchList(fetch: suspend () -> Unit): Boolean = runCatching { fetch() }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "A movie or show list failed", it) }
        .isSuccess

    /** [fillGenresInBackground] for every provider that has films: Xtream's. */
    suspend fun fillEveryProvidersGenres() =
        enabledSources().filter { it.kind == SourceKind.XTREAM }.forEach(::fillGenresInBackground)

    /**
     * Works through [source]'s films not looked up in the background ([MovieGenres.fillAll]): the
     * catalogue is saved by now, and the genre rows and people's pages fill in as it goes. After a
     * sync, and whenever Movies or Shows opens or the app starts, so it carries on where it left off.
     * One job per source, one lookup after another, so a struggling panel doesn't get hundreds of
     * calls at once; asked again while it's running, it adds no second one.
     */
    fun fillGenresInBackground(source: Source) {
        if (!sourcesGettingGenres.add(source.id)) return
        background.launch {
            try {
                runCatching {
                    movieGenres.fillAll(source)
                }.onFailure { Log.w(TAG, "Movie genres failed for source ${source.id}", it) }
                runCatching {
                    seriesTmdb.fillAll(source)
                }.onFailure { Log.w(TAG, "Series TMDB look-ups failed for source ${source.id}", it) }
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
                return SyncResult.Failed(wording.ofHttpStatus(response.code, R.string.error_catalogue), null)
            }
            val stream = response.body.byteStream()
            M3uParser.parse(stream, source.id)
        }

        if (parsed.channels.isEmpty()) {
            // Nothing touched: a playlist that briefly comes back empty, or with only films and
            // episodes, is a provider hiccup, and must not cost the viewer their channels.
            return SyncResult.Failed(wording.of(R.string.error_playlist_empty, NO_CHANNELS), null)
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
        // A playlist's categories are only its channels' group-titles, so one no channel is in any
        // more is a group the playlist dropped, or the films and shows an earlier version stored.
        categoryDao.deleteEmptyLive(source.id)

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
        for ((sourceId, channels) in bySource) {
            val names = categoryDao.liveNamesOf(sourceId).associate { it.id to it.name }
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

    /**
     * Switches an Xtream source's live-stream container (HLS ↔ MPEG-TS) and rewrites its live
     * channels' playback URLs in place, so the change takes effect immediately without a re-sync.
     *
     * Xtream only: an M3U source's channel URLs come straight from its playlist and must not be
     * rebuilt from credentials. The `channels` table is live-only (movies and series have their own
     * tables), so every row here is a live channel whose URL derives from [XtreamApi.liveStreamUrl].
     */
    suspend fun setLiveFormat(sourceId: Long, format: LiveStreamFormat) = withContext(Dispatchers.IO) {
        channelDao.switchLiveFormat(sourceId, format, api::liveStreamUrl)
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

        /** The detail line's code for a provider that answered with no channels at all. */
        private const val NO_CHANNELS = "NoChannels"
        private const val OUT_OF_MEMORY = "OutOfMemory"

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
private const val MORE_LIKE_THIS_LIMIT = 20
