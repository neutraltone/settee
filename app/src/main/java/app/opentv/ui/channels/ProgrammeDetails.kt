/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import android.widget.Toast
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import app.opentv.ui.design.PanelRow
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.SidePanel
import app.opentv.ui.design.Text
import app.opentv.ui.options.LocalRecordingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Programme details (docs/LOOK.md, Live TV), what OK on a programme in the guide opens: a panel on
 * the right with the programme's title, channel, time and synopsis at the top, and what can be done
 * with it as rows beneath: record it, be reminded of it, switch to it when it starts. Back closes it.
 *
 * Record from and Quality are rows showing the current choice; OK on one swaps the panel for that
 * choice, with the current one ticked, and choosing comes back to the details.
 *
 * Its work runs in [scope], which has to outlive the panel: choosing an action closes it.
 */
@Composable
internal fun ProgrammeDetails(
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
    val graph = remember { ServiceLocator.get(context) }
    val channel = targetRow.primary
    // Which Quality actually gets recorded. Defaults to what plays here, but the viewer can pick from
    // whatever else does ("record HD or SD"), so what's recorded can always be watched here.
    var chosenVariant by remember(targetRow.primary.id) {
        mutableStateOf(graph.channelLineup.toRecord(targetRow.channel))
    }
    // Which provider records it, when this channel exists on more than one. Recording from a second
    // provider's account is what lets you keep watching on the first, the only real way around a
    // single-connection provider.
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
    val qualities = targetRow.switchable.filter { it in recordable }
    val sourceName: @Composable (Channel) -> String = { option ->
        sources.firstOrNull { it.id == option.sourceId }?.name ?: stringResource(R.string.rec_source_unknown)
    }
    val qualityName: @Composable (
        Channel,
    ) -> String = { it.qualityLabel.ifBlank { stringResource(R.string.rec_quality_sd) } }

    var choosing by remember { mutableStateOf<Choosing?>(null) }
    // The row a choice was opened from, which the Highlight goes back to when it's made.
    val choiceRow = remember { FocusRequester() }
    var backToChoiceRow by remember { mutableStateOf(false) }

    SidePanel(onBack = { if (choosing != null) choosing = null.also { backToChoiceRow = true } else onDismiss() }) {
        when (choosing) {
            Choosing.SOURCE -> Choice(
                title = stringResource(R.string.rec_record_from),
                options = sourceOptions,
                chosen = chosenVariant,
                labelOf = sourceName,
            ) {
                chosenVariant = it
                choosing = null
                backToChoiceRow = true
            }
            Choosing.QUALITY -> Choice(
                title = stringResource(R.string.programme_quality),
                options = qualities,
                chosen = chosenVariant,
                labelOf = qualityName,
            ) {
                chosenVariant = it
                choosing = null
                backToChoiceRow = true
            }
            null -> {
                Heading(programme, channel)
                if (sourceOptions.size > 1) {
                    PanelRow(
                        stringResource(R.string.rec_record_from),
                        onClick = { choosing = Choosing.SOURCE },
                        value = sourceName(chosenVariant),
                        modifier = Modifier.focusRequester(choiceRow),
                    )
                } else if (qualities.size > 1) {
                    PanelRow(
                        stringResource(R.string.programme_quality),
                        onClick = { choosing = Choosing.QUALITY },
                        value = qualityName(chosenVariant),
                        modifier = Modifier.focusRequester(choiceRow),
                    )
                }
                if (backToChoiceRow) {
                    LaunchedEffect(Unit) {
                        withFrameNanos {}
                        runCatching { choiceRow.requestFocus() }
                        backToChoiceRow = false
                    }
                }
                Actions(
                    targetRow,
                    programme,
                    nowMillis,
                    activeRecordings,
                    chosenVariant,
                    scope,
                    onWatch,
                    onPlayCatchup,
                    onDismiss,
                )
            }
        }
    }
}

private enum class Choosing { SOURCE, QUALITY }

@Composable
private fun ColumnScope.Heading(programme: Programme, channel: Channel) {
    Text(programme.title, SetteeType.Section, maxLines = 2)
    Text(channel.shownName, SetteeType.Body, maxLines = 1)
    Text(
        "${formatTime(programme.startUtcMillis)}–${formatTime(programme.endUtcMillis)}",
        SetteeType.Body,
        color = SetteeColors.QuietText,
    )
    programme.description?.takeIf { it.isNotBlank() }?.let { Text(it, SetteeType.Synopsis, maxLines = SYNOPSIS_LINES) }
    Spacer(Modifier.height(HEADING_GAP))
}

