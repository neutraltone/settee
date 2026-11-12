/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How big an [AerialTv] is drawn. */
enum class AerialTvSize(internal val side: Dp) {
    /** Over a player that's buffering, or a screen that's waiting on something, with the reason under it. */
    Full(FullSide),

    /** At text height, beside a line of text: no face, thicker aerials and one arc. */
    Small(SmallSide),
}

private val FullSide = 64.dp
private val SmallSide = 22.dp
