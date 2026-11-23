/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.annotation.VisibleForTesting
import app.opentv.core.AppDispatchers
import app.opentv.data.db.MOVIES_TABLE
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.SERIES_TABLE
import app.opentv.data.model.SourceCategoryName
import app.opentv.data.model.StreamKind
import app.opentv.data.model.VersionRow
import app.opentv.player.DecoderList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every film and show grouped into its titles (ADR 0007), worked out again whenever the catalogue
 * changes, for what lists show once and what each cover's badge says.
 *
 * Held in memory rather than looked up per cover: a category's grid holds one shelf's rows, while a
 * cover's badge and whether it's shown once depend on Versions in other categories, and the tables have
 * no index on the TMDB id, which a schema change would add. Built from a light projection, off the
 * main thread, and kept until the app closes. Grouping starts when something first asks, or [start].
 */
class TitleIndex(
    private val db: OpenTvDatabase,
    private val decoders: DecoderList,
    private val dispatchers: AppDispatchers = AppDispatchers(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default),
    private val regroupGap: (tookMillis: Long) -> Long = ::regroupGapMillis,
) {
    /** One title: its Versions' row ids, and its cover's badge (see [titleBadge]). */
    class Title internal constructor(val versionIds: LongArray, val badge: String?)

    /** One kind's titles, by any of their Versions' row ids. */
    class Titles internal constructor(private val byId: Map<Long, Title>) {
        /** The title [id] is a Version of; null for a row the index hasn't seen yet. */
        fun of(id: Long): Title? = byId[id]

        fun badgeOf(id: Long): String? = byId[id]?.badge

        /**
         * The badge on a cover for [id]: its title's, or before the index has seen it, what its own [name]
         * says, as covers showed before titles were grouped.
         */
        fun badgeFor(id: Long, name: String): String? {
            val title = byId[id] ?: return nameBadge(name)
            return title.badge
        }

        /** [items] with only the first of each title's Versions, in their order. */
        fun <T> distinct(items: List<T>, idOf: (T) -> Long): List<T> = items.distinctBy { byId[idOf(it)] ?: idOf(it) }

        companion object {
            /** Before the index is built: every row its own title, with no badge. */
            val NONE = Titles(emptyMap())
        }
    }

    /** The films' titles, or null until they've first been grouped. */
    val movies: StateFlow<Titles?> =
        titlesOf(MOVIES_TABLE, db.movies()::versionRows, db.categories().observeNames(StreamKind.MOVIE))

    /** The shows' titles, or null until they've first been grouped. */
    val series: StateFlow<Titles?> =
        titlesOf(SERIES_TABLE, db.series()::versionRows, db.categories().observeNames(StreamKind.SERIES))

    /**
     * Each title's Versions for what plays and what's kept, waiting a moment for a first grouping. Past
     * that, the TMDB id stands in, rather than holding up Play.
     */
    val versionIds = VersionIds { kind, id ->
        withTimeoutOrNull(FIRST_GROUPING_WAIT_MILLIS) { titlesFor(kind).filterNotNull().first() }?.of(id)?.versionIds
    }

    /** Whether [id] and [other] are Versions of one title, as grouped so far; null before the first grouping. */
    fun sameTitle(kind: StreamKind, id: Long, other: Long): Boolean? =
        titlesFor(kind).value?.let { titles -> id == other || titles.of(id)?.let { it === titles.of(other) } == true }

    private fun titlesFor(kind: StreamKind) = if (kind == StreamKind.SERIES) series else movies

    /** [items], a list of films, with each title once: the first of its Versions there. */
    fun <T> distinctMovies(items: Flow<List<T>>, idOf: (T) -> Long): Flow<List<T>> =
        items.combine(movies.groupedOrNone()) { list, titles -> titles.distinct(list, idOf) }

    /** [items], a list of shows, with each title once: the first of its Versions there. */
    fun <T> distinctSeries(items: Flow<List<T>>, idOf: (T) -> Long): Flow<List<T>> =
        items.combine(series.groupedOrNone()) { list, titles -> titles.distinct(list, idOf) }

    /** [items], films and shows together, with each title once: the first of its Versions there. */
    fun distinctTitles(items: Flow<List<VodTitle>>): Flow<List<VodTitle>> =
        combine(items, movies.groupedOrNone(), series.groupedOrNone(), ::distinctIn)

    /** As [distinctTitles], for a list read once. */
    suspend fun distinct(items: List<VodTitle>): List<VodTitle> =
        distinctIn(items, movies.groupedOrNone().first(), series.groupedOrNone().first())

    /**
     * Starts grouping now rather than when something first asks, for a screen about to: the first
     * screen opening, so the grouping is done while the viewer picks a Profile (#582). Not when the
     * index is made, as a sync with nobody looking makes it too.
     */
    fun start() {
        // Grouping, once started, runs until the index closes: holding a subscription is all it needs.
        scope.launch { movies.collect {} }
        scope.launch { series.collect {} }
    }

    /** Stops regrouping and waits for a read still running, for a test about to close its database. */
    @VisibleForTesting
    suspend fun close() = scope.coroutineContext.job.cancelAndJoin()

    // Rows go straight into titles and aren't kept: holding every row of a catalogue in memory is
    // what made a Stick close the app (#180). Only the titles stay, a fraction of the size, and of the
    // rows only a hash, to skip a write that changes nothing grouping reads, such as a page filling in a
    // film's details.
    //
    // Read on a signal that the table changed, not with a Room Flow over the rows: that runs the whole
    // read on every write, before anything downstream can settle or skip it, and the background pass
    // that fills in films' details writes every ten films for hours. On a Stick each read of a large
    // catalogue overflows SQLite's cursor window and churns the heap, so the pass alone kept two cores
    // busy and starved playback. Reads also leave a gap after each grouping ([regroupGapMillis]),
    // so steady writes regroup now and then rather than continually. The bound is per table: with
    // both passes writing, films and shows together can take a tenth of a core. A change of category
    // names, or a retry after a failed read, starts afresh without the gap; neither pass writes those.
    //
    // No gap after an empty table: on a first setup that's grouped before the catalogue arrives, and
    // waiting out a gap then would leave every list showing each title's Versions apart for minutes.
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun titlesOf(
        table: String,
        rows: suspend () -> List<VersionRow>,
        categories: Flow<List<SourceCategoryName>>,
    ) = categories
        .map { names -> names.associate { (it.sourceId to it.id) to it.name } }
        .distinctUntilChanged()
        .flatMapLatest { names ->
            var grouped: Int? = null
            db.invalidationTracker.createFlow(table)
                // A refresh writes in batches: grouping once they settle is all that's wanted, but no
                // longer than a few seconds into them, as plain debounce never fires while writes keep
                // coming faster than it waits, which held up the first grouping, and Recently Added
                // with it, for as long as a film-details lookup wrote (#582).
                .settled(SETTLE_MILLIS, FIRST_GROUPING_MAX_WAIT_MILLIS)
                .conflate()
                .transform {
                    val started = System.nanoTime()
                    val read = rows()
                    val hash = read.hashCode()
                    if (hash != grouped) {
                        emit(group(read, names))
                        grouped = hash
                    }
                    if (read.isNotEmpty()) {
                        delay(regroupGap(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)))
                    }
                }
        }
        .flowOn(dispatchers.default)
        // A read that fails, or a database closed under it, leaves every row its own title for now,
        // rather than grids that wait for ever, and grouping tries again later rather than never.
        .retryWhen { _, _ ->
            emit(Titles.NONE)
            delay(RETRY_MILLIS)
            true
        }
        .stateIn(scope, SharingStarted.Lazily, null)

    private suspend fun group(rows: List<VersionRow>, categoryNames: Map<Pair<Long, String>, String>): Titles {
        val byId = HashMap<Long, Title>(rows.size)
        val titles = groupVersions(rows, VersionRow::tmdbId, VersionRow::name, VersionRow::year)
        // Checked as it goes, so new category names, or the index closing, stop a grouping under way.
        val job = currentCoroutineContext()
        for ((i, versions) in titles.withIndex()) {
            if (i % CHECK_EVERY == 0) job.ensureActive()
            val traits = versions.map {
                versionTraits(it.name, it.categoryId?.let { c -> categoryNames[it.sourceId to c] })
            }
            val title = Title(LongArray(versions.size) { versions[it].id }, titleBadge(traits, decoders))
            versions.forEach { byId[it.id] = title }
        }
        return Titles(byId)
    }
}

