/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

// Scrolling a title page's lazy rows and grids so a particular item is there for the Highlight.

/**
 * Scrolls the pill row so pill [index] is wholly in view, leaving the one before it showing where
 * there is one, so the row still says there's more that way.
 */
internal suspend fun LazyListState.keepInView(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val inView = item != null &&
        item.offset >= info.viewportStartOffset &&
        item.offset + item.size <= info.viewportEndOffset
    if (index >= 0 && !inView) scrollToItem((index - 1).coerceAtLeast(0))
}

/**
 * Scrolls a lazy row to item [index], once the row is laid out, unless it shows already: an item
 * scrolled out of the row isn't there to take the Highlight. Usually the row's own saved scroll has it
 * in view; this is for when what the row holds changed while the page was away. Nothing for -1.
 */
internal suspend fun LazyListState.bringOut(index: Int) = bringOut(
    index,
    laidOut = { layoutInfo.totalItemsCount > 0 },
    showing = { layoutInfo.visibleItemsInfo.any { it.index == index } },
    scroll = { scrollToItem(index) },
)

/** [LazyListState.bringOut] for a grid. */
internal suspend fun LazyGridState.bringOut(index: Int) = bringOut(
    index,
    laidOut = { layoutInfo.totalItemsCount > 0 },
    showing = { layoutInfo.visibleItemsInfo.any { it.index == index } },
    scroll = { scrollToItem(index) },
)

private suspend fun bringOut(index: Int, laidOut: () -> Boolean, showing: () -> Boolean, scroll: suspend () -> Unit) {
    if (index < 0) return
    snapshotFlow(laidOut).first { it }
    if (!showing()) scroll()
}
