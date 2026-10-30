/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ChannelDao
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.ChannelNameNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn

/** Which Channels a viewer is looking at. Scope decides which Channels appear, never their Qualities. */
sealed interface LineupScope {
    /** Every Channel, or one provider's. */
    data class All(val sourceId: Long? = null) : LineupScope

    /** The Channels in one [CategoryGroup], by its key, optionally one provider's. */
    data class Group(val key: String, val sourceId: Long? = null) : LineupScope

    /** The active Profile's Favourite Channels, optionally one provider's. */
    data class Favourites(val sourceId: Long? = null) : LineupScope

    /** Channels whose name contains [query]. */
    data class Search(val query: String) : LineupScope

    /**
     * The channel manager's search: hidden rows and the Parental Lock's categories included, so
     * they can be found and managed.
     */
    data class ManageSearch(val query: String) : LineupScope

    /** The channel manager's view of one [CategoryGroup], hidden and locked rows included. */
    data class ManageGroup(val key: String, val sourceId: Long? = null) : LineupScope
}

/**
 * One Channel: every Quality of it, best first, [primary] the one that plays by default. It's a
 * [favourite] of the active Profile when any of its Qualities is.
 */
@androidx.compose.runtime.Immutable
data class LineupChannel(
    val primary: Channel,
    val qualities: List<Channel>,
    val favourite: Boolean = false,
    /** Stable while the catalogue is: what a list keys this Channel by. */
    val key: String = primary.groupKey.ifEmpty { "#${primary.id}" },
) {
    /**
     * The Qualities worth offering as a switch, one per distinct quality: the same stream listed in
     * two categories isn't a choice. Starring, hiding and recording still cover every Quality.
     */
    val switchable: List<Channel> get() = distinctByQuality(qualities)

    /**
     * The record menu's "record from which provider" options: each provider's best Quality,
     * [firstSourceId]'s first. With two providers a recording can run on one account while the
     * viewer keeps watching on the other, the only real way around a single-connection limit.
     */
    fun bestPerSource(firstSourceId: Long): List<Channel> =
        qualities.groupBy { it.sourceId }.values.map { it.first() }.sortedByDescending { it.sourceId == firstSourceId }

    /**
     * Every Quality's guide ids, best Quality's first, each most trustworthy first. Not [primary]'s
     * first: that depends on the scope, and the guide id mustn't.
     */
    val guideCandidates: List<String>
        get() = qualities.flatMap { it.epgCandidates }.distinct()

    /**
     * The guide this Channel uses: the first of its [guideCandidates] that has programmes, by
     * [hasProgrammes]. A provider's id can point at a guide it never ships, and mustn't shadow a
     * sibling Quality's id that works. [ChannelLineup.guideId] asks the stored guide.
     */
    fun guideId(hasProgrammes: (String) -> Boolean): String? = guideCandidates.firstOrNull(hasProgrammes)

    /** Whether row [rowId] is one of this Channel's Qualities. */
    internal fun has(rowId: Long): Boolean = qualities.any { it.id == rowId }

    /** This Channel, a Favourite when any of its Qualities is among [starred] row ids. */
    internal fun starredBy(starred: Set<Long>): LineupChannel = copy(favourite = qualities.any { it.id in starred })
}

/**
 * One rail entry = one *logical* category. Providers split categories by codec ('UK| GENERAL
 * HD/RAW' and 'UK| GENERAL hevc' are the same shelf twice), so they fold by normalised name and
 * filter across every underlying id at once.
 */
data class CategoryGroup(val key: String, val label: String, val ids: List<String>)

/**
 * The key a category is grouped and locked by: categories whose names differ only by decoration
 * or region (`UK| ADULT`, `US| Adult`) share one, so locking one locks them all, the safer mistake
 * for a lock. One with no name to normalise is keyed by its source too, as provider category ids
 * are only unique within a provider.
 */
fun categoryGroupKey(category: Category): String =
    ChannelNameNormalizer.normalize(category.name).groupKey.ifEmpty { "${category.sourceId}:${category.id}" }

/** Folds [categories] into one [CategoryGroup] per [categoryGroupKey], in their order. */
fun foldCategories(categories: List<Category>): List<CategoryGroup> {
    val groups = LinkedHashMap<String, Pair<String, MutableList<String>>>()
    for (category in categories) {
        val label = ChannelNameNormalizer.normalize(category.name).baseName
        groups.getOrPut(categoryGroupKey(category)) { label to mutableListOf() }.second += category.id
    }
    return groups.map { (key, value) -> CategoryGroup(key, value.first, value.second) }
}

