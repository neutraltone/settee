/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.recordings

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.recording.RecordingStorage
import app.opentv.ui.options.OptionsFor

/** The Recordings screen's headings, in the order they're shown. */
enum class RecordingsHeading { ComingUp, Recorded, SeriesLinks }

/** One heading and its rows; never empty. */
data class RecordingsSection(val heading: RecordingsHeading, val rows: List<RecordingsRow>)

/** One row on the Recordings screen. */
sealed interface RecordingsRow {
    /** A booked Recording; [clashes] when it overlaps another booking on the same provider. */
    data class Booking(val recording: Recording, val clashes: Boolean) : RecordingsRow

    data class OfReminder(val reminder: Reminder) : RecordingsRow

    /** Recorded, in progress or failed. */
    data class Recorded(val recording: Recording) : RecordingsRow

    /**
     * A series link, listed even before its first airing is booked (the guide may not reach it yet):
     * [nextAtMillis] is its soonest booking, if any, of the [booked] it has.
     */
    data class SeriesLink(val rule: SeriesRule, val nextAtMillis: Long?, val booked: Int) : RecordingsRow
}

/**
 * What the Recordings screen lists, under its headings. Headings with nothing under them are left
 * out, so the first section's first row is where the Highlight starts.
 */
fun recordingsSections(
    recordings: List<Recording>,
    reminders: List<Reminder>,
    seriesRules: List<SeriesRule>,
    nowMillis: Long,
): List<RecordingsSection> {
    val bookings = recordings.filter { it.status == RecordingStatus.SCHEDULED }
    val clashing = clashingIds(bookings)
    val comingUp = bookings.map { it.scheduledStartMillis to RecordingsRow.Booking(it, it.id in clashing) } +
        reminders.filter { !it.fired && it.endUtcMillis > nowMillis }
            .map { it.startUtcMillis to RecordingsRow.OfReminder(it) }
    val recorded = recordings.filter { it.status != RecordingStatus.SCHEDULED }
        .sortedWith(compareBy<Recording> { it.status != RecordingStatus.RECORDING }.thenByDescending { it.whenMillis })
        .map(RecordingsRow::Recorded)
    val bookingsByRule = bookings.groupBy { it.seriesRuleId }
    val seriesLinks = seriesRules.sortedBy { it.title.lowercase() }.map { rule ->
        val booked = bookingsByRule[rule.id].orEmpty()
        RecordingsRow.SeriesLink(rule, booked.minOfOrNull { it.scheduledStartMillis }, booked.size)
    }
    return listOf(
        RecordingsSection(RecordingsHeading.ComingUp, comingUp.sortedBy { it.first }.map { it.second }),
        RecordingsSection(RecordingsHeading.Recorded, recorded),
        RecordingsSection(RecordingsHeading.SeriesLinks, seriesLinks),
    ).filter { it.rows.isNotEmpty() }
}

/**
 * Bookings that overlap another *on the same provider*: that's the real single-connection clash.
 * Overlaps spread across two providers (the multi-provider fallback did its job) both record fine.
 */
private fun clashingIds(bookings: List<Recording>): Set<Long> = buildSet {
    for (i in bookings.indices) {
        for (j in i + 1 until bookings.size) {
            val a = bookings[i]
            val b = bookings[j]
            if (a.sourceId == b.sourceId &&
                a.scheduledStartMillis < b.scheduledEndMillis &&
                b.scheduledStartMillis < a.scheduledEndMillis
            ) {
                add(a.id)
                add(b.id)
            }
        }
    }
}

/**
 * When a Recording was meant to record, or when it began if it was started on the spot. A booking
 * that failed before it began has no start of its own, but still belongs where it was meant to be.
 */
internal val Recording.whenMillis get() = if (scheduledStartMillis > 0) scheduledStartMillis else startedAtMillis

internal fun RecordingsRow.options(): OptionsFor = when (this) {
    is RecordingsRow.Booking -> OptionsFor.OfRecording(recording.status)
    is RecordingsRow.Recorded -> OptionsFor.OfRecording(recording.status)
    is RecordingsRow.OfReminder -> OptionsFor.OfReminder
    is RecordingsRow.SeriesLink -> OptionsFor.OfSeriesLink
}

/** What OK does on a row; null where it opens the options panel, as nothing else would be safe. */
internal sealed interface OkAction {
    data class Play(val recording: Recording) : OkAction

    data class Retry(val recording: Recording) : OkAction
}

internal fun RecordingsRow.okAction(): OkAction? {
    val recording = (this as? RecordingsRow.Recorded)?.recording ?: return null
    return when (recording.status) {
        RecordingStatus.COMPLETED -> OkAction.Play(recording)
        RecordingStatus.FAILED -> OkAction.Retry(recording)
        RecordingStatus.RECORDING -> OkAction.Play(recording).takeIf { recording.watchableWhileRecording }
        RecordingStatus.SCHEDULED -> null
    }
}

/**
 * Whether a recording in progress can be watched as it records: tail-followed off disk (internal) or
 * over SMB (NAS), with no second provider connection. USB (SAF) can't be read while it's written.
 */
private val Recording.watchableWhileRecording
    get() = !RecordingStorage.isContent(filePath) && !RecordingStorage.isUsbPlaceholder(filePath)
