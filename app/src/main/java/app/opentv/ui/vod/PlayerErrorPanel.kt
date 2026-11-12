/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.PlayerController
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/**
 * Why a film or episode can't go on, with somewhere to go. Takes focus itself: a remote has no other way
 * to reach these buttons, and with the bar hidden behind them the viewer would be left with nothing lit.
 */
@Composable
internal fun PlayerErrorPanel(error: PlayerController.State.Error, onRetry: () -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(error) { runCatching { first.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            // Left to itself, Compose spends the first Back on taking focus off the button, so the
            // press would seem to do nothing.
            .onPreviewKeyEvent { event ->
                val isBack = event.key == Key.Back || event.key == Key.Escape
                if (isBack && event.type == KeyEventType.KeyUp) onBack()
                isBack
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(48.dp)) {
            Text(error.title, SetteeType.ScreenTitle)
            Spacer(Modifier.height(12.dp))
            ErrorMessage(error.reason.sentence, error.reason.detail)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (error.canRetry) {
                    PanelButton(R.string.common_try_again, onRetry, Modifier.focusRequester(first))
                    PanelButton(R.string.common_back, onBack)
                } else {
                    PanelButton(R.string.common_back, onBack, Modifier.focusRequester(first))
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(stringResource(label), onClick = onClick, modifier = modifier)
}
