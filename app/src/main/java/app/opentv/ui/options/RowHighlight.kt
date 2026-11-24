/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * hidden, a recording deleted). Left alone, Compose hands the highlight to the list's first row, a
 * long way from where the viewer was. Each row wears [modifier] (or [groupModifier]), ahead of what
 * makes it focusable; a row drawn outside the list wears [outside].
 *
 * The highlight goes to the first row that followed the one that left and is still listed, else a new
 * row now in its place, else the nearest one before it: by key rather than by place, as other rows
 * can leave with it (a series link's bookings), and counting places would then skip past the row
 * that moved into the gap.
 */
internal class RowHighlight(
    private val listState: LazyListState,
    private val itemIndex: (row: Int) -> Int,
    private val keepScroll: () -> Boolean,
) {
    private val requester = FocusRequester()
    private var restoringTo by mutableStateOf<Any?>(null)

    /** Where the highlight is to be handed on to, until it has been. */
    internal var handing by mutableStateOf<Handing?>(null)
        private set
    private var highlighted: Any? = null

    // What a row that has just left is looked up in: the list goes on to its new rows in the
    // composition that removes the row, before the row says it lost the highlight.
    private var rows: List<Any> = emptyList()
    private var indexOf: Map<Any, Int> = emptyMap()
    private var before: List<Any> = emptyList()

    // An outside row that lost the highlight, until it's seen whether it went with it.
    private var lostOutside: Any? = null

    /** A row's modifier, ahead of what makes it focusable; [index] is its place among the rows. */
    fun modifier(index: Int, key: Any): Modifier = watch(index, key) { it.isFocused }

    /**
     * For a row that is itself a row of cards (For you's shelves): it has the Highlight while one of
     * its cards does, and putting the Highlight back on it puts it on one of them.
     */
    fun groupModifier(index: Int, key: Any): Modifier = watch(index, key) { it.hasFocus }

    private fun watch(index: Int, key: Any, has: (FocusState) -> Boolean): Modifier {
        val restoring = if (restoringTo == key) Modifier.focusRequester(requester) else Modifier
        return restoring.onFocusChanged {
            if (has(it)) {
                highlighted = key
                lostOutside = null
            } else if (highlighted == key) {
                // Still listed means the highlight just moved off it, rather than leaving with it.
                highlighted = null
                if (key !in indexOf) handOn(heirsOf(key, index))
            }
        }
    }

    /**
     * For a row drawn outside the list (the player's Last channel, above it): when it goes while it
     * has the highlight, the highlight goes to [fallback], a key of the list's rows. [key] mustn't be
     * one of the list's own, though the row may show the same thing as one of them.
     */
    @Composable
    fun outside(key: Any, fallback: Any?): Modifier {
        val target by rememberUpdatedState(fallback)
        DisposableEffect(key) {
            onDispose {
                // Either order: a removed row may say it lost the highlight before it's disposed, or after.
                if (highlighted == key || lostOutside == key) {
                    highlighted = null
                    lostOutside = null
                    handOn(listOfNotNull(target?.takeIf { it in indexOf }) + rows.take(1))
                }
            }
        }
        return Modifier.onFocusChanged {
            if (it.isFocused) {
                highlighted = key
                lostOutside = null
            } else if (highlighted == key) {
                highlighted = null
                lostOutside = key
            }
        }
    }

    /** The list's rows by key, in order, with each key's place. */
    fun list(keys: List<Any>, places: Map<Any, Int>) {
        if (keys === rows) return
        before = rows
        rows = keys
        indexOf = places
    }

    /** Who takes the highlight from [key], which stood at [index] and has left. */
    private fun heirsOf(key: Any, index: Int): List<Any> {
        if (rows.isEmpty()) return emptyList()
        val at = if (before.getOrNull(index) == key) index else before.indexOf(key)
        val byKey = if (at < 0) {
            emptySequence()
        } else {
            // A new row now standing where it stood (a fresh Home row swapping a title, #595) moved into
            // the gap as much as a following one did, so it comes before the rows above.
            val newInPlace = rows.getOrNull(index)?.takeIf { it !in before }
            before.asSequence().drop(at + 1).filter { it in indexOf } +
                listOfNotNull(newInPlace) +
                (at - 1 downTo 0).asSequence().map(before::get).filter { it in indexOf }
        }
        // None of its neighbours left (the rows were all replaced): the row now in its place, then
        // those below it, which moved up into the gap, then those above.
        val target = index.coerceIn(0, rows.lastIndex)
        val byPlace = ((target..rows.lastIndex).asSequence() + (target - 1 downTo 0).asSequence()).map(rows::get)
        // A row that can't take the Highlight (For you's placeholder for a row still being worked out)
        // passes it on. A sequence, as a guide's list can run to twenty thousand rows.
        return byKey.ifEmpty { byPlace }.take(MOST_TRIES).toList()
    }

    private fun handOn(heirs: List<Any>) {
        handing = Handing(heirs)
    }

    internal suspend fun finishHanding(handing: Handing, onEmptied: () -> Unit) {
        if (handing.heirs.isEmpty()) {
            // Wait for the composition that emptied the list to settle before anything else is asked.
            withFrameNanos {}
            onEmptied()
        } else {
            for (key in handing.heirs) {
                if (restoreTo(key)) break
            }
        }
        if (this.handing === handing) this.handing = null
    }

    /**
     * Puts the highlight on the row keyed [key], scrolling as a handing on does: Back to a screen that
     * was built again asks for the row it left (Recordings, #432).
     */
    suspend fun aimAt(key: Any) {
        restoreTo(key)
    }

    private suspend fun restoreTo(key: Any): Boolean {
        val index = indexOf[key] ?: return false
        // `highlighted` isn't cleared first: Compose may already have moved focus onto this row by
        // itself when the old one left (a series link going with its bookings), and asking for focus
        // a row already has reports nothing, so the row would be judged unable to take it (#445).
        restoringTo = key
        val item = itemIndex(index)
        if (!keepScroll() || listState.layoutInfo.visibleItemsInfo.none { it.index == item }) {
            listState.scrollToItem(item)
        }
        // A frame resumes this before it recomposes, so the row wears the requester a frame later.
        repeat(FRAMES_TO_SETTLE) { withFrameNanos {} }
        runCatching { requester.requestFocus() }
        restoringTo = null
        withFrameNanos {}
        return highlighted == key
    }

    /** The rows to try, in turn, for the highlight; none means the list has emptied. */
    internal class Handing(val heirs: List<Any>)

    private companion object {
        const val FRAMES_TO_SETTLE = 2
        const val MOST_TRIES = 3
    }
}

/**
 * A [RowHighlight] for [rows] drawn by [listState]. [key] must be what the list keys its rows by.
 * Row `i` is the list's item [itemIndex] of `i`: `1 + it` after a header, or mapped past headings
 * when the rows are interleaved with them. [onEmptied] is told when the highlighted row leaves and no row
 * is left to take the highlight. [keepScroll] leaves the list where it is when the row taking the
 * highlight is already in sight, rather than scrolling it to the start.
 */
@Composable
internal fun <T> rememberRowHighlight(
    rows: List<T>,
    listState: LazyListState,
    itemIndex: (row: Int) -> Int = { it },
    keepScroll: Boolean = false,
    onEmptied: () -> Unit = {},
    key: (T) -> Any,
): RowHighlight {
    // Read through state, so a list that changes either is seen without building the highlight again.
    val currentItemIndex by rememberUpdatedState(itemIndex)
    val currentKeepScroll by rememberUpdatedState(keepScroll)
    val highlight = remember(listState) {
        RowHighlight(listState, itemIndex = { currentItemIndex(it) }, keepScroll = { currentKeepScroll })
    }
    // Built once per list: every row that loses the highlight asks whether it's still listed, and a
    // guide can run to twenty thousand rows on a weak box.
    val keys = remember(rows) { rows.map(key) }
    val places = remember(keys) { keys.withIndex().associateTo(HashMap(keys.size)) { it.value to it.index } }
    highlight.list(keys, places)
    val emptied by rememberUpdatedState(onEmptied)
    LaunchedEffect(highlight.handing) {
        highlight.handing?.let { highlight.finishHanding(it) { emptied() } }
    }
    return highlight
}
