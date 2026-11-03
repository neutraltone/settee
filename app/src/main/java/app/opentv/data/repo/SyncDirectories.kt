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
 * Runs [write] unless Profile [profileId] has been removed, returning null if it has. A sync looks its
 * Profiles up before it writes, and may then spend a while writing, so a Profile can go in between.
 * One transaction, because [Profiles.remove] is one too: SQLite orders the two, so this write either
 * sees the Profile gone or is deleted with it. Holding a lock for a whole sync instead would leave
 * Who's watching? waiting on a slow one, and wouldn't cover progress arriving after it has returned.
 */
internal suspend fun <T> OpenTvDatabase.ifProfileExists(profileId: Long, write: suspend () -> T): T? =
    withTransaction { if (profiles().exists(profileId)) write() else null }

/** This device's sources by id and by name, which [SourceRepository] keeps unique (ADR 0004). */
internal class SourceDirectory(sources: List<Source>) {
    private val namesById = sources.associate { it.id to it.name }
    private val idsByName = sources.associate { it.name to it.id }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = idsByName[name]
}

internal suspend fun SourceDao.directory() = SourceDirectory(observeAll().first())
