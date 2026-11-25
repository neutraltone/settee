/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * How something that comes up without a press arrives (the Next Episode card, Skip intro, Setup's
 * finish): it fades in as it rises into place on [SetteeMotion.Arrive], rather than cutting in. Runs
 * once, from when it's first drawn, [delayMillis] after that; [arrived] starts it already in place, for
 * something that was there before the viewer looked. With the system's animations removed it's in
 * place at once, since the animation clock scales [millis] to 0.
 */
@Composable
fun rememberArrival(
    millis: Int = ARRIVAL_MILLIS,
    delayMillis: Int = 0,
    arrived: Boolean = false,
): Animatable<Float, AnimationVector1D> {
    val arrival = remember { Animatable(if (arrived) 1f else 0f) }
    LaunchedEffect(Unit) {
        arrival.animateTo(1f, tween(millis, delayMillis = delayMillis, easing = SetteeMotion.Arrive))
    }
    return arrival
}

/**
 * Draws [arrival] as a fade in while rising [rise] into place. In the draw phase only: animating a size
 * or offset in layout would lay out everything around it again every frame.
 */
fun Modifier.arriving(arrival: Animatable<Float, AnimationVector1D>, rise: Dp): Modifier = graphicsLayer {
    alpha = arrival.value
    translationY = (1f - arrival.value) * rise.toPx()
}

// If a frame of an arrival misses on a Stick, set the time to 0, never longer (docs/LOOK.md, Motion
// and cost).
private const val ARRIVAL_MILLIS = 250
