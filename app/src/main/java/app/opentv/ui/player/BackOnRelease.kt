/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A Back press a player takes itself rather than leave to its back handler (#427). With the highlight
 * on the bar, the channel list or Try again, a Back key-down nobody consumes is spent by Compose moving
 * focus up to the player's key catcher, and the back handler never hears it. So the player takes every
 * Back its overlays leave alone ([backOnRelease]): it consumes the key-down and acts on the release,
 * which then can't reach whatever gets focus next, and Android never gets a release without the press
 * that began it.
 */
internal class BackOnRelease {
    private var pending: (() -> Unit)? = null

    /**
     * On a Back key-down: [step] runs when the key comes up. Decide what it does now, not on the
     * release: the bar can time out while Back is held, and Back that hid the bar mustn't then leave.
     */
    fun hold(step: () -> Unit) {
        pending = step
    }

    /** Takes the rest of a held Back press, running its step on the release; false for anything else. */
    fun consume(event: KeyEvent): Boolean {
        val step = pending
        if (step == null || !event.isBack()) return false
        val freshPress = event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0
        // A new press means the last one's release went astray: its step is stale, and this press
        // is decided afresh.
        if (freshPress) pending = null
        if (event.type == KeyEventType.KeyUp) {
            pending = null
            step()
        }
        return !freshPress
    }
}

/**
 * The player's key catcher taking Back: the rest of a held press before anything else sees it, and a
 * key-down that nothing focused consumed. A picker or the error panel takes Back itself, so they keep
 * theirs without being listed here. [step] says, on the key-down, what this press will do, or null to
 * leave it to the back handler.
 */
internal fun Modifier.backOnRelease(back: BackOnRelease, step: () -> (() -> Unit)?): Modifier = this
    .onPreviewKeyEvent { back.consume(it) }
    .onKeyEvent { event ->
        if (!event.isBack() || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val decided = step() ?: return@onKeyEvent false
        back.hold(decided)
        true
    }

internal fun KeyEvent.isBack() = key == Key.Back || key == Key.Escape
