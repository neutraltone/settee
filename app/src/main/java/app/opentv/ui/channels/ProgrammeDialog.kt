/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.Reminder
import app.opentv.data.model.Source
import app.opentv.data.model.shownName
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.recordable
import app.opentv.data.repo.toRecord
import app.opentv.reminders.ReminderScheduler
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.options.LocalRecordingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What can be done with one programme: record it, be reminded of it, switch to it when it starts.
 * Its work runs in [scope], which has to outlive the dialog: choosing an action closes it.
 */
@Composable
internal fun ProgrammeDialog(
    targetRow: ChannelsViewModel.Row,
    programme: Programme,
    nowMillis: Long,
    sources: List<Source>,
    activeRecordings: List<Recording>,
    scope: CoroutineScope,
    onWatch: (Channel) -> Unit,
    onPlayCatchup: (MediaKey.Catchup) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = remember { ServiceLocator.get(context) }
    val recordingStarted = LocalRecordingStarted.current
    val channel = targetRow.primary
    // Which quality actually gets recorded. Defaults to what plays here, but the user can pick from
    // whatever else does (Sky Q's "record HD or SD"), so what's recorded can always be watched here.
    // Each variant channel carries its own stream URL, so the pick flows straight through to the
    // capture. Only surfaced when the channel has more than one.
    var chosenVariant by remember(targetRow.primary.id) {
        mutableStateOf(graph.channelLineup.toRecord(targetRow.channel))
    }
    // Which provider records it, when this channel exists on more than one. Recording from a
    // second provider's account is what lets you keep watching on the first — the only real way
    // around a single-connection provider (something even TiviMate can't do).
    var sourceOptions by remember(channel.id) { mutableStateOf<List<Channel>>(emptyList()) }
    val recordable = remember(targetRow.channel) { graph.channelLineup.recordable(targetRow.channel) }
    LaunchedEffect(channel.id) {
        sourceOptions = targetRow.channel.bestPerSource(firstSourceId = channel.sourceId, among = recordable)
    }
    LaunchedEffect(sourceOptions) {
        if (sourceOptions.size > 1) {
            chosenVariant = sourceOptions.firstOrNull { it.sourceId == channel.sourceId } ?: sourceOptions.first()
        }
    }
    val liveNow = nowMillis in programme.startUtcMillis until programme.endUtcMillis
    val recordingThis = activeRecordings.firstOrNull { targetRow.channel.has(it.channelId) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
        ) {
            Text(
                programme.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatTime(
                    programme.startUtcMillis,
                )}–${formatTime(programme.endUtcMillis)}   ${channel.shownName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            RecordFromChoice(
                qualities = targetRow.switchable.filter { it in recordable },
                sourceOptions = sourceOptions,
                sources = sources,
                chosen = chosenVariant,
                onChoose = { chosenVariant = it },
            )

            val isPast = programme.endUtcMillis <= nowMillis
            when {
                liveNow && recordingThis != null -> RecordActionRow(
                    stringResource(R.string.rec_stop_recording),
                    primary = true,
                ) {
                    graph.recordingEngine.stop(recordingThis.id)
                    Toast.makeText(
                        context,
                        resources.getString(R.string.rec_recording_stopped),
                        Toast.LENGTH_SHORT,
                    ).show()
                    onDismiss()
                }
                liveNow -> RecordActionRow(stringResource(R.string.rec_record_now), primary = true) {
                    scope.launch { graph.recordingEngine.startChannel(chosenVariant, programme) }
                    Toast.makeText(
                        context,
                        resources.getString(R.string.rec_recording_channel, channel.shownName),
                        Toast.LENGTH_LONG,
                    ).show()
                    recordingStarted()
                    onDismiss()
                }
                // A finished programme on a catch-up channel plays back from the archive.
                isPast && channel.tvArchive -> RecordActionRow(
                    stringResource(R.string.guide_watch_from_start),
                    primary = true,
                ) {
                    onPlayCatchup(MediaKey.Catchup(channel.id, programme.startUtcMillis))
                    onDismiss()
                }
                !isPast -> RecordActionRow(stringResource(R.string.rec_schedule_recording), primary = true) {
                    scope.launch { graph.recordingEngine.scheduleProgramme(chosenVariant, programme) }
                    Toast.makeText(
                        context,
                        resources.getString(R.string.rec_scheduled_title, programme.title),
                        Toast.LENGTH_LONG,
                    ).show()
                    recordingStarted()
                    onDismiss()
                }
            }

            // Reminders — only for something that hasn't started yet.
            if (!isPast) ReminderActions(channel, programme, scope, onDismiss)
            RecordActionRow(stringResource(R.string.rec_record_series)) {
                scope.launch {
                    graph.recordingEngine.recordSeries(
                        graph.channelLineup.toRecord(targetRow.channel),
                        programme,
                        targetRow.programmes,
                    )
                }
                Toast.makeText(
                    context,
                    resources.getString(R.string.rec_series_recording_set, programme.title),
                    Toast.LENGTH_LONG,
                ).show()
                recordingStarted()
                onDismiss()
            }
            RecordActionRow(stringResource(R.string.guide_watch_channel)) {
                onDismiss()
                onWatch(channel)
            }
            RecordActionRow(stringResource(R.string.common_cancel), onClick = onDismiss)
        }
    }
}

