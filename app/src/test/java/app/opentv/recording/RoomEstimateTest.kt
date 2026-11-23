/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.parser.ChannelNameNormalizer.RANK_4K
import app.opentv.data.parser.ChannelNameNormalizer.RANK_8K
import app.opentv.data.parser.ChannelNameNormalizer.RANK_FHD
import app.opentv.data.parser.ChannelNameNormalizer.RANK_HD
import app.opentv.data.parser.ChannelNameNormalizer.RANK_SD
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Which bookings may not fit in the room their storage has left, and how long there's room for (#473).
 * Sizes are worked out by hand from each test's figures.
 */
class RoomEstimateTest {

    @Test
    fun `a booking that may not fit has room worked out at its channel's own rate`() {
        // Channel 7's recordings came to 20 MB a minute: 1 GB is room for 50 minutes.
        val past =
            listOf(
                recorded(channel = 7, minutes = 60, bytes = 1_200 * MB),
                recorded(
                    channel = 7,
                    minutes = 30,
                    bytes =
                    600 * MB,
                ),
            )
        val room = RoomEstimate(
            past + booking(id = 1, channel = 7, minutes = 120),
            qualities = mapOf(7L to RANK_HD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to GB),
        )

        assertThat(room.shortOfRoom()).containsExactly(1L, 50L)
    }

    @Test
    fun `only a channel's last few recordings count towards its rate`() {
        // Its last five came to 20 MB a minute, so 1 GB is room for 50 minutes; one long before, in
        // another format, doesn't count.
        val lastFive = (1L..5L).map {
            recorded(channel = 7, minutes = 60, bytes = 1_200 * MB, endedAt = NOW - it * DAY)
        }
        val longBefore = recorded(channel = 7, minutes = 60, bytes = 6_000 * MB, endedAt = NOW - 30 * DAY)
        val room = RoomEstimate(
            lastFive + longBefore + booking(id = 1, channel = 7, minutes = 120),
            qualities = mapOf(7L to RANK_HD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to GB),
        )

        assertThat(room.shortOfRoom()).containsExactly(1L, 50L)
    }

    @Test
    fun `a channel never recorded here goes by recordings of channels of the same quality`() {
        // HD channel 8 came to 25 MB a minute, so 1 GB is room for 40 minutes; FHD channel 9 doesn't count.
        val past =
            listOf(
                recorded(channel = 8, minutes = 60, bytes = 1_500 * MB),
                recorded(
                    channel = 9,
                    minutes = 60,
                    bytes =
                    3_000 * MB,
                ),
            )
        val room = RoomEstimate(
            past + booking(id = 1, channel = 7, minutes = 120),
            qualities = mapOf(7L to RANK_HD, 8L to RANK_HD, 9L to RANK_FHD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to GB),
        )

        assertThat(room.shortOfRoom()).containsExactly(1L, 40L)
    }

    @Test
    fun `with nothing of the same quality recorded either, it goes by a fixed figure for the quality`() {
        // 1 GB is an hour of SD at 1 GB an hour; 24 minutes of HD or unknown at 2.5 GB; 17 of FHD at
        // 3.5 GB; 7 of 4K or 8K at 8 GB. A channel gone from the catalogue is of unknown quality.
        val minutesByQuality =
            mapOf(RANK_SD to 60L, RANK_HD to 24L, 0 to 24L, RANK_FHD to 17L, RANK_4K to 7L, RANK_8K to 7L, null to 24L)

        for ((quality, minutes) in minutesByQuality) {
            val room = RoomEstimate(
                listOf(booking(id = 1, channel = 7, minutes = 600)),
                qualities = if (quality == null) emptyMap() else mapOf(7L to quality),
                nowMillis = NOW,
                free = mapOf(RecordingStore.Device to GB),
            )

            assertWithMessage("quality $quality").that(room.shortOfRoom()).containsExactly(1L, minutes)
        }
    }

