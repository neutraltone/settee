/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * For the thing with the Highlight beside text it can't reach, such as a Side Panel's notes or the
 * font licences: Up and Down scroll [scroll] while it has further to go that way, so the Highlight
 * stays put and OK is still one press away. At either end they move the Highlight as usual.
 */
@Composable
fun Modifier.dpadScrolls(scroll: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { DpadScrollStep.toPx() }
    return onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionDown -> 1
            Key.DirectionUp -> -1
            else -> return@onPreviewKeyEvent false
        }
        val further = if (direction > 0) scroll.canScrollForward else scroll.canScrollBackward
        if (!further) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * step) }
        true
    }
}

private val DpadScrollStep = 96.dp
