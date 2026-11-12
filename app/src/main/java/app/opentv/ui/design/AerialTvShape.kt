/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.ui.geometry.Offset

/**
 * Where the aerial TV's moving parts go, in the viewport of ic_waiting_tv and ic_waiting_tv_small,
 * so Compose draws them where the mark has them. Written by tools/launcher-art/mark.py.
 */
internal object AerialTvShape {
    const val VIEWPORT = 78f
    val knobs = listOf(Offset(25.5f, 19.5f), Offset(52.5f, 19.5f))

    /** The way each knob's arcs face: along its aerial, in degrees clockwise from three o'clock. */
    val arcFacing = floatArrayOf(-127.405f, -52.595f)
    const val ARC_SWEEP = 70f
    val arcRadii = floatArrayOf(8f, 12f, 16f)
    const val ARC_WIDTH = 3f
    const val SMALL_ARC_RADIUS = 11f
    const val SMALL_ARC_WIDTH = 5f
    val eyes = listOf(Offset(27.5f, 53f), Offset(50.5f, 53f))
    const val EYE_RADIUS = 4.4f
}
