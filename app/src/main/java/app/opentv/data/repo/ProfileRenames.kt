/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Profile
import app.opentv.sync.SyncProfileAdd
import app.opentv.sync.SyncProfileRename

/** Applies a Profile Rename from another device to the Profiles here. [Profiles] holds its lock around this. */
internal class ProfileRenames(
    private val db: OpenTvDatabase,
    private val viewerLibrary: ViewerLibrary,
    private val profileSettings: ProfileSettings,
    private val recordings: RecordingRepository,
    private val settings: AppSettings,
    private val removed: RemovedProfiles,
) {
    suspend fun apply(rename: SyncProfileRename, profiles: List<Profile>) {
        val from = profiles.firstOrNull { Profiles.sameName(it.name, rename.from) }
        val to = profiles.firstOrNull { Profiles.sameName(it.name, rename.to) }
        val history = ProfileHistory(settings.profileRenames(), settings.profileAdds())
        when {
            from == null -> {
                // The same viewer under a new name, and they were removed here.
                if (to == null && rename.from in removed) removed.add(rename.to)
            }
            // This device's Tom took the name after that Tom was renamed, so it's someone else.
            history.namedSince(rename.from, rename.atMillis) -> Unit
            to == null || to.id == from.id -> {
                db.profiles().rename(from.id, rename.to)
                removed.lift(rename.to)
            }
            else -> merge(from, to, rename.to)
        }
    }

    /**
     * Makes two Profiles one, called [name], newest wins wherever they conflict. The base Profile
     * survives whichever side it's on, since it can't be removed and every install has one. One
     * transaction, for the reason [Profiles.remove] is.
     */
    private suspend fun merge(from: Profile, to: Profile, name: String) {
        val (gone, kept) = if (from.id == Profiles.BASE_PROFILE_ID) to to from else from to to
        db.withTransaction {
            viewerLibrary.profileMerge.merge(gone.id, kept.id)
            profileSettings.mergeProfile(gone.id, kept.id)
            recordings.moveProfile(gone.id, kept.id)
            db.profiles().delete(gone.id)
            db.profiles().rename(kept.id, name)
        }
        if (settings.activeProfileId.value == gone.id) settings.setActiveProfile(kept.id)
    }
}

/**
 * What a name has meant over time, from the dated Profile Renames and adds a device knows about (ADR
 * 0005). A new Tom added after Tom became Thomas keeps what's dated after it, and anything older
 * labelled Tom still goes to Thomas.
 */
class ProfileHistory(renames: List<SyncProfileRename>, adds: List<SyncProfileAdd>) {
    private val renames = renames.sortedBy { it.atMillis }
    private val named = adds.map { it.name to it.atMillis } + renames.map { it.to to it.atMillis }

    /**
     * The name now of the Profile an item dated [atMillis] calls [label]. Only the label itself can
     * belong to a newer Profile: once an item has moved on to another name, it's that older viewer's.
     */
    fun currentName(label: String, atMillis: Long): String {
        var name = label
        var moved = false
        for (rename in renames) {
            val isThisOne = moved || !namedBetween(name, rename.atMillis, atMillis)
            if (Profiles.sameName(rename.from, name) && isThisOne) {
                name = rename.to
                moved = true
            }
        }
        return name
    }

    /** Whether a Profile took the name [name] after [atMillis], by being added or renamed onto it. */
    internal fun namedSince(name: String, atMillis: Long) = namedBetween(name, atMillis, Long.MAX_VALUE)

    private fun namedBetween(name: String, after: Long, until: Long) =
        named.any { (other, at) -> Profiles.sameName(other, name) && at > after && at <= until }
}
