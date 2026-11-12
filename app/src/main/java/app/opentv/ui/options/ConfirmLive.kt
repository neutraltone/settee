/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.model.Recording
import app.opentv.ui.design.ConfirmPanel

/**
 * Runs what starts a live stream, asking first if that would fight a recording. With nothing above
 * to ask (a screen drawn on its own in a test), it just runs it.
 */
val LocalConfirmLive = staticCompositionLocalOf<(start: () -> Unit) -> Unit> { { start -> start() } }

/**
 * Watching a live channel while a recording runs opens a second stream on the same line, which cuts
 * the recording and can get a single-connection account banned. Every surface that can start one
 * goes through [LocalConfirmLive], so none of them can forget to ask.
 */
@Composable
fun ConfirmLiveHost(
    activeRecordings: List<Recording>,
    onStopRecording: (Recording) -> Unit,
    content: @Composable () -> Unit,
) {
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val recordings by rememberUpdatedState(activeRecordings)
    val confirmLive: (() -> Unit) -> Unit = remember {
        { start -> if (recordings.isEmpty()) start() else pending = start }
    }
    CompositionLocalProvider(LocalConfirmLive provides confirmLive, content = content)
    pending?.let { start ->
        ConfirmPanel(
            title = stringResource(R.string.rec_live_warn_title),
            body = stringResource(R.string.rec_live_warn_body, recordings.firstOrNull()?.title.orEmpty()),
            confirm = stringResource(R.string.rec_live_warn_watch),
            keep = stringResource(R.string.rec_live_warn_keep),
            onConfirm = {
                pending = null
                recordings.forEach(onStopRecording)
                start()
            },
            onKeep = { pending = null },
        )
    }
}
