/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * What [OpenTvApp.onFirstOpen] does once a viewer opens the app: the NAS sync, when they've turned
 * it on, alongside catching the catalogue up with the normaliser and then the launch refresh. The
 * steps come in from the app so this can be checked without an SMB share or a provider.
 */
internal class FirstOpenWork(
    private val nasAutoSync: Boolean,
    private val syncNas: suspend () -> Unit,
    private val catchUpNormaliser: suspend () -> Unit,
    private val launchRefresh: suspend () -> Unit,
) {
    // Each its own child of a supervisor, so a catalogue that fails doesn't cancel a NAS sync
    // half-way through writing this device's bundle. Running the catalogue in the scope's own
    // block instead would: a supervisor still cancels its children when its block throws.
    suspend fun run() = supervisorScope {
        if (nasAutoSync) launch { runCatching { syncNas() } }
        launch {
            catchUpNormaliser()
            launchRefresh()
        }
    }
}
