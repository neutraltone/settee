/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.ui.LocalRailReturn
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * A row with nothing on this day: one cell across the strip, so Up and Down from a programme have
 * something in line to stop on. Left to Compose's search, with only the channel cell at the far left
 * to aim for, Down from a programme hours along skipped the row for the programme two rows down.
 * It does what the channel cell does: OK plays the channel, and Right and Left page the day.
 *
 * It spans what's on screen rather than the whole day, outside the strip's scroll: a cell a day wide
 * is mostly off screen, and Down from it went to the programme under its middle, around noon,
 * wherever the strip was scrolled. This way Up and Down from it land in sight.
 */
@Composable
internal fun NoInformationCell(
    onWatch: () -> Unit,
    onHighlight: () -> Unit,
    onNextDay: (() -> Unit)?,
    onPrevDay: (() -> Unit)?,
    railFallback: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val railReturn = LocalRailReturn.current
    val self = remember { FocusRequester() }
    // Gone while the Rail would come back to it (the day's programmes arrived): back to the channel.
    DisposableEffect(self) { onDispose { if (railReturn.target === self) railReturn.target = railFallback } }
    HighlightSurface(
        onClick = onWatch,
        style = HighlightStyle.Cell,
        modifier = modifier
            .focusRequester(self)
            .onFocusChanged {
                if (it.isFocused) {
                    railReturn.target = self
                    onHighlight()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> onNextDay?.invoke() != null
                    Key.DirectionLeft -> onPrevDay?.invoke() != null
                    else -> false
                }
            }
            .fillMaxHeight()
            .drawBehind { drawLine(SetteeColors.Rule, Offset(0f, 0f), Offset(0f, size.height), RULE.toPx()) },
    ) { highlighted ->
        Text(
            stringResource(R.string.guide_no_info),
            SetteeType.Body,
            color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = CELL_PADDING),
        )
    }
}
