/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.pairing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [onExpiry] [millis] after the last [arm], unless [cancel]led first.
 *
 * Shared by [PairingServer] and [app.opentv.sync.SyncServer]: a session's timeout belongs to that
 * session, so stopping and starting again never lets an earlier session's timer end the new one.
 */
internal class SessionTimer(
    private val scope: CoroutineScope,
    private val millis: Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onExpiry: () -> Unit,
) {
    @Volatile private var running: Job? = null

    fun arm() {
        running?.cancel()
        running = scope.launch(dispatcher) {
            delay(millis)
            onExpiry()
        }
    }

    fun cancel() {
        running?.cancel()
        running = null
    }
}
