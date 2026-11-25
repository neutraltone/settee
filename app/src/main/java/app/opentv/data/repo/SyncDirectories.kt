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
import kotlinx.coroutines.flow.first

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
 * The latest time a change arriving in one bundle may claim. Every store takes an incoming change's
 * time through [clamp] before comparing it with [isNewerChange] and before storing it, so the time a
 * row keeps, and passes on to the next device, is the one that was compared. `SyncEngine.applyOne` works
 * out one per bundle; a time of zero (undated) stays zero, still the oldest possible.
 */
@JvmInline
value class ArrivalCeiling(val millis: Long) {
    fun clamp(atMillis: Long): Long = minOf(atMillis, millis)

    companion object {
        /** No limit: a change keeps the time it came with. Also for merges that aren't a sync, such as two Profiles. */
        val NONE = ArrivalCeiling(Long.MAX_VALUE)

        /**
         * The ceiling for a bundle made at [generatedAtMillis] arriving when this device's clock reads
         * [now] (ADR 0005). Nothing in a bundle can be newer than the bundle itself, so its own time
         * caps it, but only when that's believable: a bundle dated after [now] came from a clock set
         * ahead, and zero is an old bundle without a time. Either way [now] caps it, so a TV whose clock
         * is ahead can't date a change after everything the other TVs do from here on.
         */
        fun of(now: Long, generatedAtMillis: Long): ArrivalCeiling =
            ArrivalCeiling(if (generatedAtMillis in 1..now) generatedAtMillis else now)
    }
}

/**
 * Whether a change dated [incoming] from another device beats what's here, dated [here] (null when
 * this device has no record of the item): the one newest-wins rule every synced store imports by.
 * The newest wins and a tie keeps what's here. A time of zero means the bundle had none, which
 * counts as the oldest possible: it applies only where this device has never changed the item, so
 * an old file can't undo a change made here. [incoming] is taken through [ceiling] first.
 */
internal fun isNewerChange(incoming: Long, here: Long?, ceiling: ArrivalCeiling = ArrivalCeiling.NONE): Boolean {
    val dated = ceiling.clamp(incoming)
    return when {
        here == null -> true
        dated == 0L -> false
        else -> dated > here
    }
}

/**
 * This device's sources by id and by name, which [SourceRepository] keeps unique ignoring case and
 * surrounding spaces (ADR 0004). A name another device sends matches the same way, so "Sky " there is
 * "sky" here. One that matches nothing is followed through the provider [renames] this device knows,
 * oldest first, so an item from a device that hasn't heard of a rename still finds the provider.
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

    private fun matching(name: String) = sources.firstOrNull { SourceRepository.sameName(it.name, name) }?.id

    private fun renamed(label: String): String = renamesInOrder.fold(label.trim()) { name, rename ->
        if (SourceRepository.sameName(rename.from, name)) rename.to.trim() else name
    }
}

internal suspend fun SourceDao.directory(renames: List<SyncProviderRename> = emptyList()) =
    SourceDirectory(observeAll().first(), renames)
