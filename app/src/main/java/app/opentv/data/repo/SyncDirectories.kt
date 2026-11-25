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

/** This device's sources by id and by name, which [SourceRepository] keeps unique (ADR 0004). */
internal class SourceDirectory(sources: List<Source>) {
    private val namesById = sources.associate { it.id to it.name }
    private val idsByName = sources.associate { it.name to it.id }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = idsByName[name]
}

internal suspend fun SourceDao.directory() = SourceDirectory(observeAll().first())
