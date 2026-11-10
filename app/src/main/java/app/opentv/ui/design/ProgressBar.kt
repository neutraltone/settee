/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** How tall a [ProgressBar] is, for laying text out clear of it. */
val ProgressBarHeight = 4.dp

/** How far through something the viewer is, [progress] from 0 to 1, as a thin coral bar on a dark track. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(ProgressBarHeight).background(Track)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(
                if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f),
            ).background(SetteeColors.Accent),
        )
    }
}

private val Track = SetteeColors.Background.copy(alpha = 0.7f)
