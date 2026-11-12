/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.opentv.R
import kotlin.math.abs

/** How big an [AerialTv] is drawn. */
enum class AerialTvSize(internal val side: Dp) {
    /** Over a player that's buffering, or a screen that's waiting on something, with the reason under it. */
    Full(FullSide),

    /** At text height, beside a line of text: no face, thicker aerials and one arc. */
    Small(SmallSide),
}

/**
 * Settee waiting: the TV from the mark, with coral arcs rising from its aerials and fading as they
 * spread. At [AerialTvSize.Full] three arcs rise in turn and the eyes blink now and then. A screen
 * reader hears [description], what's loading.
 *
 * It never stops moving, even with the system's animations removed: the motion is the only sign the
 * app isn't frozen, and WCAG counts a loading indicator as essential motion. It's the one place in
 * Settee that ignores the animation scale (docs/LOOK.md, Waiting).
 *
 * The TV's still parts are a drawable that tools/launcher-art/mark.py writes from the mark, and only
 * the arcs and eyes are drawn here, in the same coordinates. All of it is drawn, not laid out, so a
 * frame redraws the indicator and nothing else.
 */
@Composable
fun AerialTv(description: String, modifier: Modifier = Modifier, size: AerialTvSize = AerialTvSize.Full) {
    val still = painterResource(
        if (size == AerialTvSize.Full) R.drawable.ic_waiting_tv else R.drawable.ic_waiting_tv_small,
    )
    val millis = rememberElapsedMillis()
    Spacer(
        modifier
            .size(size.side)
            .progressSemantics()
            .semantics { contentDescription = description }
            .drawBehind {
                with(still) { draw(this@drawBehind.size) }
                // Read here, while drawing, so each frame redraws only this.
                val elapsed = millis.longValue
                scale(this.size.width / AerialTvShape.VIEWPORT, pivot = Offset.Zero) {
                    if (size == AerialTvSize.Full) drawFull(elapsed) else drawSmall(elapsed)
                }
            },
    )
}

/**
 * Milliseconds since the indicator first drew, from the bare frame clock.
 *
 * Not rememberInfiniteTransition or animateFloat: those read the animation scale, and at 0 ("Remove
 * animations") they jump to their end and stop, leaving a frozen TV that looks like a hung app. The
 * frame clock itself isn't scaled. It's asked through withInfiniteAnimationFrameMillis so a test can
 * still pause it (InfiniteAnimationPolicy) instead of waiting for a screen that never goes idle.
 */
@Composable
private fun rememberElapsedMillis(): LongState {
    val millis = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) {
            withInfiniteAnimationFrameMillis { now -> millis.longValue = now - start }
        }
    }
    return millis
}

/** Three arcs from each aerial, rising in turn and fading as they spread; the eyes blink. */
private fun DrawScope.drawFull(millis: Long) {
    AerialTvShape.arcRadii.forEachIndexed { arc, radius ->
        val sinceLit = (millis - arc * ARC_STAGGER_MILLIS).mod(ARC_CYCLE_MILLIS)
        val fading = sinceLit.toFloat() / ARC_LIFE_MILLIS
        if (fading < 1f) {
            val fartherFainter = 1f - arc * FAINTER_PER_ARC
            drawArcs(radius, FullArc, alpha = (1f - fading) * fartherFainter)
        }
    }
    val open = eyesOpen(millis)
    AerialTvShape.eyes.forEach { eye ->
        val r = AerialTvShape.EYE_RADIUS
        // The mark's plum, which is the screen's background colour.
        drawOval(
            SetteeColors.Background,
            topLeft = Offset(eye.x - r, eye.y - r * open),
            size = Size(2 * r, 2 * r * open),
        )
    }
}

/** One arc from each aerial, widening as it fades. */
private fun DrawScope.drawSmall(millis: Long) {
    val fading = millis.mod(SMALL_CYCLE_MILLIS).toFloat() / SMALL_CYCLE_MILLIS
    val radius = AerialTvShape.SMALL_ARC_RADIUS * (SMALL_START + (1f - SMALL_START) * fading)
    drawArcs(radius, SmallArc, alpha = 1f - fading)
}

private fun DrawScope.drawArcs(radius: Float, stroke: Stroke, alpha: Float) {
    AerialTvShape.knobs.forEachIndexed { i, knob ->
        drawArc(
            SetteeColors.Accent,
            startAngle = AerialTvShape.arcFacing[i] - AerialTvShape.ARC_SWEEP / 2,
            sweepAngle = AerialTvShape.ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(knob.x - radius, knob.y - radius),
            size = Size(2 * radius, 2 * radius),
            alpha = alpha,
            style = stroke,
        )
    }
}

/** How open the eyes are, 1 for wide open: shut and open again quickly once every blink cycle. */
private fun eyesOpen(millis: Long): Float {
    val intoBlink = millis.mod(BLINK_CYCLE_MILLIS) - (BLINK_CYCLE_MILLIS - BLINK_MILLIS)
    if (intoBlink < 0) return 1f
    return abs(1f - 2f * intoBlink / BLINK_MILLIS).coerceAtLeast(SHUT)
}

// Made once: the arcs redraw every frame for as long as anything waits.
private val FullArc = Stroke(AerialTvShape.ARC_WIDTH, cap = StrokeCap.Round)
private val SmallArc = Stroke(AerialTvShape.SMALL_ARC_WIDTH, cap = StrokeCap.Round)
private val FullSide = 64.dp
private val SmallSide = 22.dp
private const val ARC_CYCLE_MILLIS = 1_800L
private const val ARC_STAGGER_MILLIS = 400L
private const val ARC_LIFE_MILLIS = 1_000L
private const val FAINTER_PER_ARC = 0.25f
private const val SMALL_CYCLE_MILLIS = 1_200L
private const val SMALL_START = 0.7f
private const val BLINK_CYCLE_MILLIS = 4_000L
private const val BLINK_MILLIS = 180L
private const val SHUT = 0.1f
