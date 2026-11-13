/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.SourceCategoryName
import app.opentv.data.model.StreamKind
import app.opentv.data.model.VersionRow
import app.opentv.player.DecoderList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Every film and show grouped into its titles (ADR 0007), worked out again whenever the catalogue
 * changes, for what lists show once and what each cover's badge says.
 *
 * Held in memory rather than looked up per cover: a category's grid holds one shelf's rows, while a
 * cover's badge and whether it's shown once depend on Versions in other categories, and the tables have
 * no index on the TMDB id, which a schema change would add. Built from a light projection, off the
 * main thread, and kept until the app closes.
 */
class TitleIndex(
    db: OpenTvDatabase,
    private val decoders: DecoderList,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
        titlesOf(db.movies().observeVersionRows(), db.categories().observeNames(StreamKind.MOVIE))

    /** The shows' titles, or null until they've first been grouped. */
    val series: StateFlow<Titles?> =
        titlesOf(db.series().observeVersionRows(), db.categories().observeNames(StreamKind.SERIES))

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
        items.combine(movies.filterNotNull()) { list, titles -> titles.distinct(list, idOf) }

    /** [items], a list of shows, with each title once: the first of its Versions there. */
    fun <T> distinctSeries(items: Flow<List<T>>, idOf: (T) -> Long): Flow<List<T>> =
        items.combine(series.filterNotNull()) { list, titles -> titles.distinct(list, idOf) }

    /** [items], films and shows together, with each title once: the first of its Versions there. */
    fun distinctTitles(items: Flow<List<VodTitle>>): Flow<List<VodTitle>> =
        combine(items, movies.filterNotNull(), series.filterNotNull(), ::distinctIn)

    /** As [distinctTitles], for a list read once. */
    suspend fun distinct(items: List<VodTitle>): List<VodTitle> =
        distinctIn(items, movies.filterNotNull().first(), series.filterNotNull().first())

    private fun distinctIn(items: List<VodTitle>, movies: Titles, series: Titles): List<VodTitle> =
        items.distinctBy { (if (it.kind == StreamKind.SERIES) series else movies).of(it.id) ?: it }

    /** Stops regrouping, for a test that closes its database. */
    fun close() = scope.cancel()

    // Rows go straight into titles and aren't kept: holding every row of a catalogue in memory is
    // what made a Stick close the app (#180). Only the titles stay, a fraction of the size, and of the
    // rows only a hash, to skip a write that changes nothing grouping reads, such as a page filling in a
    // film's details.
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun titlesOf(rows: Flow<List<VersionRow>>, categories: Flow<List<SourceCategoryName>>) = categories
        .map { names -> names.associate { (it.sourceId to it.id) to it.name } }
        .distinctUntilChanged()
        .flatMapLatest { names ->
            rows.distinctUntilChangedBy { it.hashCode() }
                // A refresh writes in batches: grouping once they settle is all that's wanted.
                .debounce(SETTLE_MILLIS)
                .mapLatest { group(it, names) }
        }
        .flowOn(Dispatchers.Default)
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
        // Checked as it goes, so newer rows replace a grouping still under way instead of queuing behind it.
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

/** How long after a failed read grouping tries again. */
private const val RETRY_MILLIS = 30_000L

/** How many titles are grouped between checks that the work is still wanted. */
private const val CHECK_EVERY = 500