/** Which provider or Quality records it, when there's a choice. */
@Composable
private fun RecordFromChoice(
    qualities: List<Channel>,
    sourceOptions: List<Channel>,
    sources: List<Source>,
    chosen: Channel,
    onChoose: (Channel) -> Unit,
) {
    if (sourceOptions.size > 1) {
        // Record from which provider — keeps the other account free to watch on.
        Text(
            stringResource(R.string.rec_record_from),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sourceOptions.forEach { opt ->
                QualityChip(
                    label = sources.firstOrNull { it.id == opt.sourceId }?.name
                        ?: stringResource(R.string.rec_source_unknown),
                    selected = opt.id == chosen.id,
                    onClick = { onChoose(opt) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    } else if (qualities.size > 1) {
        Text(
            stringResource(R.string.rec_quality_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            qualities.forEach { variant ->
                QualityChip(
                    label = variant.qualityLabel.ifBlank { stringResource(R.string.rec_quality_sd) },
                    selected = variant.id == chosen.id,
                    onClick = { onChoose(variant) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReminderActions(channel: Channel, programme: Programme, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = remember { ServiceLocator.get(context) }
    var reminderSet by remember(channel.id, programme.startUtcMillis) {
        mutableStateOf<Boolean?>(null)
    }
    LaunchedEffect(channel.id, programme.startUtcMillis) {
        reminderSet =
            graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null
    }
    if (reminderSet == true) {
        RecordActionRow(stringResource(R.string.guide_cancel_reminder)) {
            scope.launch {
                graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis)?.let {
                    ReminderScheduler.cancel(context, it.id)
                    graph.reminderRepository.delete(it.id)
                }
            }
            Toast.makeText(
                context,
                resources.getString(R.string.guide_reminder_removed),
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        }
    } else {
        RecordActionRow(stringResource(R.string.guide_remind_me)) {
            scope.launch { setReminder(graph, context, channel, programme, autoTune = false) }
            Toast.makeText(
                context,
                resources.getString(R.string.guide_reminder_set, programme.title),
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        }
        RecordActionRow(stringResource(R.string.guide_auto_switch)) {
            scope.launch { setReminder(graph, context, channel, programme, autoTune = true) }
            Toast.makeText(
                context,
                resources.getString(R.string.guide_will_switch, channel.shownName),
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        }
    }
}

/** Inserts a reminder for a future programme and arms its alarm. No-op if one already exists. */
private suspend fun setReminder(
    graph: ServiceLocator.Graph,
    context: android.content.Context,
    channel: Channel,
    programme: Programme,
    autoTune: Boolean,
) {
    if (graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null) return
    val id = graph.reminderRepository.insert(
        Reminder(
            channelId = channel.id,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = programme.title,
            startUtcMillis = programme.startUtcMillis,
            endUtcMillis = programme.endUtcMillis,
            autoTune = autoTune,
            createdAtMillis = System.currentTimeMillis(),
        ),
    )
    ReminderScheduler.set(context, id, programme.startUtcMillis)
}

@Composable
internal fun RecordActionRow(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        primary -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        primary -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A small focusable quality pill (FHD / HD / SD…) for the record dialog's "record HD or SD" choice. */
@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = fg,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