/**
 * The Channels a viewer may see in a scope, with the rules about what one Channel is applied once,
 * here, rather than in each screen's flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelLineup(
    private val db: OpenTvDatabase,
    private val favourites: Favourites,
    private val lockedKeys: StateFlow<Set<String>>,
    private val unlocked: StateFlow<Boolean>,
    private val sharing: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val channelDao = db.channels()
    private val categories = db.categories().observe(StreamKind.LIVE)

    /**
     * The categories the Parental Lock is hiding right now, as (source, category id): none once
     * the session is unlocked. Category ids are only unique within a provider.
     */
    private val locked: Flow<Set<Pair<Long, String>>> =
        combine(categories, lockedKeys, unlocked) { categories, keys, unlocked ->
            if (unlocked || keys.isEmpty()) {
                emptySet()
            } else {
                categories.filter { categoryGroupKey(it) in keys }.mapTo(HashSet()) { it.sourceId to it.id }
            }
        }

    /**
     * Every row a viewer may see, folded into Channels. Built over the whole catalogue whatever the
     * scope, because which Channel a row joins depends on rows outside it.
     */
    private val browsable: Flow<Catalogue> =
        combine(channelDao.observeEvery(), locked) { rows, locked -> Catalogue(rows) { it.isShown(locked) } }
            // One fold for every screen open at once, off the main thread.
            .flowOn(Dispatchers.Default)
            .shareIn(sharing, SharingStarted.WhileSubscribed(SHARE_MILLIS), replay = 1)

    /** Every row, hidden and locked ones too: what the channel manager folds. */
    private val manageable: Flow<Catalogue> = channelDao.observeEvery().map { Catalogue(it) }
        .flowOn(Dispatchers.Default)
        .shareIn(sharing, SharingStarted.WhileSubscribed(SHARE_MILLIS), replay = 1)

    /** The scope channel up and down follow: All until a screen says otherwise. */
    private val zapScope = MutableStateFlow<LineupScope>(LineupScope.All())

    /** [zapScope]'s Channels, with the scope they're for; kept while the player watches [zapList]. */
    private val zapped: Flow<Pair<LineupScope, List<LineupChannel>>> =
        zapScope.flatMapLatest { scope -> channels(scope).map { scope to it } }
            .shareIn(sharing, SharingStarted.WhileSubscribed(SHARE_MILLIS), replay = 1)

    /**
     * Every row, hidden and locked ones too, of a Channel the active Profile has starred: what
     * lights the web manager's stars. Folds only the starred Channels' names, not the catalogue.
     */
    suspend fun favouriteRowIds(): Set<Long> {
        val rows = HashSet<Long>()
        for (id in favourites.channelIds().first()) {
            if (id !in rows) rows += channelOf(id, manage = true)?.qualities?.map { it.id } ?: listOf(id)
        }
        return rows
    }

    /** The categories' rail, folded by [categoryGroupKey]; locked ones are left out unless [includeLocked]. */
    fun categoryGroups(sourceId: Long? = null, includeLocked: Boolean = false): Flow<List<CategoryGroup>> =
        combine(categories, lockedKeys, unlocked) { categories, keys, unlocked ->
            val groups = foldCategories(categories.filter { sourceId == null || it.sourceId == sourceId })
            if (includeLocked || unlocked) groups else groups.filter { it.key !in keys }
        }

    fun channels(scope: LineupScope): Flow<List<LineupChannel>> {
        if (scope is LineupScope.Favourites) {
            // Folded from just their names' rows: Home shows them on start, and a handful of names
            // is far cheaper than the whole catalogue.
            return combine(favourites.channels(), locked, favourites.channelIds(), ::Triple)
                .mapLatest { (rows, locked, starred) ->
                    val named = rows.flatMap { channelDao.sameNamed(it) }.distinctBy { it.id }
                    Catalogue(named) { it.isShown(locked) }
                        .channelsOf(rows.from(scope.sourceId))
                        .map { it.starredBy(starred) }
                }
        }
        val catalogue = when (scope) {
            is LineupScope.ManageSearch, is LineupScope.ManageGroup -> manageable
            else -> browsable
        }
        return combine(rowsIn(scope, channelDao, categories), catalogue, favourites.channelIds()) {
                rows,
                catalogue,
                starred,
            ->
            catalogue.channelsOf(rows).map { it.starredBy(starred) }
        }
    }

    /**
     * Stars or unstars every Quality of [channel], so the star doesn't depend on which one is
     * playing. Storage stays per provider stream (ADR 0001).
     */
    suspend fun setFavourite(channel: LineupChannel, favourite: Boolean) {
        channel.qualities.forEach { favourites.set(it, favourite) }
    }

    /**
     * [LineupChannel.guideId] by what the guide has stored: for Home's now and next, series-rule
     * rescans and catch-up, which don't draw the guide's window.
     */
    suspend fun guideId(channel: LineupChannel): String? =
        channel.guideCandidates.firstOrNull { db.programmes().hasProgrammes(it) }

    /**
     * Channel up and down follow [scope] from now on: the guide's current scope, or All from
     * screens without one. Kept here rather than as a list handed to the player, so it's never
     * stale and never one screen's list when another started playback.
     */
    fun zapFrom(scope: LineupScope) {
        zapScope.value = scope
    }

    /** The Channels channel up and down step through, in order, as they change: the player's list. */
    val zapList: Flow<List<LineupChannel>> = zapped.map { it.second }

    /**
     * The Channels channel up and down step through. When the one [around] is a Quality of isn't
     * among them (played from a reminder, say, after browsing a category), they're every Channel.
     */
    suspend fun zapChannels(around: Long? = null): List<LineupChannel> {
        val channels = zapped.first { it.first == zapScope.value }.second
        if (around == null || zapScope.value == LineupScope.All() || channels.any { it.has(around) }) return channels
        zapFrom(LineupScope.All())
        return zapped.first { it.first == zapScope.value }.second
    }

    /**
     * The Channel [delta] steps from the one [fromChannelId] is a Quality of, or null past either
     * end. Any Quality zaps from its own Channel, not only the primary.
     */
    suspend fun zap(fromChannelId: Long, delta: Int): LineupChannel? {
        val channels = zapChannels(around = fromChannelId)
        val from = channels.indexOfFirst { it.has(fromChannelId) }
        return if (from < 0) null else channels.getOrNull(from + delta)
    }

    /** [channelOf] as it changes: null while the row is gone, hidden or locked. */
    fun channel(channelId: Long): Flow<LineupChannel?> =
        // The count re-emits on any change to the channels table: only this row's names are folded.
        combine(channelDao.observeVisibleCount(), locked, favourites.channelIds()) { _, _, _ -> }
            .mapLatest { channelOf(channelId) }
            .distinctUntilChanged()

    /**
     * The Channel [channelId] is a Quality of, or null when it's gone, hidden or locked. With
     * [manage], hidden and locked rows count, as the channel manager shows them.
     */
    suspend fun channelOf(channelId: Long, manage: Boolean = false): LineupChannel? {
        val row = channelDao.byId(channelId) ?: return null
        val locked = if (manage) emptySet() else locked.first()
        // Only rows of the same name can share its Channel, so the fold needn't see the catalogue.
        return Catalogue(channelDao.sameNamed(row)) { manage || it.isShown(locked) }
            .channelContaining(row.id)
            ?.starredBy(favourites.channelIds().first())
    }

    /**
     * Rows folded into Channels. Which Channel a row joins is decided over every row, hidden and
     * locked ones too, so the guide and the channel manager agree on it; only [shown] rows are
     * Qualities.
     */
    private class Catalogue(rows: List<Channel>, shown: (Channel) -> Boolean = { true }) {
        private val keyOf = channelKeys(rows)
        private val shownIds = rows.filter(shown).mapTo(HashSet()) { it.id }
        private val qualities: Map<String, List<Channel>> = rows.filter { it.id in shownIds }
            .groupBy { keyOf.getValue(it.id) }
            .mapValues { (_, group) -> group.sortedWith(BEST_FIRST) }

        fun channelContaining(rowId: Long): LineupChannel? {
            if (rowId !in shownIds) return null
            val key = keyOf.getValue(rowId)
            val qualities = qualities.getValue(key)
            return LineupChannel(qualities.first(), qualities, key = key)
        }

        /** One Channel per [rows]' Channel, in the order they first appear, each played from the first of them. */
        fun channelsOf(rows: List<Channel>): List<LineupChannel> {
            val firstRow = LinkedHashMap<String, Channel>()
            for (row in rows) {
                if (row.id !in shownIds) continue
                val key = keyOf.getValue(row.id)
                val best = firstRow[key]
                if (best == null || BEST_FIRST.compare(row, best) < 0) firstRow[key] = row
            }
            return firstRow.map { (key, primary) -> LineupChannel(primary, qualities.getValue(key), key = key) }
        }
    }

    private companion object {
        const val SHARE_MILLIS = 5_000L
        val BEST_FIRST: Comparator<Channel> =
            compareByDescending<Channel> { it.qualityRank }.thenBy { it.sourceId }.thenBy { it.id }
    }
}

