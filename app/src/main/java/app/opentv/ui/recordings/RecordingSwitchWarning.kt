/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.RecordingSignals
import app.opentv.ui.design.PanelBody
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.PanelTitle
import app.opentv.ui.design.SidePanel
import kotlinx.coroutines.delay

/**
 * Warns, 30 seconds before a booking starts, that the screen is about to switch to it (single-
 * connection auto-switch), over whatever screen is showing.
 *
 * A Side Panel rather than the banner it was (#437): the banner sat outside every screen's focus
 * path, and the live player takes the arrow keys before focus search runs, so the viewer could never
 * reach Keep watching. The panel is a window of its own, so it owns the remote while it's open, and
 * it starts on Keep watching. Back only closes it: switching stays the default.
 */
@Composable
fun RecordingSwitchWarning() {
    val imminent by RecordingSignals.imminent.collectAsState()
    val current = imminent ?: return

    // Clears a little after the switch instant, in case the switch itself never lands.
    LaunchedEffect(current.recordingId) {
        val wait = (current.startAtMillis + CLEAR_AFTER_START_MILLIS) - System.currentTimeMillis()
        delay(wait.coerceIn(MIN_SHOWN_MILLIS, MAX_SHOWN_MILLIS))
        if (RecordingSignals.imminent.value?.recordingId == current.recordingId) {
            RecordingSignals.clearImminent()
        }
    }

    val keepWatching = remember(current.recordingId) { FocusRequester() }
    SidePanel(onBack = RecordingSignals::clearImminent, startOn = keepWatching) {
        PanelTitle(stringResource(R.string.rec_switch_soon_title, current.title))
        PanelBody(stringResource(R.string.rec_switch_banner, current.title, current.channelName))
        PanelRow(
            stringResource(R.string.rec_switch_keep_watching),
            onClick = { RecordingSignals.suppress(current.recordingId) },
            modifier = Modifier.focusRequester(keepWatching),
        )
    }
}

private const val CLEAR_AFTER_START_MILLIS = 5_000L
private const val MIN_SHOWN_MILLIS = 5_000L
private const val MAX_SHOWN_MILLIS = 60_000L
