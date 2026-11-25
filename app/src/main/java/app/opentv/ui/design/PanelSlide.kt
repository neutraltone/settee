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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** A panel's slide in from the screen's end edge, from [rememberPanelSlide], drawn by [slidesInFromEnd]. */
@Stable
class PanelSlide internal constructor(
    internal val progress: Animatable<Float, AnimationVector1D>,
    internal val rightToLeft: Boolean,
)

/**
 * Runs a panel's slide once, from when it's first drawn, so new content in an open panel doesn't
 * slide it again. It needs no check of the system's animation scale: Compose scales the time by it,
 * so with animations removed the panel is simply in place. Apart from [slidesInFromEnd] because the
 * progress has to be remembered, and Compose discourages modifier factories that are @Composable.
 */
@Composable
fun rememberPanelSlide(): PanelSlide {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(SetteeMotion.PANEL_SLIDE_MILLIS, easing = SetteeMotion.SlideIn))
    }
    return PanelSlide(progress, LocalLayoutDirection.current == LayoutDirection.Rtl)
}

/**
 * Moves the panel in by its own width. Moved, never faded: a see-through panel would show the screen
 * under it, which LOOK.md rules out, and would cost an offscreen layer on a Stick. Moved while
 * drawing, so the rows aren't measured again every frame. Nothing waits for it: the Highlight and
 * presses act on the rows while they move. Takes a [PanelSlide] rather than remembering its own,
 * since a modifier factory shouldn't be @Composable.
 */
fun Modifier.slidesInFromEnd(slide: PanelSlide): Modifier = graphicsLayer {
    val towardsEnd = if (slide.rightToLeft) -1f else 1f
    translationX = (1f - slide.progress.value) * size.width * towardsEnd
}
