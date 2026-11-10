/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Which card of a [Shelf] the viewer was last on, by its key, so coming back into the row lands on
 * that card wherever it now stands, rather than on whichever card is nearest the one they left. By
 * position, a large card above a row of small ones sent Down to the row's second card.
 */
@Stable
class ShelfMemory internal constructor(last: MutableState<String?>) {
    internal val requester = FocusRequester()

    /** The key of the card the Highlight was last on in this row; null until the viewer has been there. */
    var lastKey by last
        internal set

    /** The row's keys as last drawn, so a remembered card that has left gives way to the first. */
    internal var keys: List<String> by mutableStateOf(emptyList())

    /** While Up or Down leaves the row, the other cards are out of reach ([Shelf]). */
    internal var leaving = false

    internal val target: String?
        get() = lastKey?.takeIf { it in keys } ?: keys.firstOrNull()
}

/**
 * A [ShelfMemory] for a row of cards keyed [keys], kept with the row's other saved state so it
 * outlives the row scrolling off.
 */
@Composable
fun rememberShelfMemory(keys: List<String>): ShelfMemory {
    val last = rememberSaveable { mutableStateOf<String?>(null) }
    val memory = remember(last) { ShelfMemory(last) }
    memory.keys = keys
    return memory
}

/** Goes on each of a [Shelf]'s cards, ahead of what makes it focusable: it's how the row remembers. */
fun Modifier.shelfCard(memory: ShelfMemory, key: String): Modifier =
    (if (key == memory.target) focusRequester(memory.requester) else this)
        // Read when focus is searched for, not composed: the card being left stays reachable.
        .focusProperties { canFocus = !memory.leaving || key == memory.lastKey }
        .onFocusChanged { if (it.isFocused) memory.lastKey = key }
