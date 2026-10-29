/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells [onSettled] which channel the viewer settled on in the live player: one they stayed on for
 * [settleMillis] without moving on. Zapping past a channel says nothing about wanting it on Home.
 */
class SettledChannel(
    private val scope: CoroutineScope,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val onSettled: suspend (Channel) -> Unit,
) {
    private var waiting: Job? = null

    /** The player has moved to [channel]. */
    fun tunedTo(channel: Channel) {
        waiting?.cancel()
        waiting = scope.launch {
            delay(settleMillis)
            onSettled(channel)
        }
    }

    /** The viewer has left the player, so whatever was showing wasn't settled on. */
    fun stopped() {
        waiting?.cancel()
        waiting = null
    }

    private companion object {
        const val SETTLE_MILLIS = 10_000L
    }
}
