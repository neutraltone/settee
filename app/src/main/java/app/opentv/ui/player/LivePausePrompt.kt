/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.player.BufferPlan
import app.opentv.ui.design.PanelBody
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel

/**
 * Offers to turn live pause on, explaining what it costs, when the viewer pauses without it. The
 * Highlight starts on turning it on; Back is Not now.
 */
@Composable
internal fun LivePausePrompt(onTurnOn: () -> Unit, onDontAskAgain: () -> Unit, onDismiss: () -> Unit) {
    val pauseMillis = remember { BufferPlan.livePauseMillis() }
    SidePanel(onBack = onDismiss) {
        PanelTitle(stringResource(R.string.live_pause_prompt_title))
        PanelBody(
            if (pauseMillis >= MILLIS_PER_MINUTE) {
                val minutes = pauseMillis / MILLIS_PER_MINUTE
                pluralStringResource(R.plurals.live_pause_prompt_body, minutes, minutes)
            } else {
                // A device with little memory, such as a Fire TV Stick, gets under a minute.
                val seconds = pauseMillis / MILLIS_PER_SECOND
                pluralStringResource(R.plurals.live_pause_prompt_body_seconds, seconds, seconds)
            },
        )
        PanelRow(stringResource(R.string.live_pause_prompt_turn_on), onClick = onTurnOn)
        PanelRow(stringResource(R.string.live_pause_prompt_not_now), onClick = onDismiss)
        PanelRow(stringResource(R.string.live_pause_prompt_dont_ask), onClick = onDontAskAgain)
    }
}

private const val MILLIS_PER_MINUTE = 60_000
private const val MILLIS_PER_SECOND = 1_000
