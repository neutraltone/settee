/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Message
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.ChoicePanel
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Recordings screen's list (docs/LOOK.md, Recordings): [sections] as rows under their headings,
 * each taking the Highlight whole. [profileNames] are every Profile's, by id; a row says whose it is
 * only when there's more than one.
 *
 * OK plays a recording ([onPlay]) or retries a failed one ([onRetry]); everything else is in the
 * row's options panel, and what's picked there reaches [onChoose].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsContent(
    sections: List<RecordingsSection>,
    profileNames: Map<Long, String>,
    onPlay: (Recording) -> Unit,
    onRetry: (Recording) -> Unit,
    onChoose: (RecordingsRow, ItemOption) -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    // By key, not the row: the list can change under an open panel (a booking starts, a recording
    // ends), and the panel must act on the row as it is now, or close if it has gone.
    var optionsOn by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val rows = remember(sections) { sections.flatMap { it.rows } }
    val heir = rememberHeir(remember(rows) { rows.map { it.key } })
    val room = with(LocalDensity.current) { HEADING_ROOM.toPx() }
    ScreenFrame(modifier) {
        Column {
            Text(stringResource(R.string.rec_screen_title), SetteeType.ScreenTitle)
            Spacer(Modifier.height(HEADING_GAP))
            if (sections.isEmpty()) {
                Message(stringResource(R.string.rec_empty_state))
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides remember(room) { RoomForHeading(room) }) {
                    LazyColumn(heir.watchPresses, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                        sections.forEach { section ->
                            item(key = section.heading, contentType = "heading") {
                                Text(
                                    stringResource(section.heading.label),
                                    SetteeType.Section,
                                    modifier = Modifier.padding(top = HEADING_GAP),
                                )
                            }
                            section.rows.forEach { row ->
                                item(key = row.key, contentType = "row") {
                                    RecordingsRowItem(
                                        row = row,
                                        profileNames = profileNames.takeIf { it.size > 1 }.orEmpty(),
                                        onClick = {
                                            when (val ok = row.okAction(now())) {
                                                is OkAction.Play -> onPlay(ok.recording)
                                                is OkAction.Retry -> onRetry(ok.recording)
                                                null -> optionsOn = row.key
                                            }
                                        },
                                        onOptions = { optionsOn = row.key },
                                        modifier = heir.modifierFor(row.key),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    rows.firstOrNull { it.key == optionsOn }?.let { row ->
        OptionsPanel(
            title = row.title,
            options = row.options().options(),
            onChoose = { option ->
                optionsOn = null
                when (option) {
                    ItemOption.DeleteRecording -> deleting = row.key
                    else -> {
                        // Stopping keeps the row, as a finished recording; everything else removes it.
                        if (option != ItemOption.StopRecording) heir.expectToLose(row.key)
                        onChoose(row, option)
                    }
                }
            },
            onDismiss = { optionsOn = null },
        )
    }
    // Delete removes the file from this device, the NAS or USB, and nothing can bring it back.
    rows.firstOrNull { it.key == deleting }?.let { row ->
        ChoicePanel(
            title = stringResource(R.string.rec_delete_confirm, row.title),
            choices = DeleteChoice.entries,
            labelOf = { stringResource(it.label) },
            onChoose = { choice ->
                deleting = null
                if (choice == DeleteChoice.Delete) {
                    heir.expectToLose(row.key)
                    onChoose(row, ItemOption.DeleteRecording)
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

private enum class DeleteChoice(@StringRes val label: Int) {
    // First, so it's where the Highlight starts: a second press of OK must not delete.
    Keep(R.string.rec_delete_keep),
    Delete(R.string.common_delete),
}

private val RecordingsHeading.label
    get() = when (this) {
        RecordingsHeading.ComingUp -> R.string.rec_heading_coming_up
        RecordingsHeading.Recorded -> R.string.rec_section_recorded
        RecordingsHeading.SeriesLinks -> R.string.rec_section_series_links
    }

private val RecordingsRow.key: String
    get() = when (this) {
        is RecordingsRow.Booking -> "rec-${recording.id}"
        is RecordingsRow.Recorded -> "rec-${recording.id}"
        is RecordingsRow.OfReminder -> "rem-${reminder.id}"
        is RecordingsRow.SeriesLink -> "series-${rule.id}"
    }

@Composable
private fun RecordingsRowItem(
    row: RecordingsRow,
    profileNames: Map<Long, String>,
    onClick: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Row,
        modifier = modifier.fillMaxWidth(),
        onOptions = onOptions,
    ) { highlighted ->
        val text = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text
        val quiet = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText
        Row(
            Modifier.padding(horizontal = ROW_PADDING_SIDE, vertical = ROW_PADDING_TOP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Leading(row, quiet)
            Spacer(Modifier.width(ROW_PADDING_SIDE))
            Column(Modifier.weight(1f)) {
                Text(row.title, SetteeType.RowTitle, color = text, maxLines = 1)
                if (row.inProgress) RecordingNow(highlighted, text)
                Text(row.line(profileNames), SetteeType.Body, color = quiet, maxLines = 2)
                if (row is RecordingsRow.Booking && row.clashes) {
                    Text(
                        stringResource(R.string.rec_clashes),
                        SetteeType.Body,
                        color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The channel's logo, or what stands in for one: a bell for a Reminder, a repeat for a series link. */
@Composable
private fun Leading(row: RecordingsRow, tint: Color) {
    Box(Modifier.size(LOGO_SIZE), contentAlignment = Alignment.Center) {
        when (row) {
            is RecordingsRow.OfReminder -> Icon(Icons.Filled.Notifications, contentDescription = null, tint = tint)
            is RecordingsRow.SeriesLink -> Icon(Icons.Filled.Repeat, contentDescription = null, tint = tint)
            is RecordingsRow.Booking, is RecordingsRow.Recorded -> row.recording?.logoUrl?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    modifier = Modifier.size(LOGO_SIZE).clip(SetteeShapes.Thumbnail),
                )
            }
        }
    }
}

/**
 * The dot and the word that say a recording is capturing now. Recording red on coral is about
 * 1.4:1, so on a highlighted row the dot takes the row's text colour instead.
 */
@Composable
private fun RecordingNow(highlighted: Boolean, text: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(DOT_SIZE)
                .testTag("recording-dot")
                .background(if (highlighted) SetteeColors.OnAccent else SetteeColors.Recording, CircleShape),
        )
        Spacer(Modifier.width(DOT_GAP))
        Text(stringResource(R.string.rec_in_progress), SetteeType.Body, color = text, maxLines = 1)
    }
}

private val RecordingsRow.title: String
    get() = when (this) {
        is RecordingsRow.Booking -> recording.title
        is RecordingsRow.Recorded -> recording.title
        is RecordingsRow.OfReminder -> reminder.title
        is RecordingsRow.SeriesLink -> rule.title
    }

private val RecordingsRow.inProgress
    get() = this is RecordingsRow.Recorded && recording.status == RecordingStatus.RECORDING

/** The quiet line under a row's title, saying whose it is when there's more than one Profile. */
@Composable
private fun RecordingsRow.line(profileNames: Map<Long, String>): String {
    val parts = when (this) {
        is RecordingsRow.Booking -> listOf(formatWhen(recording.scheduledStartMillis), recording.channelName)
        is RecordingsRow.OfReminder -> listOf(formatWhen(reminder.startUtcMillis), reminder.channelName)
        is RecordingsRow.SeriesLink -> listOf(nextAiring())
        is RecordingsRow.Recorded -> recording.details()
    }
    val profile = recording?.profileId?.let { profileNames[it] }?.let { stringResource(R.string.rec_for_profile, it) }
    return (parts + listOfNotNull(profile)).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun RecordingsRow.SeriesLink.nextAiring(): String = when {
    nextAtMillis != null && booked > 1 -> stringResource(
        R.string.rec_series_next_more,
        formatWhen(nextAtMillis),
        booked - 1,
    )
    nextAtMillis != null -> stringResource(R.string.rec_series_next_at, rule.channelName, formatWhen(nextAtMillis))
    else -> stringResource(R.string.rec_series_waiting, rule.channelName)
}

/** When · channel · length · size; what went wrong, for a failed one; channel · size so far, while recording. */
@Composable
private fun Recording.details(): List<String> {
    val size = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    return when (status) {
        RecordingStatus.FAILED -> listOf(
            stringResource(
                R.string.rec_status_failed_at,
                formatWhen(whenMillis),
                error ?: stringResource(R.string.rec_error_unknown),
            ),
        )
        RecordingStatus.RECORDING -> listOf(channelName, size)
        else -> listOf(formatWhen(whenMillis), channelName, formatLength(durationMillis), size)
    }
}

/** The Recording behind a row; a Reminder and a series link have none. */
private val RecordingsRow.recording: Recording?
    get() = when (this) {
        is RecordingsRow.Booking -> recording
        is RecordingsRow.Recorded -> recording
        else -> null
    }

// Built once, as GuideGrid's clock is: every visible row formats a time each time it's drawn.
private val whenFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

private fun formatWhen(utcMillis: Long): String = if (utcMillis <= 0) "" else whenFormat.format(Date(utcMillis))

private fun formatLength(millis: Long): String {
    if (millis <= 0) return ""
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = TimeUnit.MINUTES.toHours(minutes)
    val rest = minutes - TimeUnit.HOURS.toMinutes(hours)
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

private val HEADING_GAP = 12.dp

// A heading's line, the gap above it and the gap between it and its first row.
private val HEADING_ROOM = 40.dp
private val ROW_GAP = 4.dp
private val ROW_PADDING_SIDE = 16.dp
private val ROW_PADDING_TOP = 10.dp
private val LOGO_SIZE = 44.dp
private val DOT_SIZE = 10.dp
private val DOT_GAP = 8.dp
