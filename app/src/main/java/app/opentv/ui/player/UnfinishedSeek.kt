/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The seek bar while it's picked up: where the viewer has moved it to, before they put it down.
 *
 * Picking up is its own step (OK on the bar) so that Left and Right can move the Highlight along the
 * row the rest of the time. Before that, the bar seeked on Left and Right and finished on their
 * key-up, which also came from the press that moved the Highlight onto it.
 */
class UnfinishedSeek {
    /** Where the bar has been moved to, which it shows instead of playback; null while it's down. */
    var position by mutableStateOf<Float?>(null)
        private set

    /** Changes whenever the viewer does something with the bar, which puts it back after a while with none. */
    var activity by mutableIntStateOf(0)
        private set

    /** OK on the bar picked it up at [at], where playback is. */
    fun pickUp(at: Float) = pressed(to = at)

    /** Left or right on the picked-up bar, or a finger, has moved it to [to]. */
    fun pressed(to: Float) {
        position = to
        activity++
    }

    /** OK, or a finger lifting, put the bar down: where to seek to, if it was picked up. */
    fun putDown(): Long? {
        val target = position?.toLong()
        drop()
        return target
    }

    /** Puts the bar back where it started, leaving playback where it was. */
    fun drop() {
        position = null
    }
}

/**
 * Puts a picked-up bar back where it started after [PICKED_UP_WAIT_MILLIS] with no press, in every
 * player. Not tied to the controls hiding: a paused film never hides, and the Next Episode card can take
 * the Highlight from a picked-up bar. A held key repeats, so it counts as activity; a finger can rest
 * without moving, so the wait doesn't start while one is [dragging].
 */
@Composable
internal fun PutsBackWhenLeft(seek: UnfinishedSeek, dragging: Boolean) {
    LaunchedEffect(seek.activity, dragging) {
        if (seek.position != null && !dragging) {
            delay(PICKED_UP_WAIT_MILLIS)
            seek.drop()
        }
    }
}

/** How long a picked-up bar waits for a press before going back. To adjust once tried on the Stick. */
internal const val PICKED_UP_WAIT_MILLIS = 3_000L
