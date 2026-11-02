/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The seek the viewer has started on the player's seek bar and not yet let go of.
 *
 * The slider finishes a seek on any key-up it receives, including the key-up of the press that moved
 * focus onto it, so "finished" only counts when a press on the bar is under way. Trusting the slider
 * sent the film back to wherever the bar was last dragged, or to the start.
 */
class UnfinishedSeek {
    /** Where the seek has got to, which the bar shows instead of playback; null when there is none. */
    var position by mutableStateOf<Float?>(null)
        private set

    /**
     * Changes whenever the viewer does something with the seek. It lapses after a while with no change,
     * however it came to be left: away from the bar, back on it without pressing, or a lost key-up.
     */
    var activity by mutableIntStateOf(0)
        private set

    private var pressing = false
    private var away = false

    /** Left or right on the bar, or a drag, has moved the seek on to [to]. */
    fun pressed(to: Float) {
        position = to
        pressing = true
        away = false
        activity++
    }

    /** The key or finger came up: where to seek to, if that finished a seek. */
    fun released(): Long? {
        if (!pressing) return null
        val finished = position?.toLong()
        drop()
        return finished
    }

    /** The bar lost focus. A seek in hand stays, for now, but no press is under way. */
    fun left() {
        pressing = false
        if (position != null && !away) {
            away = true
            activity++
        }
    }

    /** The bar has focus again. */
    fun reached() {
        if (away) {
            away = false
            activity++
        }
    }

    /** Forgets the seek, leaving playback where it was. */
    fun drop() {
        position = null
        pressing = false
        away = false
    }
}
