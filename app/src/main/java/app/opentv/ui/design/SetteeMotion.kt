/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.CubicBezierEasing

/** Settee's motion curves, by what they're for (docs/LOOK.md, Motion). */
object SetteeMotion {
    /**
     * For something coming into view: it starts quickly and settles gently, so it's nearly in place
     * by the time the eye reaches it. Compose's own ease-out starts too slowly to feel like a response.
     */
    val Arrive = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** For a panel coming in from an edge, as a drawer does: fast off the mark, settling gently. */
    val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * How long a Side Panel takes to slide in; it closes at once. If a frame of it misses on a Stick,
     * set this to 0, never longer (docs/LOOK.md, Motion and cost).
     */
    const val PANEL_SLIDE_MILLIS = 200
}
