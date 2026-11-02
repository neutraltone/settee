/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

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

/** This device's sources by id and by name, which [SourceRepository] keeps unique (ADR 0004). */
internal class SourceDirectory(sources: List<Source>) {
    private val namesById = sources.associate { it.id to it.name }
    private val idsByName = sources.associate { it.name to it.id }

    fun name(id: Long): String? = namesById[id]

    fun id(name: String): Long? = idsByName[name]
}

internal suspend fun SourceDao.directory() = SourceDirectory(observeAll().first())
