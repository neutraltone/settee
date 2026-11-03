/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.db.ProfileDao
import app.opentv.data.model.Profile
import app.opentv.sync.SyncProfileAdd
import app.opentv.sync.SyncProfileRename
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
 * if a table with a `profileId` keeps a removed Profile's rows. A removed Profile's name goes on a
 * list that stays on this device, so sync can't bring it back, until the name is added here again.
 * Names stay a Profile's identity, so a rename travels as a dated record (see [ProfileHistory]).
 */
class Profiles(
    private val db: OpenTvDatabase,
    private val viewerLibrary: ViewerLibrary,
    private val profileSettings: ProfileSettings,
    private val recordings: RecordingRepository,
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.profiles()
    private val removed = RemovedProfiles(settings)
    private val renamer = ProfileRenames(db, viewerLibrary, profileSettings, recordings, settings, removed)

    // Name checks and the first Profile's seeding read before they write; two at once would let a
    // duplicate name, or two "Me"s, through.
    private val writes = Mutex()

    /** What the Profile Renames and adds known here say about names, for labelling a bundle's items. */
    val history: ProfileHistory get() = ProfileHistory(settings.profileRenames(), settings.profileAdds())

    /** Every Profile, oldest first. Never empty: the first ask on a fresh install creates "Me". */
    fun observeAll(): Flow<List<Profile>> = dao.observeAll().onStart { all() }

    suspend fun all(): List<Profile> = writes.withLock { dao.seeded(clock()) }

    /** Adds a Profile called [name]; returns its id, or null when the name is blank or taken. */
    suspend fun create(name: String): Long? = writes.withLock {
        val wanted = name.trim()
        if (wanted.isEmpty() || isNameTaken(wanted, dao.seeded(clock()))) return null
        removed.lift(wanted)
        settings.setProfileAdds(settings.profileAdds() + SyncProfileAdd(wanted, clock()))
        dao.insert(Profile(name = wanted, createdAtMillis = clock(), namedAtMillis = clock()))
    }

    /** Renames a Profile; returns false when there's no such Profile, or the name is blank or taken. */
    suspend fun rename(id: Long, name: String): Boolean = writes.withLock {
        val wanted = name.trim()
        val profiles = dao.seeded(clock())
        val profile = profiles.firstOrNull { it.id == id } ?: return false
        if (wanted.isEmpty() || isNameTaken(wanted, profiles, except = id)) return false
        if (profile.name != wanted) {
            settings.setProfileRenames(settings.profileRenames() + SyncProfileRename(profile.name, wanted, clock()))
        }
        dao.rename(id, wanted, clock())
        removed.lift(wanted)
        true
    }

    /** Every Profile's name, as they travel to the other side. */
    suspend fun export(): List<String> = all().map { it.name }

    /**
     * Adds the Profiles another device has and this one doesn't, unless they were removed here. Never
     * removes one: that would delete its history. [atMillis] is when the other side listed them, which
     * says who a reused name means.
     */
    suspend fun import(names: List<String>, atMillis: Long = clock()) {
        val history = history
        names.map { history.currentName(it, atMillis) }.distinct().forEach { findOrCreate(it) }
    }

    /**
     * The Profile another device calls [name], created here if there isn't one. Matches the way
     * [isNameTaken] does, so "tom" from the other side is this device's "Tom". Null for a blank name,
     * and for one removed on this device.
     */
    suspend fun findOrCreate(name: String): Long? = writes.withLock { findOrCreateLocked(name, namedAtMillis = 0) }

    private suspend fun findOrCreateLocked(name: String, namedAtMillis: Long): Long? {
        val wanted = name.trim()
        val existing = dao.seeded(clock()).firstOrNull { sameName(it.name, wanted) }
        return when {
            wanted.isEmpty() -> null
            existing != null -> existing.id
            wanted in removed -> null
            else -> dao.insert(Profile(name = wanted, createdAtMillis = clock(), namedAtMillis = namedAtMillis))
        }
    }

    /**
     * Applies the Profile Renames and adds this device hasn't seen, oldest first and each only once,
     * so Tom → Thomas → Tom can't loop and a record seen again on a later sync changes nothing.
     * On the same millisecond a rename goes first, since adding its old name has to follow it.
     */
    suspend fun importChanges(renames: List<SyncProfileRename>, adds: List<SyncProfileAdd>) = writes.withLock {
        // A blank name is no Profile's, so a record of one is neither applied nor passed on.
        val newRenames = renames.filter { it.to.isNotBlank() }.distinct() - settings.profileRenames().toSet()
        val newAdds = adds.distinct() - settings.profileAdds().toSet()
        val steps = newRenames.map { rename ->
            rename.atMillis to suspend {
                renamer.apply(rename, dao.seeded(clock()))
                settings.setProfileRenames(settings.profileRenames() + rename)
            }
        } + newAdds.map { add ->
            add.atMillis to suspend {
                findOrCreateLocked(add.name, add.atMillis)
                settings.setProfileAdds(settings.profileAdds() + add)
            }
        }
        steps.sortedBy { it.first }.forEach { it.second() }
    }

    /**
     * Removes a Profile and everything it owned; its recordings stay, for no one in particular.
     * Returns false for the base Profile, which can't be removed, and for one that isn't there.
     * Removing the active Profile makes the base Profile active. One transaction, which a sync's
     * writes order themselves against (see [mergeForProfiles]) so none lands under the old id.
     */
    suspend fun remove(id: Long): Boolean = writes.withLock {
        val profile = dao.all().firstOrNull { it.id == id }
        if (id == BASE_PROFILE_ID || profile == null) return false
        removed.add(profile.name)
        // Before its rows go, so progress the player is saving can't land under an id that's gone.
        if (settings.activeProfileId.value == id) settings.setActiveProfile(BASE_PROFILE_ID)
        db.withTransaction {
            viewerLibrary.profileRemoval.forget(id)
            profileSettings.forgetProfile(id)
            recordings.forgetProfile(id)
            dao.delete(id)
        }
        true
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

        internal fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

        // Every write seeds first: a Profile added before anything had read the list would otherwise
        // take id 1 and become the base Profile, and "Me" would never be created. Call under the lock.
        private suspend fun ProfileDao.seeded(now: Long): List<Profile> {
            val existing = all()
            if (existing.isNotEmpty()) return existing
            insert(Profile(name = FIRST_PROFILE_NAME, createdAtMillis = now))
            return all()
        }
    }
}

/**
 * Names of Profiles removed on this device, which sync must not bring back. They match the way
 * Profile names do, and only adding the name on this device lifts one (ADR 0005).
 */
internal class RemovedProfiles(private val settings: AppSettings) {
    operator fun contains(name: String) = settings.removedProfileNames().any { Profiles.sameName(it, name) }

    fun add(name: String) = settings.setRemovedProfileNames(settings.removedProfileNames() + name)

    fun lift(name: String) = settings.setRemovedProfileNames(
        settings.removedProfileNames().filterNot { Profiles.sameName(it, name) }.toSet(),
    )
}
