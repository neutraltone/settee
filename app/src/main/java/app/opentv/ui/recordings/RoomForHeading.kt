/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec

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
