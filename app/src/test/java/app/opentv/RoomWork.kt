/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.annotation.SuppressLint
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.DefaultTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.compose.ui.test.IdlingResource
import java.util.concurrent.atomic.AtomicInteger

/**
 * Room's background work in flight, as a Compose [IdlingResource] (#615): registered by
 * [setTvContent], so `waitForIdle`, and every assertion that waits for idle first, also waits for
 * a Room read to come back. Compose otherwise watches only the main looper and itself, so a check
 * could run before the rows it was about had arrived.
 *
 * It counts at `ArchTaskExecutor` rather than by giving each test database an executor of its own,
 * because a database built without one (in memory, or the production `OpenTvDatabase.build` that a
 * `ServiceLocator.Graph` without `openDatabase` opens) runs its queries, suspend and Flow alike, on
 * `ArchTaskExecutor.getIOThreadExecutor()`, which hands each task to whichever delegate is set at the
 * time. So one delegate covers every such database with no change to production code. A database
 * built with its own query executor, as [TestGraph]'s is, isn't counted here: the graph counts it
 * itself, and leaves out the reads it holds, so a test can check the screen while one is held.
 */
@SuppressLint("RestrictedApi")
object RoomWork : IdlingResource {
    private val inFlight = AtomicInteger()

    // Set once for the whole test JVM: the delegate only counts, so it's harmless to tests that never
    // register the resource, and setting it per test would lose count of work a previous test left.
    init {
        ArchTaskExecutor.getInstance().setDelegate(Counting)
    }

    override val isIdleNow: Boolean
        get() = inFlight.get() == 0

    override fun getDiagnosticMessageIfBusy(): String = "${inFlight.get()} Room task(s) still running"

    private object Counting : TaskExecutor() {
        private val default = DefaultTaskExecutor()

        override fun executeOnDiskIO(runnable: Runnable) {
            inFlight.incrementAndGet()
            try {
                default.executeOnDiskIO {
                    try {
                        runnable.run()
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            } catch (e: RuntimeException) {
                inFlight.decrementAndGet()
                throw e
            }
        }

        override fun postToMainThread(runnable: Runnable) = default.postToMainThread(runnable)

        override fun isMainThread(): Boolean = default.isMainThread
    }
}
