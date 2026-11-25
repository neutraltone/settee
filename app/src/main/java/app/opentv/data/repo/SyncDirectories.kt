/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.ProfileDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.sync.SyncProviderRename

/**
 * This device's Profiles by id and by name. Sync items name their Profile, since row ids differ
 * from one device to the next, and names match the way [Profiles] matches them.
 */
internal class ProfileDirectory(private val profiles: List<Profile>) {
    private val namesById = profiles.associate { it.id to it.name }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = profiles.firstOrNull { Profiles.sameName(it.name, name) }?.id
}

internal suspend fun ProfileDao.directory() = ProfileDirectory(all())

/**
 * Merges synced [items] under the Profile each belongs to, leaving out any Profile that has been
 * removed, and returns how many [merge] took. A sync looks its Profiles up before it writes, and may
 * then spend a while writing, so a Profile can go in between. Each batch is one transaction that
 * starts by checking, and [Profiles.remove] is one too: SQLite orders them, so a write either sees
 * the Profile gone or is deleted with it. Holding a lock for a whole sync instead would leave Who's
 * watching? waiting on a slow one, and wouldn't cover progress arriving after it has returned.
 * Batches rather than one transaction an item, which a slow TV would feel on every sync.
 */
internal suspend fun <T> OpenTvDatabase.mergeForProfiles(
    items: List<T>,
    profileId: (T) -> Long,
    merge: suspend (T) -> Boolean,
): Int {
    var taken = 0
    for ((id, ofProfile) in items.groupBy(profileId)) {
        for (batch in ofProfile.chunked(SYNC_BATCH)) {
            taken += withTransaction { if (profiles().exists(id)) batch.count { merge(it) } else 0 }
        }
    }
    return taken
}

private const val SYNC_BATCH = 200

/**
 * A bundle's times on this device's clock (ADR 0005). A bundle made at [generatedAtMillis] by a TV
 * whose clock reads ahead of this one's [now] is that far ahead in everything it dates, so every time
 * in it is moved back by the gap; and nothing is dated after [now]. A bundle dated in the past, or
 * with no time, has no gap: a TV whose clock is behind passes on changes made elsewhere with the
 * times they were made. Moved by the gap rather than capped at now, so the same change arriving again
 * on a later sync gets the same time, and can't beat a change made here in between.
 */
class ArrivalClock private constructor(private val gapMillis: Long, private val nowMillis: Long) {
    /** [sentAtMillis] on this device's clock. Zero (undated) stays zero, and a dated change stays dated. */
    fun corrected(sentAtMillis: Long): Long =
        if (sentAtMillis <= 0) sentAtMillis else (sentAtMillis - gapMillis).coerceAtMost(nowMillis).coerceAtLeast(1)

    companion object {
        /** Times as they came. Also for merges that aren't a sync, such as two Profiles. */
        val NONE = ArrivalClock(0, Long.MAX_VALUE)

        fun of(now: Long, generatedAtMillis: Long) = ArrivalClock(maxOf(0, generatedAtMillis - now), now)
    }
}

/**
 * What a store needs to place one bundle's items here: the Profile Renames and adds that say whose an
 * item's Profile name is now, the bundle's [clock], and the provider renames this device knows, which
 * the stores' [SourceDirectory] follows to the name each provider has here.
 */
class SyncArrival(
    private val history: ProfileHistory = ProfileHistory.NONE,
    internal val clock: ArrivalClock = ArrivalClock.NONE,
    private val providerRenames: List<SyncProviderRename> = emptyList(),
) {
    /**
     * Where an item labelled [profile] and dated [sentAtMillis] lands. Its Profile is looked up by the
     * time it came with, the clock the rename and add records keep theirs on (they apply once each,
     * recognised by their time, so they aren't moved); a moved time on a TV whose clock is behind
     * would file a new Tom's progress under the Tom who became Thomas. The time it's compared and
     * stored with, and passed on with, is on this device's clock.
     */
    fun placed(profile: String, sentAtMillis: Long) =
        Placed(history.currentName(profile, sentAtMillis), clock.corrected(sentAtMillis))

    internal suspend fun sources(dao: SourceDao): SourceDirectory = dao.directory(providerRenames)

    /** An item's Profile, by its name here now, and its time on this device's clock. */
    data class Placed(val profile: String, val atMillis: Long)

    companion object {
        /** Labels as they are and times as they came: for a store's own tests, and items already placed. */
        val NONE = SyncArrival()
    }
}

/**
 * Whether a change dated [incoming] from another device beats what's here, dated [here] (null when
 * this device has no record of the item): the one newest-wins rule every synced store imports by.
 * The newest wins and a tie keeps what's here. A time of zero means the bundle had none, which
 * counts as the oldest possible: it applies only where this device has never changed the item, so
 * an old file can't undo a change made here. Stores pass [incoming] on this device's clock
 * ([SyncArrival.placed]).
 */
internal fun isNewerChange(incoming: Long, here: Long?): Boolean = when {
    here == null -> true
    incoming == 0L -> false
    else -> incoming > here
}

/**
 * This device's sources by id and by name, which [SourceRepository] keeps unique by [sameName] (ADR
 * 0004). A name another device sends matches the same way, so "Sky " there is "sky" here. One that
 * matches nothing is followed through the provider [renames] this device knows, oldest first, so an
 * item from a device that hasn't heard of a rename still finds the provider.
 */
internal class SourceDirectory(private val sources: List<Source>, renames: List<SyncProviderRename> = emptyList()) {
    private val namesById = sources.associate { it.id to it.name }
    private val renamesInOrder = renames.sortedBy { it.atMillis }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = matching(name) ?: matching(renamed(name))

    /**
     * The name this device keeps [label]'s items under: its own provider's exact name when one
     * matches, else where the renames lead, for a provider this device may add later.
     */
    fun currentName(label: String): String = id(label)?.let(::name) ?: renamed(label)

    private fun matching(name: String) = sources.firstOrNull { sameName(it.name, name) }?.id

    private fun renamed(label: String): String = renamesInOrder.fold(label.trim()) { name, rename ->
        if (sameName(rename.from, name)) rename.to.trim() else name
    }

    companion object {
        /**
         * Whether two provider names are the same provider's: ignoring case and surrounding spaces.
         * Kotlin's ignoreCase is Unicode, and SQLite's NOCASE only ASCII, so this never moves into SQL.
         */
        fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    }
}

internal suspend fun SourceDao.directory(renames: List<SyncProviderRename> = emptyList()) =
    SourceDirectory(everySource(), renames)