/** How long a lookup waits for the first grouping, which takes a second or two on a Stick. */
private const val FIRST_GROUPING_WAIT_MILLIS = 3_000L

/** How long the catalogue stays unchanged before it's grouped again. */
private const val SETTLE_MILLIS = 500L

/** The longest a stream of writes holds off grouping, at first or after a regroup's gap. */
private const val FIRST_GROUPING_MAX_WAIT_MILLIS = 3_000L

/** [items] with each title once, by [movies]' and [series]' titles. */
private fun distinctIn(items: List<VodTitle>, movies: TitleIndex.Titles, series: TitleIndex.Titles): List<VodTitle> =
    items.distinctBy { (if (it.kind == StreamKind.SERIES) series else movies).of(it.id) ?: it }

/**
 * The titles once grouped, or every row its own title if the first grouping takes longer than a lookup
 * waits, then the grouping when it comes: a list shown with a title twice for a moment beats Home
 * waiting on a grouping a busy refresh holds up (#582).
 */
private fun StateFlow<TitleIndex.Titles?>.groupedOrNone(): Flow<TitleIndex.Titles> = flow {
    emit(withTimeoutOrNull(FIRST_GROUPING_WAIT_MILLIS) { filterNotNull().first() } ?: TitleIndex.Titles.NONE)
    emitAll(filterNotNull())
}.distinctUntilChanged()

