/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
 * [working] adds a heartbeat: a soft band runs along the fill from its leading edge back to the
 * start, a strong beat then a softer one, then a rest. It says only that something is happening,
 * never how far. The band is drawn, not laid out, so a beat redraws the bar and nothing else.
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
        val fill = Modifier.fillMaxHeight().fillMaxWidth(shown).background(SetteeColors.Accent)
        Box(if (working) fill.heartbeat() else fill)
    }
}

@Composable
private fun Modifier.heartbeat(): Modifier {
    val beat = rememberInfiniteTransition(label = "heartbeat").animateFloat(
        initialValue = 0f,
        targetValue = BEAT_CYCLE_MILLIS.toFloat(),
        animationSpec = infiniteRepeatable(tween(BEAT_CYCLE_MILLIS, easing = LinearEasing)),
        label = "beat",
    )
    val band = with(LocalDensity.current) { BandWidth.toPx() }
    return drawWithContent {
        drawContent()
        // Read here, while drawing, so each frame redraws the bar without recomposing anything.
        val (where, strength) = beatAt(beat.value) ?: return@drawWithContent
        val centre = size.width * where
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to SetteeColors.Text.copy(alpha = BAND_ALPHA * strength),
                1f to Color.Transparent,
                startX = centre - band / 2,
                endX = centre + band / 2,
            ),
        )
    }
}

/** Where along the fill the band is (1 is the leading edge), and how strong, or null at rest. */
private fun beatAt(millis: Float): Pair<Float, Float>? {
    fun travel(start: Int) = 1f - (millis - start) / BEAT_MILLIS
    return when {
        millis < BEAT_MILLIS -> travel(0) to 1f
        millis in SECOND_BEAT.toFloat()..(SECOND_BEAT + BEAT_MILLIS).toFloat() -> travel(SECOND_BEAT) to SOFTER
        else -> null
    }
}

private val BandWidth = 160.dp
private const val BAND_ALPHA = 0.55f
private const val SOFTER = 0.55f
private const val BEAT_MILLIS = 520
private const val SECOND_BEAT = 600
private const val BEAT_CYCLE_MILLIS = 1_700

private val Track = SetteeColors.Background.copy(alpha = 0.7f)
