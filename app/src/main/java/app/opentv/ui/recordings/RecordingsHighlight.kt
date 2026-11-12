/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * Hands the Highlight on when the viewer removes the row that has it. A row that leaves the list
 * while highlighted takes the Highlight with it, so the row that moves into its place asks for it,
 * or the row before when it was the last.
 */
internal class Heir {
    private val requester = FocusRequester()
    private var leaving: Pair<String, Int>? = null
    var key by mutableStateOf<String?>(null)
        private set
    private var keys = emptyList<String>()

    fun expectToLose(row: String) {
        leaving = row to keys.indexOf(row)
    }

    /**
     * A press on the list means the viewer has moved on from the row they acted on. Presses, not
     * focus changes: when a highlighted row goes, the Highlight falls to the first row by itself,
     * and that mustn't count as the viewer moving.
     */
    val watchPresses: Modifier = Modifier.onPreviewKeyEvent {
        leaving = null
        false
    }

    fun modifierFor(row: String): Modifier = if (row == key) Modifier.focusRequester(requester) else Modifier

    /** Whether the Highlight needs handing on now the list is [now]. */
    fun listed(now: List<String>): Boolean {
        keys = now
        val gone = leaving?.takeIf { it.first !in now } ?: return false
        leaving = null
        key = now.getOrNull(gone.second) ?: now.lastOrNull()
        return key != null
    }

    fun take() {
        runCatching { requester.requestFocus() }
    }
}

@Composable
internal fun rememberHeir(keys: List<String>): Heir {
    val heir = remember { Heir() }
    LaunchedEffect(keys) {
        if (heir.listed(keys)) {
            // The heir takes its requester in the next composition, and can't be asked before.
            withFrameNanos {}
            heir.take()
        }
    }
    return heir
}

/**
 * Leaves room above the highlighted row for its heading. A heading can't take the Highlight, so
 * moving up to a section's first row would otherwise stop with the heading off the top.
 */
@OptIn(ExperimentalFoundationApi::class)
internal class RoomForHeading(private val roomPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < roomPx -> offset - roomPx
        offset + size > containerSize -> offset + size - containerSize
        else -> 0f
    }
}