/**
 * Each value once [quietMillis] pass with no newer one, as `debounce` does, except that a value
 * waiting when [maxWaitMillis] have passed since the first of its burst goes through then: plain
 * `debounce` never emits while values keep coming faster than [quietMillis].
 */
@OptIn(FlowPreview::class)
private fun <T> Flow<T>.settled(quietMillis: Long, maxWaitMillis: Long): Flow<T> {
    val burstStart = AtomicLong(NO_BURST)
    return onEach { burstStart.compareAndSet(NO_BURST, nowMillis()) }
        .debounce {
            val waited = nowMillis() - burstStart.get()
            quietMillis.coerceAtMost((maxWaitMillis - waited).coerceAtLeast(0))
        }
        .onEach { burstStart.set(NO_BURST) }
}

private const val NO_BURST = -1L

// Not SystemClock, which stands still under Robolectric unless a test moves it.
private fun nowMillis() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

/**
 * How long after reading and grouping, which took [tookMillis], changes wait before the next: at least
 * [MIN_REGROUP_GAP_MILLIS], and long enough that regrouping takes no more than a twentieth of a core
 * however slow the device, up to [MAX_REGROUP_GAP_MILLIS].
 */
internal fun regroupGapMillis(tookMillis: Long): Long =
    (tookMillis * BUSY_SHARE_INVERSE).coerceIn(MIN_REGROUP_GAP_MILLIS, MAX_REGROUP_GAP_MILLIS)

/** For every millisecond spent grouping, this many are left for everything else. */
private const val BUSY_SHARE_INVERSE = 19L

/** The shortest gap between groupings while the catalogue keeps changing. */
private const val MIN_REGROUP_GAP_MILLIS = 10_000L

/** The longest: a title added during a slow device's long refresh still shows as one within this. */
private const val MAX_REGROUP_GAP_MILLIS = 10 * 60_000L

/** How long after a failed read grouping tries again. */
private const val RETRY_MILLIS = 30_000L

/** How many titles are grouped between checks that the work is still wanted. */
private const val CHECK_EVERY = 500