/**
 * [keys] with each lock saved under a nameless category's old key, its bare id, moved to the key it
 * has now, `source:id`, on every provider with that id, as the old key locked them all. Run once on
 * upgrade, so a lock the viewer set doesn't quietly lift.
 */
fun migratedLockKeys(keys: Set<String>, categories: List<Category>): Set<String> {
    val nameless = categories.filter { ChannelNameNormalizer.normalize(it.name).groupKey.isEmpty() }.groupBy { it.id }
    return keys.flatMapTo(HashSet()) { key -> nameless[key]?.map(::categoryGroupKey) ?: listOf(key) }
}

/** The rows [scope] shows, in its order, before they're folded into Channels. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun rowsIn(scope: LineupScope, channelDao: ChannelDao, categories: Flow<List<Category>>): Flow<List<Channel>> =
    when (scope) {
        is LineupScope.All -> channelDao.observe(scope.sourceId, null)
        // Folded from their names' rows instead: see ChannelLineup.channels.
        is LineupScope.Favourites -> flowOf(emptyList())
        is LineupScope.Search -> channelDao.search(scope.query)
        is LineupScope.ManageSearch -> channelDao.searchIncludingHidden(scope.query)
        is LineupScope.Group -> categories.map { it.idsOf(scope.key, scope.sourceId) }.flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                channelDao.observeInCategories(ids).map {
                    it.from(scope.sourceId)
                }
            }
        }
        is LineupScope.ManageGroup -> categories.map { it.idsOf(scope.key, scope.sourceId) }.flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(
                    emptyList(),
                )
            } else {
                channelDao.observeInCategoriesIncludingHidden(scope.sourceId, ids)
            }
        }
    }

/** Every row, hidden ones too, that could share a Channel with [row]. */
private suspend fun ChannelDao.sameNamed(row: Channel): List<Channel> =
    if (row.groupKey.isEmpty()) listOf(row) else sameName(ChannelNameNormalizer.nameKeyOf(row.groupKey))

