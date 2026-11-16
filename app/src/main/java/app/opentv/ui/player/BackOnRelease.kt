/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * A Back press a player takes itself rather than leave to its back handler (#427). With the highlight
 * on the bar, the channel list or Try again, an unconsumed Back key-down is spent by Compose moving
 * focus up to the player's key catcher, and the back handler never hears it. So the player consumes
 * the key-down and acts on the release: the release then can't reach whatever gets focus next, and
 * Android never gets a release without the press that began it.
 */
internal class BackOnRelease {
    private var pending: (() -> Unit)? = null

    /** On a Back key-down: [step] runs when the key comes up. Decided now, as the screen may change before then. */
    fun hold(step: () -> Unit) {
        pending = step
    }

    /** Takes the rest of a held Back press, running its step on the release; false for anything else. */
    fun consume(event: KeyEvent): Boolean {
        val step = pending
        if (step == null || !event.isBack()) return false
        if (event.type == KeyEventType.KeyUp) {
            pending = null
            step()
        }
        return true
    }
}

internal fun KeyEvent.isBack() = key == Key.Back || key == Key.Escape
