/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the Highlight was on a title's page, by a key the page gives each place it can leave from: a
 * person's name, a cover, an episode. Navigation disposes the page while the screen it opened covers
 * it, so this is kept with the page's saved state, and Back puts the Highlight back there (#429) rather
 * than on Play, as a person's page does (#384). Null while the Highlight is on the page's buttons, which
 * is where the page starts anyway.
 */
@Stable
internal class ReturnSpot(saved: MutableState<String?>) {
    var key by saved
        private set

    internal val requester = FocusRequester()

    internal fun highlighted(key: String?) {
        this.key = key
    }

    /** Highlights the spot if there is one and it's on the page; false when the page should start as new. */
    fun restore(): Boolean =
        key != null && runCatching { requester.requestFocus(FocusDirection.Enter) }.getOrDefault(false)

    /**
     * [restore], frame by frame, for a spot known to be on the page but in a lazy row or grid that is
     * still being laid out or scrolled to it.
     */
    suspend fun restoreSoon(): Boolean = withTimeoutOrNull(LAYOUT_WAIT_MILLIS) {
        while (!restore()) withFrameNanos {}
        true
    } == true
}

/** A [ReturnSpot] for [page], kept with the page's saved state. */
@Composable
internal fun rememberReturnSpot(page: Any): ReturnSpot {
    val saved = rememberSaveable(page) { mutableStateOf<String?>(null) }
    return remember(saved) { ReturnSpot(saved) }
}

/**
 * Marks a place the viewer can leave the page from as [key], ahead of what makes it focusable. Null for
 * the page's buttons: highlighting one forgets the spot, so Back from playing lands on Play as before.
 */
internal fun Modifier.returnSpot(spot: ReturnSpot, key: String?): Modifier =
    (if (key != null && key == spot.key) focusRequester(spot.requester) else this)
        .onFocusChanged { if (it.isFocused) spot.highlighted(key) }

/**
 * Scrolls a lazy row to item [index], once the row is laid out, unless it shows already: an item
 * scrolled out of the row isn't there to take the Highlight. Usually the row's own saved scroll has it
 * in view; this is for when what the row holds changed while the page was away. Nothing for -1.
 */
internal suspend fun LazyListState.bringOut(index: Int) {
    if (index < 0) return
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}

/** [LazyListState.bringOut] for a grid. */
internal suspend fun LazyGridState.bringOut(index: Int) {
    if (index < 0) return
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}

/** For a lazy row or grid to be laid out and scrolled to the spot. */
private const val LAYOUT_WAIT_MILLIS = 500L
