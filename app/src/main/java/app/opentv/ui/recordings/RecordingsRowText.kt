/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import android.content.res.Resources
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.core.ViewerError
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.ui.dayTime
import java.util.concurrent.TimeUnit

// What a row on the Recordings screen says: its title, the quiet line under it, and why it stopped.

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
 * Why a recording stopped by itself, in Voice's sentence and detail line (docs/ERRORS.md): a failed
 * one, or a recorded one that was cut short (#473). One that failed before errors were stored that
 * way has a plain sentence and no detail. A booking's or one in progress's reason is a past attempt's.
 */
@Composable
internal fun RecordingsRow.reason(): ViewerError? {
    val stopped = (this as? RecordingsRow.Recorded)?.recording ?: return null
    return when (stopped.status) {
        RecordingStatus.FAILED -> stopped.error?.let(::readStored)
            ?: ViewerError(stringResource(R.string.rec_error_channel_stopped))
        RecordingStatus.COMPLETED -> stopped.error?.let(::readStored)
        else -> null
    }
}

private fun readStored(error: String) = ViewerError.fromStored(error) ?: ViewerError(error)

/** What a booking that may not fit says, with [minutes] of room: in time, not bytes. */
internal fun mayNotFit(resources: Resources, minutes: Long): String = if (roughly(minutes) > 0) {
    resources.getString(R.string.rec_may_not_fit, roomFor(resources, roughly(minutes)))
} else {
    resources.getString(R.string.rec_no_room)
}

/** "About" [minutes]: to the five minutes below, so it never says there's more room than there is. */
private fun roughly(minutes: Long) = if (minutes < ROUGHLY_FROM) minutes else minutes - minutes % ROUGHLY_TO

/** [minutes] as hours and minutes: "40 minutes", "2 hours", "1 hour 20 minutes". */
private fun roomFor(resources: Resources, minutes: Long): String {
    val hours = (minutes / MINUTES_PER_HOUR).toInt()
    val rest = (minutes % MINUTES_PER_HOUR).toInt()
    val inMinutes = resources.getQuantityString(R.plurals.rec_room_minutes, rest, rest)
    val inHours = resources.getQuantityString(R.plurals.rec_room_hours, hours, hours)
    return when {
        hours == 0 -> inMinutes
        rest == 0 -> inHours
        else -> resources.getString(R.string.rec_room_hours_minutes, inHours, inMinutes)
    }
}

// Under ten minutes of room, every minute is worth saying.
private const val ROUGHLY_FROM = 10
private const val ROUGHLY_TO = 5
private const val MINUTES_PER_HOUR = 60

/** The Recording behind a row; a Reminder and a series link have none. */
internal val RecordingsRow.recording: Recording?
    get() = when (this) {
        is RecordingsRow.Booking -> recording
        is RecordingsRow.Recorded -> recording
        else -> null
    }

@Composable
private fun formatWhen(utcMillis: Long): String = if (utcMillis <= 0) "" else dayTime(utcMillis)

private fun formatLength(millis: Long): String {
    if (millis <= 0) return ""
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
    val hours = TimeUnit.MINUTES.toHours(minutes)
    val rest = minutes - TimeUnit.HOURS.toMinutes(hours)
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}
