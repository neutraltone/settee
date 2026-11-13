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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

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
    // what made a Stick close the app (#180). Only the titles stay, a fraction of the size.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun titlesOf(rows: Flow<List<VersionRow>>, categories: Flow<List<SourceCategoryName>>) = categories
        .map { names -> names.associate { (it.sourceId to it.id) to it.name } }
        .distinctUntilChanged()
        // A refresh writes in batches: grouping again from the latest is all that's wanted.
        .flatMapLatest { names -> rows.mapLatest { group(it, names) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Lazily, null)

    private fun group(rows: List<VersionRow>, categoryNames: Map<Pair<Long, String>, String>): Titles {
        val byId = HashMap<Long, Title>(rows.size)
        for (versions in groupVersions(rows, VersionRow::tmdbId, VersionRow::name, VersionRow::year)) {
            val traits = versions.map {
                versionTraits(it.name, it.categoryId?.let { c -> categoryNames[it.sourceId to c] })
            }
            val title = Title(LongArray(versions.size) { versions[it].id }, titleBadge(traits, decoders))
            versions.forEach { byId[it.id] = title }
        }
        return Titles(byId)
    }
}
