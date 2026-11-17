/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Keeps the highlight in place on a lazy list when its highlighted row leaves it (a channel just
 * hidden, say). Left alone, Compose hands the highlight to the list's first row, a long way from
 * where the viewer was. Each row wears [modifier] (or [groupModifier]), ahead of what makes it focusable.
 */
internal class RowHighlight {
    private val requester = FocusRequester()
    private var restoringTo by mutableStateOf<Int?>(null)

    /** Where a highlighted row stood when it left the list, until the highlight has been put back. */
    var leftFrom by mutableStateOf<Int?>(null)
        private set
    private var highlighted: Any? = null
    private var highlightedIndex: Int? = null
    private var listed: Set<Any> = emptySet()

    /** A row's modifier, ahead of what makes it focusable. */
    fun modifier(index: Int, key: Any): Modifier = watch(index, key) { it.isFocused }

    /**
     * For a row that is itself a row of cards (For you's shelves): it has the Highlight while one of
     * its cards does, and putting the Highlight back on it puts it on one of them.
     */
    fun groupModifier(index: Int, key: Any): Modifier = watch(index, key) { it.hasFocus }

    private fun watch(index: Int, key: Any, has: (FocusState) -> Boolean): Modifier {
        val restoring = if (restoringTo == index) Modifier.focusRequester(requester) else Modifier
        return restoring.onFocusChanged {
            if (has(it)) {
                highlighted = key
                highlightedIndex = index
            } else if (highlighted == key) {
                // Still listed means the highlight just moved off it, rather than leaving with it.
                highlighted = null
                if (key !in listed) leftFrom = index
            }
        }
    }

    fun list(keys: Set<Any>) {
        listed = keys
    }

    suspend fun restore(from: Int, lastIndex: Int, listState: LazyListState, firstRowItem: Int) {
        val target = from.coerceAtMost(lastIndex)
        // A row that can't take the Highlight (For you's placeholder for a row still being worked out)
        // passes it on: to the rows below first, which moved up into the gap, then those above.
        // A sequence, as a guide's list can run to twenty thousand rows.
        val nearest = ((target..lastIndex).asSequence() + (target - 1 downTo 0).asSequence()).take(MOST_TRIES)
        for (index in nearest) {
            if (restoreTo(index, listState, firstRowItem)) break
        }
        leftFrom = null
    }

    private suspend fun restoreTo(index: Int, listState: LazyListState, firstRowItem: Int): Boolean {
        restoringTo = index
        highlightedIndex = null
        listState.scrollToItem(firstRowItem + index)
        // A frame resumes this before it recomposes, so the row wears the requester a frame later.
        repeat(FRAMES_TO_SETTLE) { withFrameNanos {} }
        runCatching { requester.requestFocus() }
        restoringTo = null
        withFrameNanos {}
        return highlightedIndex == index
    }

    private companion object {
        const val FRAMES_TO_SETTLE = 2
        const val MOST_TRIES = 3
    }
}

/**
 * A [RowHighlight] for [rows] drawn by [listState], whose rows start at item [firstRowItem] (after any
 * header). [key] must be what the list keys its rows by.
 */
@Composable
internal fun <T> rememberRowHighlight(
    rows: List<T>,
    listState: LazyListState,
    firstRowItem: Int = 0,
    key: (T) -> Any,
): RowHighlight {
    val highlight = remember { RowHighlight() }
    // A set, built once per list: every row that loses the highlight asks whether it's still listed,
    // and a guide can run to twenty thousand rows on a weak box.
    highlight.list(remember(rows) { rows.mapTo(HashSet(), key) })
    val lastIndex by rememberUpdatedState(rows.lastIndex)
    LaunchedEffect(highlight.leftFrom) {
        highlight.leftFrom?.let { highlight.restore(it, lastIndex, listState, firstRowItem) }
    }
    return highlight
}
