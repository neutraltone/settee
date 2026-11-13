/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.InvalidationTracker
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.GuideFingerprint
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The guide ids that have programmes stored, held between changes to the guide. Reading them is a
 * walk of the whole guide's channel index, too much to repeat every minute for Home's Favourites.
 *
 * Two signals say the guide changed. A [GuideFingerprint], read on every call, catches what a sync
 * and a prune do the moment they commit. Room's invalidation tracker catches the rest, such as a
 * feed being removed, a moment after; it alone isn't enough, because Room tells observers on its
 * own thread after the write has returned, so a guide stored a moment ago could still be missed.
 */
internal class StoredGuides(private val db: OpenTvDatabase) {
    /** Moves on with every change to the programmes table Room tells us of. */
    private val generation = AtomicLong()
    private val registering = Mutex()

    @Volatile
    private var watching = false

    @Volatile
    private var held: Held? = null

    private class Held(val generation: Long, val fingerprint: GuideFingerprint, val ids: Set<String>)

    private val observer = object : InvalidationTracker.Observer(PROGRAMMES) {
        override fun onInvalidated(tables: Set<String>) {
            generation.incrementAndGet()
        }
    }

    suspend fun ids(): Set<String> {
        watch()
        // Both read before the ids: a write while they're read moves one on, and the next call reads again.
        val current = generation.get()
        val fingerprint = db.programmes().guideFingerprint()
        held?.takeIf { it.generation == current && it.fingerprint == fingerprint }?.let { return it.ids }
        val ids = db.programmes().channelIdsWithProgrammes().toHashSet()
        held = Held(current, fingerprint, ids)
        return ids
    }

    /**
     * Registers with Room once. Marked as watching only once registered, under a lock, so a first call
     * cancelled part-way, or a second arriving meanwhile, can't leave nothing watching for good.
     */
    private suspend fun watch() {
        if (watching) return
        registering.withLock {
            if (watching) return
            // Off the main thread: registering can set up the tracker's triggers in the database.
            withContext(Dispatchers.IO) { db.invalidationTracker.addObserver(observer) }
            watching = true
        }
    }

    private companion object {
        const val PROGRAMMES = "programmes"
    }
}