    @Test
    fun `recordings already running use room first, for what's left of them`() {
        // HD at 2.5 GB an hour: half an hour left of the running one takes 1.25 GB of the 3 GB, leaving
        // room for 42 minutes. With an end that isn't known, Record now's takes nothing.
        val running = listOf(running(channel = 8, minutesLeft = 30), running(channel = 9, minutesLeft = null))
        val room = RoomEstimate(
            running + booking(id = 1, channel = 7, minutes = 60),
            qualities = mapOf(7L to RANK_HD, 8L to RANK_HD, 9L to RANK_HD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to 3 * GB),
        )

        assertThat(room.shortOfRoom()).containsExactly(1L, 42L)
    }

    @Test
    fun `bookings that start before it on the same storage use room first, and others don't`() {
        // HD at 2.5 GB an hour. On this device the 9pm booking takes 2.5 GB of the 3 GB, leaving the
        // 11pm one room for 12 minutes. The NAS bookings, one before each, have room of their own.
        val bookings = listOf(
            booking(id = 1, channel = 7, minutes = 60, startsIn = HOUR),
            booking(id = 2, channel = 7, minutes = 60, startsIn = 3 * HOUR),
            booking(id = 3, channel = 7, minutes = 600, startsIn = 0, filePath = "smb://nas/tv/Recordings/a.ts"),
            booking(id = 4, channel = 7, minutes = 60, startsIn = 2 * HOUR, filePath = "smb://nas/tv/Recordings/b.ts"),
        )
        val room = RoomEstimate(
            bookings,
            qualities = mapOf(7L to RANK_HD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to 3 * GB, RecordingStore.Nas("nas", "tv") to 100 * GB),
        )

        assertThat(room.shortOfRoom()).containsExactly(2L, 12L)
    }

    @Test
    fun `a booking on storage whose free space couldn't be read is never said not to fit`() {
        // Only this device's is known, and it's nearly full; the USB drive's and the NAS's aren't.
        val room = RoomEstimate(
            listOf(
                booking(id = 1, channel = 7, minutes = 600, filePath = "usb://a.ts"),
                booking(id = 2, channel = 7, minutes = 600, filePath = "smb://nas/tv/Recordings/b.ts"),
            ),
            qualities = mapOf(7L to RANK_HD),
            nowMillis = NOW,
            free = mapOf(RecordingStore.Device to MB),
        )

        assertThat(room.shortOfRoom()).isEmpty()
    }

    private var ids = 100L

    private fun running(channel: Long, minutesLeft: Long?) = Recording(
        id = ids++,
        channelId = channel,
        sourceId = 1,
        channelName = "Channel $channel",
        title = "Recording",
        filePath = "/recordings/$ids.ts",
        streamUrl = "",
        scheduledStartMillis = if (minutesLeft == null) 0 else NOW - HOUR,
        scheduledEndMillis = if (minutesLeft == null) 0 else NOW + minutesLeft * MINUTE,
        startedAtMillis = NOW - HOUR,
        sizeBytes = 2 * GB,
        status = RecordingStatus.RECORDING,
    )

    private fun recorded(channel: Long, minutes: Long, bytes: Long, endedAt: Long = NOW - HOUR) = Recording(
        id = ids++,
        channelId = channel,
        sourceId = 1,
        channelName = "Channel $channel",
        title = "Recorded",
        filePath = "/recordings/$ids.ts",
        streamUrl = "",
        startedAtMillis = endedAt - minutes * MINUTE,
        endedAtMillis = endedAt,
        sizeBytes = bytes,
        status = RecordingStatus.COMPLETED,
    )

    private fun booking(
        id: Long,
        channel: Long,
        minutes: Long,
        startsIn: Long = HOUR,
        filePath: String = "/recordings/booking$id.ts",
    ) = Recording(
        id = id,
        channelId = channel,
        sourceId = 1,
        channelName = "Channel $channel",
        title = "Booking $id",
        filePath = filePath,
        streamUrl = "",
        scheduledStartMillis = NOW + startsIn,
        scheduledEndMillis = NOW + startsIn + minutes * MINUTE,
        status = RecordingStatus.SCHEDULED,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val MB = 1_000_000L
        const val GB = 1_000 * MB
    }
}
