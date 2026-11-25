/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.CubicBezierEasing

/** Settee's shared motion tokens, curves by what they're for and durations (docs/LOOK.md, Motion). */
object SetteeMotion {
    /**
     * For something coming into view: it starts quickly and settles gently, so it's nearly in place
     * by the time the eye reaches it. Compose's own ease-out starts too slowly to feel like a response.
     */
    val Arrive = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /**
     * For a panel sliding in from an edge by its own width: fast off the mark, settling gently. Not
     * [Arrive], which is made for short moves: over a 400 dp panel it covers a third of the width in
     * the first frame at 60 Hz, which reads as a jump rather than a slide; this covers a fifth, and
     * they're within a few dp of each other from the third frame on.
     */
    val SlideIn = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /**
     * How long a Side Panel takes to slide in; it closes at once. If a frame of it misses on a Stick,
     * set this to 0, never longer (docs/LOOK.md, Motion and cost).
     */
    const val PANEL_SLIDE_MILLIS = 200
}
