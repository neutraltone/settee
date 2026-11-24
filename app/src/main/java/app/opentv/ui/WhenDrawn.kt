/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos

/**
 * Calls [onDrawn] on the frame after [drawn] turns true, each time it does: when a journey that ends
 * with something on screen has really drawn it (ADR 0010's Slow Journeys).
 */
@Composable
internal fun WhenDrawn(drawn: Boolean, onDrawn: () -> Unit) {
    val latest by rememberUpdatedState(onDrawn)
    if (!drawn) return
    LaunchedEffect(Unit) {
        withFrameNanos {}
        latest()
    }
}
