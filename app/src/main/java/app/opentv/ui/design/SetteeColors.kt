/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.ui.graphics.Color

/**
 * Settee's colours, by role (docs/LOOK.md, Colour). Settee is dark only, so there's one set.
 *
 * Plain constants rather than a CompositionLocal: there's no second theme to swap in, and a local
 * would make every read a composition dependency for nothing.
 */
object SetteeColors {
    val Background = Color(0xFF1A1416)
    val Surface = Color(0xFF281F22)
    val Raised = Color(0xFF362A2E)

    /** Decoration only: too faint to carry text or the Highlight. */
    val Rule = Color(0xFF45373B)
    val Text = Color(0xFFF8EFEA)
    val QuietText = Color(0xFFBDABA8)
    val Accent = Color(0xFFFF8F6B)
    val OnAccent = Color(0xFF2A130B)
}
