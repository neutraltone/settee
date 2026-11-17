/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.CubicBezierEasing

/** Settee's motion curves (docs/LOOK.md, Feel). */
object SetteeMotion {
    /**
     * For something arriving: it starts quickly and settles gently, so it's nearly in place by the
     * time the eye reaches it. Compose's own ease-out starts too slowly to feel like a response.
     */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
}
