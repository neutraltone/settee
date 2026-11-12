/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.opentv.R

/** How tall a [ProgressBar] is, for laying text out clear of it. */
val ProgressBarHeight = 4.dp

/**
 * How far through something the viewer is, [progress] from 0 to 1, as a thin coral bar on a dark
 * track. A screen reader hears how far through, as it did from Material's bar.
 *
 * [working] pulses a short stretch past the fill, for when the bar can't move but something is
 * still happening: it says "still working", never how far. Only that stretch's alpha changes, in
 * its own layer, so the rest of the screen isn't redrawn while it pulses.
 */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, working: Boolean = false) {
    val shown = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    val still = stringResource(R.string.progress_still_working)
    Box(
        modifier
            .height(ProgressBarHeight)
            .progressSemantics(shown)
            .semantics { if (working) stateDescription = still }
            .background(Track),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(SetteeColors.Accent))
            if (working && shown < 1f) Pulse()
        }
    }
}

@Composable
private fun Pulse() {
    val alpha by rememberInfiniteTransition(label = "still working").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .fillMaxHeight()
            .width(PulseWidth)
            .graphicsLayer { this.alpha = alpha }
            .background(SetteeColors.Accent),
    )
}

private val PulseWidth = 32.dp
private const val PULSE_MILLIS = 900

private val Track = SetteeColors.Background.copy(alpha = 0.7f)