/** A choice of [options], [chosen] ticked and the Highlight starting on it. */
@Composable
private fun Choice(
    title: String,
    options: List<Channel>,
    chosen: Channel,
    labelOf: @Composable (Channel) -> String,
    onChoose: (Channel) -> Unit,
) {
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { current.requestFocus() }
    }
    Text(title, SetteeType.Section)
    Spacer(Modifier.height(HEADING_GAP))
    options.forEach { option ->
        PanelRow(
            labelOf(option),
            onClick = { onChoose(option) },
            ticked = option.id == chosen.id,
            modifier = if (option.id == chosen.id) Modifier.focusRequester(current) else Modifier,
        )
    }
}

/** What can be done with the programme, each a row, shown under the same conditions as always. */
@Composable
private fun Actions(
    targetRow: ChannelsViewModel.Row,
    programme: Programme,
    nowMillis: Long,
    activeRecordings: List<Recording>,
    chosenVariant: Channel,
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
    val liveNow = nowMillis in programme.startUtcMillis until programme.endUtcMillis
    val isPast = programme.endUtcMillis <= nowMillis
    val recordingThis = activeRecordings.firstOrNull { targetRow.channel.has(it.channelId) }
    fun toast(text: String, long: Boolean = true) =
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    when {
        liveNow && recordingThis != null -> PanelRow(stringResource(R.string.rec_stop_recording), onClick = {
            graph.recordingEngine.stop(recordingThis.id)
            toast(resources.getString(R.string.rec_recording_stopped), long = false)
            onDismiss()
        })
        liveNow -> PanelRow(stringResource(R.string.rec_record_now), onClick = {
            scope.launch { graph.recordingEngine.startChannel(chosenVariant, programme) }
            toast(resources.getString(R.string.rec_recording_channel, channel.shownName))
            recordingStarted()
            onDismiss()
        })
        // A finished programme on a catch-up channel plays back from the archive.
        isPast && channel.tvArchive -> PanelRow(stringResource(R.string.guide_watch_from_start), onClick = {
            onPlayCatchup(MediaKey.Catchup(channel.id, programme.startUtcMillis))
            onDismiss()
        })
        !isPast -> PanelRow(stringResource(R.string.rec_schedule_recording), onClick = {
            scope.launch { graph.recordingEngine.scheduleProgramme(chosenVariant, programme) }
            toast(resources.getString(R.string.rec_scheduled_title, programme.title))
            recordingStarted()
            onDismiss()
        })
    }
    // Reminders: only for something that hasn't started yet.
    if (!isPast) ReminderActions(channel, programme, scope, onDismiss)
    PanelRow(stringResource(R.string.rec_record_series), onClick = {
        scope.launch {
            graph.recordingEngine.recordSeries(
                graph.channelLineup.toRecord(targetRow.channel),
                programme,
                targetRow.programmes,
            )
        }
        toast(resources.getString(R.string.rec_series_recording_set, programme.title))
        recordingStarted()
        onDismiss()
    })
    PanelRow(stringResource(R.string.guide_watch_channel), onClick = {
        onDismiss()
        onWatch(channel)
    })
}

@Composable
private fun ReminderActions(channel: Channel, programme: Programme, scope: CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = remember { ServiceLocator.get(context) }
    var reminderSet by remember(channel.id, programme.startUtcMillis) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(channel.id, programme.startUtcMillis) {
        reminderSet = graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis) != null
    }
    if (reminderSet == true) {
        PanelRow(stringResource(R.string.guide_cancel_reminder), onClick = {
            scope.launch {
                graph.reminderRepository.forProgramme(channel.id, programme.startUtcMillis)?.let {
                    ReminderScheduler.cancel(context, it.id)
                    graph.reminderRepository.delete(it.id)
                }
            }
            Toast.makeText(context, resources.getString(R.string.guide_reminder_removed), Toast.LENGTH_SHORT).show()
            onDismiss()
        })
    } else {
        PanelRow(stringResource(R.string.guide_remind_me), onClick = {
            scope.launch { setReminder(graph, context, channel, programme, autoTune = false) }
            Toast.makeText(
                context,
                resources.getString(R.string.guide_reminder_set, programme.title),
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        })
        PanelRow(stringResource(R.string.guide_auto_switch), onClick = {
            scope.launch { setReminder(graph, context, channel, programme, autoTune = true) }
            Toast.makeText(
                context,
                resources.getString(R.string.guide_will_switch, channel.shownName),
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
        })
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

private val HEADING_GAP = 12.dp
private const val SYNOPSIS_LINES = 4
