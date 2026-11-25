/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.withTransaction
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.repo.SourceDirectory.Companion.sameName
import app.opentv.sync.SyncProviderRename
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider renames (ADR 0004): the dated records of one provider's name becoming another, made here
 * or heard of from another device, kept in settings so they outlive a database rebuild, as Profile
 * Renames do. Renaming a provider takes everything kept by its name along to the new one.
 */
class ProviderRenames(
    private val db: OpenTvDatabase,
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.sources()

    // Name checks read before they write; two saves at once, or a save during a sync's renames, would
    // otherwise let two sources take one name.
    private val names = Mutex()

    /** Runs [block] holding the lock every change to a provider's name takes. */
    internal suspend fun <T> naming(block: suspend () -> T): T = names.withLock { block() }

    /**
     * Every rename made here or heard of, as they travel to the other side: those still waiting to
     * apply here too, for the devices they can apply on.
     */
    fun all(): List<SyncProviderRename> = (settings.providerRenames() + settings.pendingProviderRenames()).distinct()

    /** This device's providers by name, following every rename known here. */
    internal suspend fun directory(): SourceDirectory = dao.directory(all())

    /**
     * Saves an edit to an existing provider, under [naming]. A new name takes along everything kept
     * by the old one, and is recorded so the other devices rename theirs.
     */
    internal suspend fun edited(source: Source) {
        val before = dao.byId(source.id)?.name
        val renamed = before != null && before != source.name
        db.withTransaction {
            dao.updateEdit(source)
            if (renamed && before != null) db.favouritesSourceRenamed(before, source.name)
        }
        if (renamed && before != null) {
            settings.setProviderRenames(settings.providerRenames() + SyncProviderRename(before, source.name, clock()))
            carrySettings(before, source.name)
        }
    }

    /** After a provider is added, under [naming]: applies the renames waiting for a provider of its name. */
    internal suspend fun added() = applyWaiting(ArrivalClock.NONE)

    /**
     * Applies the renames from another device this one hasn't seen, oldest first and each only once,
     * comparing their times on this device's clock ([arrival]). One that can't apply yet waits, and
     * is tried again with each sync and each provider added: a device can hear of Sky becoming Sky UK
     * before it hears of Provider becoming Sky.
     */
    suspend fun import(renames: List<SyncProviderRename>, arrival: ArrivalClock = ArrivalClock.NONE) = naming {
        val known = settings.providerRenames().toSet()
        val fresh = renames.filter { it.to.isNotBlank() && it !in known }
        settings.setPendingProviderRenames((settings.pendingProviderRenames() + fresh).distinct())
        applyWaiting(arrival)
    }

    private suspend fun applyWaiting(arrival: ArrivalClock) {
        val waiting = settings.pendingProviderRenames().sortedBy { it.atMillis }
        if (waiting.isEmpty()) return
        val stillWaiting = waiting.filterNot { rename ->
            settle(rename, arrival).also { done ->
                if (done) settings.setProviderRenames(settings.providerRenames() + rename)
            }
        }
        settings.setPendingProviderRenames(stillWaiting)
    }

    /**
     * Applies [rename], unless this device named its provider after the rename was made, since its
     * viewer chose that name later; either way it's done with. False while it can't apply: no
     * provider here has its old name, or another already has the new one (two can't become one).
     */
    private suspend fun settle(rename: SyncProviderRename, arrival: ArrivalClock): Boolean {
        val sources = dao.all()
        val from = sources.firstOrNull { sameName(it.name, rename.from) } ?: return false
        val to = rename.to.trim()
        val clash = sources.any { it.id != from.id && sameName(it.name, to) }
        val madeAt = arrival.corrected(rename.atMillis)
        val namedSince = settings.providerRenames().any { sameName(it.to, from.name) && it.atMillis > madeAt }
        if (!clash && from.name != to && !namedSince) rename(from, to)
        return !clash
    }

    private suspend fun rename(from: Source, to: String) {
        db.withTransaction {
            dao.rename(from.id, to)
            db.favouritesSourceRenamed(from.name, to)
        }
        carrySettings(from.name, to)
    }

    // The database's rows keyed by a provider's name move with it above; Parental Lock's keys for
    // nameless categories, and the episodes sync stopped asking the provider for, hold the name too.
    private fun carrySettings(from: String, to: String) {
        settings.rekeyCategoryLocks { key -> NamelessCategory.relabelled(key) { if (it == from) to else it } }
        settings.carryEpisodesNotFound(from, to)
    }
}
