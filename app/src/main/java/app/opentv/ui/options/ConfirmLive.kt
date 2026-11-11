/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.data.model.Recording

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
        Dialog(onDismissRequest = { pending = null }) {
            Column(
                Modifier
                    .width(480.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
            ) {
                Text(
                    stringResource(R.string.rec_live_warn_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.rec_live_warn_body, recordings.firstOrNull()?.title.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                ConfirmRow(stringResource(R.string.rec_live_warn_watch)) {
                    pending = null
                    recordings.forEach(onStopRecording)
                    start()
                }
                ConfirmRow(stringResource(R.string.rec_live_warn_keep)) { pending = null }
            }
        }
    }
}

@Composable
private fun ConfirmRow(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
