/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Settee's corners, by what they're on (docs/LOOK.md, Shape and space). */
object SetteeShapes {
    /** Guide cells and sidebar rows: dense, so nearly square. */
    val Cell = RoundedCornerShape(2.dp)

    /** Episode thumbnails. */
    val Thumbnail = RoundedCornerShape(7.dp)

    /** Cards, episode rows and rail items. */
    val Card = RoundedCornerShape(10.dp)

    /** Buttons. */
    val Pill = RoundedCornerShape(percent = 50)
}

/** Settee's margins and gaps (docs/LOOK.md, Shape and space). */
object SetteeSpace {
    /** The screen margin at the left and right, clear of a TV's overscan. */
    val ScreenSide = 48.dp

    /** The screen margin at the top and bottom. */
    val ScreenTopBottom = 27.dp

    /** Between cards in a row. */
    val CardGap = 12.dp

    /** Between rows. */
    val RowGap = 14.dp
}
