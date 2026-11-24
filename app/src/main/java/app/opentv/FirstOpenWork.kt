/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.StatusBus
import app.opentv.sync.NasSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * What [OpenTvApp.onFirstOpen] does once a viewer opens the app: the NAS sync, when they've turned
 * it on, alongside catching the catalogue up with the normaliser and then the launch refresh. The
 * steps come in from the app so this can be checked without an SMB share or a provider.
 *
 * No step's failure leaves [run]. This work starts as a screen opens, so a throw here (a full disk,
 * a query past the Stick's SQLite limits) killed the app a moment after Home appeared, and since the
 * catch-up remembers its version only on success, it did so again on every launch, before the
 * updater that could deliver a fix ever got to run.
 */
@Suppress("LongParameterList") // One per step and its reporting, so tests need no SMB share or provider.
internal class FirstOpenWork(
    private val nasAutoSync: Boolean,
    private val syncNas: suspend () -> NasSync.Result,
    /**
     * Where the sync's result goes, so Your devices can say it failed (#484). NasSync returns its
     * failures rather than throwing them, so without this one at launch went nowhere at all.
     */
    private val nasSynced: (NasSync.Result) -> Unit = {},
    private val catchUpNormaliser: suspend () -> Unit,
    private val launchRefresh: suspend () -> Unit,
    private val failed: (step: String, cause: Throwable) -> Unit = { _, _ -> },
    /** What the main screen's corner says while the NAS sync runs. */
    private val syncingNas: String,
) {
    // Each its own child of a supervisor, so a catalogue that fails doesn't cancel a NAS sync
    // half-way through writing this device's bundle. Running the catalogue in the scope's own
    // block instead would: a supervisor still cancels its children when its block throws.
    suspend fun run() = supervisorScope {
        if (nasAutoSync) launch { step("NAS sync") { StatusBus.during(syncingNas) { syncNasAndKeep() } } }
        launch {
            // A catch-up that fails still leaves the refresh to run: it brings fresh rows, normalised
            // as they're saved, and the catch-up is tried again on the next launch.
            step("normaliser catch-up") { catchUpNormaliser() }
            step("launch refresh") { launchRefresh() }
        }
    }

    private suspend fun syncNasAndKeep() {
        val result = syncNas()
        nasSynced(result)
        if (result is NasSync.Result.Failed) failed("NAS sync", result.cause)
    }

    // A cancelled launch (the process going away) is let through: catching it would keep a cancelled
    // coroutine running the next step.
    private suspend fun step(name: String, block: suspend () -> Unit) {
        runCatching { block() }.onFailure { if (it is CancellationException) throw it else failed(name, it) }
    }
}
