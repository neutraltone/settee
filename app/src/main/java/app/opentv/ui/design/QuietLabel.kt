/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A secondary line in Quiet text, one line long: where the viewer is with a title, what's on a
 * channel. Deliberately not a chip or an icon in the accent colour, which on a TV reads as
 * something to press.
 */
@Composable
fun QuietLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SetteeType.Body, modifier = modifier, color = SetteeColors.QuietText, maxLines = 1)
}
