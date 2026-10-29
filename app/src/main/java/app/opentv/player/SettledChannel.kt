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
 * Tells [onSettled] which channel the viewer settled on in the live player: one that played in
 * front of them for [settleMillis] without a break. Zapping past a channel says nothing about
 * wanting it on Home, and nor does one that never got going or played on behind another app, so
 * the time only counts while the player says it's [playing].
 */
class SettledChannel(
    private val scope: CoroutineScope,
    private val settleMillis: Long = SETTLE_MILLIS,
    private val onSettled: suspend (Channel) -> Unit,
) {
    private var channel: Channel? = null
    private var isPlaying = false
    private var waiting: Job? = null

    /** The player has moved to [channel]. */
    fun tunedTo(channel: Channel) {
        this.channel = channel
        restart()
    }

    /** Whether [tunedTo]'s channel is playing in front of the viewer; a break starts the count again. */
    fun playing(isPlaying: Boolean) {
        if (isPlaying == this.isPlaying) return
        this.isPlaying = isPlaying
        restart()
    }

    /** The viewer has left the player, so whatever was showing wasn't settled on. */
    fun stopped() {
        channel = null
        restart()
    }

    private fun restart() {
        waiting?.cancel()
        val settling = channel?.takeIf { isPlaying } ?: return
        waiting = scope.launch {
            delay(settleMillis)
            onSettled(settling)
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 10_000L
    }
}
