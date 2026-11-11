/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

/*
 * Movies' and Shows' grids scroll so the row with the Highlight is at the top, just under the
 * backdrop, as For you's rows do. Left to scroll only as far as it takes to show the highlighted cover, a list stops
 * with the bottom of the row above still showing, under the backdrop's text (#161).
 */

/** For a grid: the highlighted card's row goes to the top, [roomPx] below it for the lift and ring. */
@OptIn(ExperimentalFoundationApi::class)
internal class RowToTheTop(private val roomPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - roomPx
}
