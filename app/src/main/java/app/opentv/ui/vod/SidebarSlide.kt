/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * How far out the sidebar is, and how the screen beside it is laid out meanwhile, as the rail does
 * it (MainShell's RailSlide). The slide is read only in layer blocks and through derivedStateOf, so
 * a frame of it never measures anything: animating a width had the screen beside the rail measured
 * on every frame, which stuttered on a Stick (#156).
 */
@Stable
internal class SidebarSlide {
    var open by mutableStateOf(false)

    val progress = Animatable(0f)

    private val fullyOpen by derivedStateOf { progress.value >= 1f }
    private val partlyOpen by derivedStateOf { progress.value > 0f }

    /**
     * Opening, the screen keeps its closed layout until the sidebar is all the way out; closing, it
     * takes the closed layout straight away. Either way it only changes width once.
     */
    val contentLaidOutOpen get() = open && fullyOpen

    /** Kept while closing, so the sidebar slides away rather than vanishing. */
    val sidebarShown get() = open || partlyOpen
}

@Composable
internal fun rememberSidebarSlide(): SidebarSlide {
    val slide = remember { SidebarSlide() }
    LaunchedEffect(slide.open) { slide.progress.animateTo(if (slide.open) 1f else 0f) }
    return slide
}
