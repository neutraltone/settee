/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Programme

/** Where one programme's cell sits along its guide row, from the start of the strip. */
internal data class CellSpan(val x: Dp, val width: Dp) {
    val end: Dp get() = x + width
}

/**
 * Lays out [shown], a row's programmes in the window (start-ordered), at [MINUTE_DP] a minute.
 *
 * A programme almost over (or a very short one) would otherwise be a sliver too thin to read. Each
 * cell is floored at [MIN_CELL_WIDTH], and the borrowed width is repaid out of the gaps that follow,
 * so everything downstream stays aligned with the time header.
 */
internal fun layOutCells(shown: List<Programme>, windowStartMillis: Long, windowEndMillis: Long): List<CellSpan> {
    var cursor = windowStartMillis
    var x = 0.dp
    var debt = 0.dp
    return shown.map { programme ->
        val start = programme.startUtcMillis.coerceAtLeast(windowStartMillis)
        val gap = widthFor(cursor, start)
        val repaid = minOf(debt, gap)
        debt -= repaid
        x += (gap - repaid).coerceAtLeast(0.dp)
        val end = programme.endUtcMillis.coerceAtMost(windowEndMillis)
        val trueWidth = widthFor(start, end)
        val drawnWidth = maxOf(trueWidth, MIN_CELL_WIDTH)
        debt += drawnWidth - trueWidth
        cursor = end
        CellSpan(x, drawnWidth).also { x += drawnWidth }
    }
}

/**
 * Which of a row's cells to build when the strip shows [from] to [from] + [viewport]: those on
 * screen, plus one more past each edge, so Right or Left always has a built cell to move to. The
 * neighbour is a cell, not a margin in dp, because a film can be wider than the screen and start
 * its neighbour far beyond it.
 */
internal fun cellsToBuild(spans: List<CellSpan>, from: Dp, viewport: Dp): IntRange {
    if (spans.isEmpty()) return IntRange.EMPTY
    val to = from + viewport
    // The first cell ending past the left edge, and the last starting before the right one; in a
    // gap with nothing on screen these cross, and their neighbours are the cells either side of it.
    val firstOnScreen = spans.indexOfFirst { it.end > from }.let { if (it < 0) spans.size else it }
    val lastOnScreen = spans.indexOfLast { it.x < to }
    return (firstOnScreen - 1).coerceAtLeast(0)..(lastOnScreen + 1).coerceAtMost(spans.lastIndex)
}

/** Minutes between two instants, as guide width. Never negative. */
internal fun widthFor(fromMillis: Long, toMillis: Long): Dp {
    val minutes = ((toMillis - fromMillis) / MINUTE_MS).coerceAtLeast(0)
    return (minutes * MINUTE_DP).dp
}

private const val MINUTE_MS = 60_000L

// A day across at 4 dp a minute is about 5800 dp of strip.
internal const val MINUTE_DP = 4f

// Floor for a cell so a nearly finished or very short programme still shows its name.
internal val MIN_CELL_WIDTH = 72.dp
