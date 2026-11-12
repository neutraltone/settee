/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// What a row on the Recordings screen says: its title, the quiet line under it, and why it failed.

internal val RecordingsRow.title: String
    get() = when (this) {
        is RecordingsRow.Booking -> recording.title
        is RecordingsRow.Recorded -> recording.title
        is RecordingsRow.OfReminder -> reminder.title
        is RecordingsRow.SeriesLink -> rule.title
    }

internal val RecordingsRow.inProgress
    get() = this is RecordingsRow.Recorded && recording.status == RecordingStatus.RECORDING

/** The quiet line under a row's title, saying whose it is when there's more than one Profile. */
@Composable
internal fun RecordingsRow.line(profileNames: Map<Long, String>): String {
    val parts = when (this) {
        is RecordingsRow.Booking -> listOf(formatWhen(recording.scheduledStartMillis), recording.channelName)
        // Only the ones that will change the channel by themselves say so: the bell already says Reminder.
        is RecordingsRow.OfReminder -> listOfNotNull(
            formatWhen(reminder.startUtcMillis),
            reminder.channelName,
            stringResource(R.string.rec_reminder_autoswitch).takeIf { reminder.autoTune },
        )
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

/**
 * When · channel · length · size. A failed one says that it failed and when, and one still recording
 * its channel and size so far.
 */
@Composable
private fun Recording.details(): List<String> {
    val size = Formatter.formatShortFileSize(LocalContext.current, sizeBytes)
    return when (status) {
        RecordingStatus.FAILED -> listOf(stringResource(R.string.rec_status_failed, formatWhen(whenMillis)))
        RecordingStatus.RECORDING -> listOf(channelName, size)
        else -> listOf(formatWhen(whenMillis), channelName, formatLength(durationMillis), size)
    }
}

/**
 * Why a failed recording failed, in Voice's sentence and detail line (docs/ERRORS.md). One that
 * failed before errors were stored that way has a plain sentence and no detail.
 */
@Composable
internal fun RecordingsRow.failure(): ViewerError? {
    val failed = recording?.takeIf { it.status == RecordingStatus.FAILED } ?: return null
    return failed.error?.let { ViewerError.fromStored(it) ?: ViewerError(it) }
        ?: ViewerError(stringResource(R.string.rec_error_nothing))
}

/** The Recording behind a row; a Reminder and a series link have none. */
internal val RecordingsRow.recording: Recording?
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
