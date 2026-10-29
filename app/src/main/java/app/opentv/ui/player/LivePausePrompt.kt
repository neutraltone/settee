/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.player.PlayerController
import app.opentv.update.startsFocused

/** Offers to turn live pause on, explaining what it costs, when the viewer pauses without it. */
@Composable
internal fun LivePausePrompt(onTurnOn: () -> Unit, onDontAskAgain: () -> Unit, onDismiss: () -> Unit) {
    val minutes = PlayerController.DVR_MAX_BUFFER_MILLIS / MILLIS_PER_MINUTE
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.live_pause_prompt_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                pluralStringResource(R.plurals.live_pause_prompt_body, minutes, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDontAskAgain) {
                    Text(stringResource(R.string.live_pause_prompt_dont_ask))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.live_pause_prompt_not_now))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onTurnOn, modifier = Modifier.startsFocused()) {
                    Text(stringResource(R.string.live_pause_prompt_turn_on))
                }
            }
        }
    }
}

private const val MILLIS_PER_MINUTE = 60_000
