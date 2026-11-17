/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.MotionDurationScale

/** The system's animation scale at 0, as "Remove animations" sets it: pass it as a compose rule's `effectContext`. */
object AnimationsOff : MotionDurationScale {
    override val scaleFactor = 0f
}
