/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.SkipCue
import app.opentv.ui.design.SetteeMotion

/**
 * Skip intro, Skip recap, or one button for both (#518). Like the Next Episode card it turns up without
 * a press, so it rises into the picture rather than cutting in.
 */
@Composable
internal fun SkipStretchButton(stretch: SkipCue.Stretch, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (stretch.kind) {
        SkipCue.Kind.INTRO -> R.string.player_skip_intro
        SkipCue.Kind.RECAP -> R.string.player_skip_recap
        SkipCue.Kind.RECAP_AND_INTRO -> R.string.player_skip_recap_and_intro
    }
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, tween(ARRIVAL_MILLIS, easing = SetteeMotion.Arrive)) }
    OverlayButton(
        stringResource(label),
        onSkip,
        modifier.graphicsLayer {
            alpha = arrival.value
            translationY = (1f - arrival.value) * ARRIVAL_RISE.toPx()
        },
    )
}

// As the Next Episode card's: if a frame of it misses on a Stick, set the time to 0, never longer.
private const val ARRIVAL_MILLIS = 250
private val ARRIVAL_RISE = 16.dp
