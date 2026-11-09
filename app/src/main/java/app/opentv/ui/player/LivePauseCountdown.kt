/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Counts a live pause down from [pauseMillis], giving [onLeft] what's left at each tick, and
 * returns when the time runs out or [bufferFull] says the player can't hold any more. The time is
 * worked out for a typical bitrate. A stream above it fills the buffer early, and only the player
 * knows when (#182).
 */
internal suspend fun countDownLivePause(pauseMillis: Long, bufferFull: StateFlow<Boolean>, onLeft: (Long) -> Unit) {
    var left = pauseMillis
    onLeft(left)
    while (left > 0) {
        val full = withTimeoutOrNull(COUNTDOWN_TICK_MILLIS) { bufferFull.first { it } }
        if (full != null) return
        left -= COUNTDOWN_TICK_MILLIS
        onLeft(left)
    }
}

private const val COUNTDOWN_TICK_MILLIS = 1_000L
