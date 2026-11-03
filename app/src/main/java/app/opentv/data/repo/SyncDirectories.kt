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
 * Whether a change dated [incoming] from another device beats what's here, dated [here] (null when
 * this device has no record of the item). The newest wins and a tie keeps what's here. A time of
 * zero means the bundle had none, which counts as the oldest possible: it applies only where this
 * device has never changed the item, so an old file can't undo a change made here.
 */
internal fun isNewerChange(incoming: Long, here: Long?): Boolean = when {
    here == null -> true
    incoming == 0L -> false
    else -> incoming > here
}

/** This device's sources by id and by name, which [SourceRepository] keeps unique (ADR 0004). */
internal class SourceDirectory(sources: List<Source>) {
    private val namesById = sources.associate { it.id to it.name }
    private val idsByName = sources.associate { it.name to it.id }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = idsByName[name]
}

internal suspend fun SourceDao.directory() = SourceDirectory(observeAll().first())