private fun List<Channel>.from(sourceId: Long?): List<Channel> =
    if (sourceId == null) this else filter { it.sourceId == sourceId }

/** The ids of the categories in the [CategoryGroup] keyed [key], optionally one provider's. */
private fun List<Category>.idsOf(key: String, sourceId: Long?): List<String> =
    filter { (sourceId == null || it.sourceId == sourceId) && categoryGroupKey(it) == key }.map { it.id }

/** Whether a viewer may see this row: not hidden, and not in a [locked] category. */
private fun Channel.isShown(locked: Set<Pair<Long, String>>): Boolean =
    !hidden && (categoryId == null || (sourceId to categoryId) !in locked)

/**
 * Which Channel each row belongs to, by row id. Rows share a Channel when they share a name and
 * region. A row with no region joins the one regional Channel of its name when there is exactly
 * one (`BBC One` joins `UK| BBC One`): with several there's no telling which it is, so it stays on
 * its own. That depends on the other rows, which is why it isn't stored in [Channel.groupKey].
 */
internal fun channelKeys(rows: List<Channel>): Map<Long, String> {
    val regionalByName = HashMap<String, MutableSet<String>>()
    for (row in rows) {
        if (':' in row.groupKey) {
            regionalByName.getOrPut(ChannelNameNormalizer.nameKeyOf(row.groupKey)) { HashSet() } += row.groupKey
        }
    }
    return rows.associate { row ->
        val key = when {
            row.groupKey.isEmpty() -> "#${row.id}"
            ':' in row.groupKey -> row.groupKey
            else -> regionalByName[row.groupKey]?.singleOrNull() ?: row.groupKey
        }
        row.id to key
    }
}
