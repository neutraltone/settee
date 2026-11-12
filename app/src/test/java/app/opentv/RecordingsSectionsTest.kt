/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.SeriesRule
import app.opentv.ui.recordings.RecordingsHeading
import app.opentv.ui.recordings.RecordingsRow
import app.opentv.ui.recordings.recordingsSections
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecordingsSectionsTest {

    @Test
    fun `bookings and Reminders come up together, soonest first`() {
        val late = booking(id = 1, start = NOW + 3 * HOUR)
        val early = booking(id = 2, start = NOW + HOUR)
        val between = reminder(id = 9, start = NOW + 2 * HOUR)

        val sections = recordingsSections(listOf(late, early), listOf(between), emptyList(), NOW)

        assertThat(sections.single().heading).isEqualTo(RecordingsHeading.ComingUp)
        assertThat(sections.single().rows).containsExactly(
            RecordingsRow.Booking(early, clashes = false),
            RecordingsRow.OfReminder(between),
            RecordingsRow.Booking(late, clashes = false),
        ).inOrder()
    }

    @Test
    fun `only bookings that overlap on the same provider clash`() {
        val first = booking(id = 1, start = NOW + HOUR, sourceId = 1)
        val overlapsFirst = booking(id = 2, start = NOW + HOUR + HOUR / 2, sourceId = 1)
        val otherProvider = booking(id = 3, start = NOW + HOUR, sourceId = 2)
        val afterwards = booking(id = 4, start = NOW + 3 * HOUR, sourceId = 1)

        val rows = recordingsSections(
            listOf(first, overlapsFirst, otherProvider, afterwards),
            emptyList(),
            emptyList(),
            NOW,
        )
            .single().rows

        assertThat(rows.filterIsInstance<RecordingsRow.Booking>().filter { it.clashes }.map { it.recording.id })
            .containsExactly(1L, 2L)
    }

    @Test
    fun `a recording in progress comes first under Recorded, above newer ones, then newest first`() {
        val older = recorded(id = 1, started = NOW - 3 * DAY)
        val inProgress = recorded(id = 2, started = NOW - 2 * DAY, status = RecordingStatus.RECORDING)
        val newer = recorded(id = 3, started = NOW - DAY)

        val sections = recordingsSections(listOf(older, inProgress, newer), emptyList(), emptyList(), NOW)

        assertThat(sections.single().heading).isEqualTo(RecordingsHeading.Recorded)
        assertThat(sections.single().rows).containsExactly(
            RecordingsRow.Recorded(inProgress),
            RecordingsRow.Recorded(newer),
            RecordingsRow.Recorded(older),
        ).inOrder()
    }

    @Test
    fun `a booking that failed before it started stays under Recorded, by when it was meant to record`() {
        val today = recorded(id = 1, started = NOW - HOUR)
        val failed = booking(id = 2, start = NOW - DAY).copy(status = RecordingStatus.FAILED, error = "No signal")
        val older = recorded(id = 3, started = NOW - 3 * DAY)

        val sections = recordingsSections(listOf(older, failed, today), emptyList(), emptyList(), NOW)

        assertThat(sections.single().heading).isEqualTo(RecordingsHeading.Recorded)
        assertThat(sections.single().rows).containsExactly(
            RecordingsRow.Recorded(today),
            RecordingsRow.Recorded(failed),
            RecordingsRow.Recorded(older),
        ).inOrder()
    }

    @Test
    fun `series links come last, A to Z, each with its next airing and how many are booked`() {
        val zebras = SeriesRule(id = 1, channelId = 1, channelName = "One", titleKey = "zebras", title = "Zebras")
        val ants = SeriesRule(id = 2, channelId = 1, channelName = "One", titleKey = "ants", title = "Ants")
        val nextAnts = booking(id = 10, start = NOW + HOUR).copy(seriesRuleId = 2)
        val laterAnts = booking(id = 11, start = NOW + DAY).copy(seriesRuleId = 2)

        val sections = recordingsSections(listOf(laterAnts, nextAnts), emptyList(), listOf(zebras, ants), NOW)

        assertThat(sections.map { it.heading })
            .containsExactly(RecordingsHeading.ComingUp, RecordingsHeading.SeriesLinks).inOrder()
        assertThat(sections.last().rows).containsExactly(
            RecordingsRow.SeriesLink(ants, nextAtMillis = NOW + HOUR, booked = 2),
            RecordingsRow.SeriesLink(zebras, nextAtMillis = null, booked = 0),
        ).inOrder()
    }

    @Test
    fun `a Reminder that has fired or whose programme has ended isn't coming up`() {
        val fired = reminder(id = 1, start = NOW + HOUR).copy(fired = true)
        val over = reminder(id = 2, start = NOW - 2 * HOUR)

        assertThat(recordingsSections(emptyList(), listOf(fired, over), emptyList(), NOW)).isEmpty()
    }

    private fun recorded(id: Long, started: Long, status: RecordingStatus = RecordingStatus.COMPLETED) = Recording(
        id = id,
        channelId = id,
        sourceId = 1,
        channelName = "Channel $id",
        title = "Recorded $id",
        filePath = "/rec/$id.ts",
        streamUrl = "",
        startedAtMillis = started,
        status = status,
    )

    private fun booking(id: Long, start: Long, sourceId: Long = 1) = Recording(
        id = id,
        channelId = id,
        sourceId = sourceId,
        channelName = "Channel $id",
        title = "Booking $id",
        filePath = "/rec/$id.ts",
        streamUrl = "",
        scheduledStartMillis = start,
        scheduledEndMillis = start + HOUR,
        status = RecordingStatus.SCHEDULED,
    )

    private fun reminder(id: Long, start: Long) = Reminder(
        id = id,
        channelId = id,
        channelName = "Channel $id",
        title = "Reminder $id",
        startUtcMillis = start,
        endUtcMillis = start + HOUR,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
