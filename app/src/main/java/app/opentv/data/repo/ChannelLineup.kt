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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
        combine(channelDao.observe(null, null), locked) { rows, locked -> Catalogue(rows.withoutLocked(locked)) }
            // One fold for every screen open at once (the guide and Home both want it), off the main thread.
            .flowOn(Dispatchers.Default)
            .shareIn(sharing, SharingStarted.WhileSubscribed(SHARE_MILLIS), replay = 1)

    /** Every row, hidden and locked ones too: what the channel manager folds. */
    private val manageable: Flow<Catalogue> = channelDao.observeEvery().map(::Catalogue)
        .flowOn(Dispatchers.Default)
        .shareIn(sharing, SharingStarted.WhileSubscribed(SHARE_MILLIS), replay = 1)

    /** Every row, hidden and locked ones too, of a Channel the active Profile has starred. */
    suspend fun favouriteRowIds(): Set<Long> {
        val starred = favourites.channelIds().first()
        if (starred.isEmpty()) return emptySet()
        return manageable.first().rowsOfChannelsWith(starred)
    }

    /** The Channels channel up and down step through: All until a screen says otherwise. */
    @Volatile
    private var zapScope: LineupScope = LineupScope.All()

    /** The categories' rail, folded by [categoryGroupKey]; locked ones are left out unless [includeLocked]. */
    fun categoryGroups(sourceId: Long? = null, includeLocked: Boolean = false): Flow<List<CategoryGroup>> =
        combine(categories, lockedKeys, unlocked) { categories, keys, unlocked ->
            val groups = foldCategories(categories.filter { sourceId == null || it.sourceId == sourceId })
            if (includeLocked || unlocked) groups else groups.filter { it.key !in keys }
        }

    fun channels(scope: LineupScope): Flow<List<LineupChannel>> {
        val catalogue = when (scope) {
            is LineupScope.ManageSearch, is LineupScope.ManageGroup -> manageable
            else -> browsable
        }
        return combine(rowsIn(scope, channelDao, categories, favourites), catalogue, favourites.channelIds()) {
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
        zapScope = scope
    }

    /** The Channels channel up and down step through, in order: the player's channel list. */
    suspend fun zapChannels(): List<LineupChannel> = channels(zapScope).first()

    /**
     * The Channel [delta] steps from the one [fromChannelId] is a Quality of, or null past either
     * end. Any Quality zaps from its own Channel, not only the primary.
     */
    suspend fun zap(fromChannelId: Long, delta: Int): LineupChannel? {
        val channels = zapChannels()
        val from = channels.indexOfFirst { channel -> channel.qualities.any { it.id == fromChannelId } }
        if (from < 0) return null
        return channels.getOrNull(from + delta)
    }

    /** [channelOf] as it changes: null while the row is gone, hidden or locked. */
    fun channel(channelId: Long): Flow<LineupChannel?> =
        combine(browsable, favourites.channelIds()) { catalogue, starred ->
            catalogue.channelContaining(channelId)?.starredBy(starred)
        }.distinctUntilChanged()

    /**
     * The Channel [channelId] is a Quality of, or null when it's gone, hidden or locked. With
     * [manage], hidden and locked rows count, as the channel manager shows them.
     */
    suspend fun channelOf(channelId: Long, manage: Boolean = false): LineupChannel? {
        val locked = if (manage) emptySet() else locked.first()
        val row = channelDao.byId(channelId)?.takeUnless { (it.hidden && !manage) || it.isLocked(locked) }
            ?: return null
        // Only rows of the same name can share its Channel, so the fold needn't see the catalogue.
        val named = if (row.groupKey.isEmpty()) {
            listOf(row)
        } else {
            channelDao.sameName(
                ChannelNameNormalizer.nameKeyOf(row.groupKey),
                includeHidden = manage,
            ).withoutLocked(locked)
        }
        return Catalogue(named).channelContaining(row.id)?.starredBy(favourites.channelIds().first())
    }

    private class Catalogue(rows: List<Channel>) {
        private val keyOf = channelKeys(rows)
        private val qualities: Map<String, List<Channel>> =
            rows.groupBy { keyOf.getValue(it.id) }.mapValues { (_, group) -> group.sortedWith(BEST_FIRST) }

        fun rowsOfChannelsWith(rowIds: Set<Long>): Set<Long> {
            val keys = rowIds.mapNotNullTo(HashSet()) { keyOf[it] }
            return keys.flatMapTo(HashSet()) { key -> qualities.getValue(key).map { it.id } }
        }

        fun channelContaining(rowId: Long): LineupChannel? {
            val key = keyOf[rowId] ?: return null
            val qualities = qualities.getValue(key)
            return LineupChannel(qualities.first(), qualities, key = key)
        }

        /** One Channel per [rows]' Channel, in the order they first appear, each played from the first of them. */
        fun channelsOf(rows: List<Channel>): List<LineupChannel> {
            val firstRow = LinkedHashMap<String, Channel>()
            for (row in rows) {
                val key = keyOf[row.id] ?: continue
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

/** The rows [scope] shows, in its order, before they're folded into Channels. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun rowsIn(
    scope: LineupScope,
    channelDao: ChannelDao,
    categories: Flow<List<Category>>,
    favourites: Favourites,
): Flow<List<Channel>> = when (scope) {
    is LineupScope.All -> channelDao.observe(scope.sourceId, null)
    is LineupScope.Favourites -> favourites.channels().map { it.from(scope.sourceId) }
    is LineupScope.Search -> channelDao.search(scope.query)
    is LineupScope.ManageSearch -> channelDao.searchIncludingHidden(scope.query)
    is LineupScope.Group -> categories.map { it.idsOf(scope.key, scope.sourceId) }.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList()) else channelDao.observeInCategories(ids).map { it.from(scope.sourceId) }
    }
    is LineupScope.ManageGroup -> categories.map { it.idsOf(scope.key, scope.sourceId) }.flatMapLatest { ids ->
        if (ids.isEmpty()) flowOf(emptyList()) else channelDao.observeInCategoriesIncludingHidden(scope.sourceId, ids)
    }
}

private fun List<Channel>.from(sourceId: Long?): List<Channel> =
    if (sourceId == null) this else filter { it.sourceId == sourceId }

/** The ids of the categories in the [CategoryGroup] keyed [key], optionally one provider's. */
private fun List<Category>.idsOf(key: String, sourceId: Long?): List<String> =
    filter { (sourceId == null || it.sourceId == sourceId) && categoryGroupKey(it) == key }.map { it.id }

private fun LineupChannel.starredBy(starred: Set<Long>): LineupChannel =
    copy(favourite = qualities.any { it.id in starred })

private fun Channel.isLocked(locked: Set<Pair<Long, String>>): Boolean =
    categoryId != null && (sourceId to categoryId) in locked

private fun List<Channel>.withoutLocked(locked: Set<Pair<Long, String>>): List<Channel> =
    if (locked.isEmpty()) this else filterNot { it.isLocked(locked) }

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
