/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.room.InvalidationTracker
import app.opentv.data.db.OpenTvDatabase
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The guide ids that have programmes stored, held between changes to the guide. Reading them is a
 * walk of the whole guide's channel index, too much to repeat every minute for Home's Favourites.
 *
 * Told of changes by Room's invalidation tracker rather than by each place that writes programmes:
 * syncs, prunes and removing a feed all write them, and a set one of them forgot to clear would hide
 * a guide that has programmes until the app restarted.
 */
internal class StoredGuides(private val db: OpenTvDatabase) {
    /** Moves on with every change to the programmes table. */
    private val generation = AtomicLong()
    private val watching = AtomicBoolean()

    @Volatile
    private var held: Held? = null

    private class Held(val generation: Long, val ids: Set<String>)

    private val observer = object : InvalidationTracker.Observer(PROGRAMMES) {
        override fun onInvalidated(tables: Set<String>) {
            generation.incrementAndGet()
        }
    }

    suspend fun ids(): Set<String> {
        val tracker = db.invalidationTracker
        if (watching.compareAndSet(false, true)) {
            // Off the main thread: registering can set up the tracker's triggers in the database.
            withContext(Dispatchers.IO) { tracker.addObserver(observer) }
        } else {
            // Room tells observers of a write a moment after it commits; this catches up now, so a
            // guide stored a moment ago isn't missed.
            tracker.refresh(PROGRAMMES)
        }
        // Read before the query: a write while it runs moves the generation on, and the next call reads again.
        val current = generation.get()
        held?.takeIf { it.generation == current }?.let { return it.ids }
        val ids = db.programmes().channelIdsWithProgrammes().toHashSet()
        held = Held(current, ids)
        return ids
    }

    private companion object {
        const val PROGRAMMES = "programmes"
    }
}
