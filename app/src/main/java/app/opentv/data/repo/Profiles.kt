/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppSettings
import app.opentv.data.db.ProfileDao
import app.opentv.data.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one owner of the Profiles on this device: creating, renaming and removing them, and the rules
 * that hold whoever asks. There is always at least one Profile; the base Profile can't be removed;
 * and no two share a name, since sync matches Profiles by name alone.
 *
 * Removing a Profile asks each store that keeps per-Profile state to forget it, rather than
 * deleting from every table here, so a store's tables stay its own business. `ProfilesTest` fails
 * if a table with a `profileId` keeps a removed Profile's rows.
 */
class Profiles(
    private val dao: ProfileDao,
    private val viewerLibrary: ViewerLibrary,
    private val profileSettings: ProfileSettings,
    private val recordings: RecordingRepository,
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Name checks and the first Profile's seeding read before they write; two at once would let a
    // duplicate name, or two "Me"s, through.
    private val writes = Mutex()

    /** Every Profile, oldest first. Never empty: the first ask on a fresh install creates "Me". */
    fun observeAll(): Flow<List<Profile>> = dao.observeAll().onStart { ensureOne() }

    suspend fun all(): List<Profile> = writes.withLock { seededProfiles() }

    /** Adds a Profile called [name]; returns its id, or null when the name is blank or taken. */
    suspend fun create(name: String): Long? = writes.withLock {
        val wanted = name.trim()
        if (wanted.isEmpty() || isNameTaken(wanted, seededProfiles())) return null
        dao.insert(Profile(name = wanted, createdAtMillis = clock()))
    }

    /** Renames a Profile; returns false when there's no such Profile, or the name is blank or taken. */
    suspend fun rename(id: Long, name: String): Boolean = writes.withLock {
        val wanted = name.trim()
        val profiles = seededProfiles()
        if (profiles.none { it.id == id }) return false
        if (wanted.isEmpty() || isNameTaken(wanted, profiles, except = id)) return false
        dao.rename(id, wanted)
        true
    }

    /**
     * The Profile another device calls [name], created here if there isn't one. Matches the way
     * [isNameTaken] does, so "tom" from the other side is this device's "Tom". Null for a blank name.
     */
    suspend fun findOrCreate(name: String): Long? = writes.withLock {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        seededProfiles().firstOrNull { sameName(it.name, wanted) }?.let { return it.id }
        dao.insert(Profile(name = wanted, createdAtMillis = clock()))
    }

    /**
     * Removes a Profile and everything it owned; its recordings stay, for no one in particular.
     * Returns false for the base Profile, which can't be removed, and for one that isn't there.
     * Removing the active Profile makes the base Profile active. The Profile's own row goes last, so
     * a removal cut short can be tried again. Holds [writes] throughout, so a sync can't find the
     * Profile by name and write under its id while its rows are being cleared.
     */
    suspend fun remove(id: Long): Boolean = writes.withLock {
        if (id == BASE_PROFILE_ID || dao.all().none { it.id == id }) return false
        viewerLibrary.profileRemoval.forget(id)
        profileSettings.forgetProfile(id)
        recordings.forgetProfile(id)
        dao.delete(id)
        if (settings.activeProfileId.value == id) settings.setActiveProfile(BASE_PROFILE_ID)
        true
    }

    private suspend fun ensureOne() = writes.withLock { seededProfiles() }

    // Every write seeds first: a Profile added before anything had read the list would otherwise
    // take id 1 and become the base Profile, and "Me" would never be created. Call under [writes].
    private suspend fun seededProfiles(): List<Profile> {
        val existing = dao.all()
        if (existing.isNotEmpty()) return existing
        dao.insert(Profile(name = FIRST_PROFILE_NAME, createdAtMillis = clock()))
        return dao.all()
    }

    companion object {
        /**
         * The Profile a fresh install starts with, and the one that can't be removed: the first
         * row in an empty table is always id 1.
         */
        const val BASE_PROFILE_ID = 1L

        private const val FIRST_PROFILE_NAME = "Me"

        /**
         * Whether another Profile in [among] (other than [except]) already has [name], ignoring case
         * and surrounding spaces. The Profiles screen asks this to say why it won't save.
         */
        fun isNameTaken(name: String, among: List<Profile>, except: Long? = null): Boolean =
            among.any { it.id != except && sameName(it.name, name) }

        private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)
    }
}
