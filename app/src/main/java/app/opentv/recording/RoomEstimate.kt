/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.parser.ChannelNameNormalizer

/**
 * Whether each booking may not fit in what its storage has left (#473). [recordings] are every
 * recording here, [qualities] each channel's `qualityRank` by id, and [free] the bytes free on each
 * storage whose free space could be read.
 */
class RoomEstimate(
    private val recordings: List<Recording>,
    private val qualities: Map<Long, Int>,
    private val nowMillis: Long,
    private val free: Map<RecordingStore, Long>,
) {
    /** The bookings that may not fit, by id, each with the minutes of room left for it. */
    fun shortOfRoom(): Map<Long, Long> = buildMap {
        val running = recordings.filter { it.status == RecordingStatus.RECORDING }
        // In the order they start, each taking its share before the next: one starting at the same
        // time as another goes by id, so the two can't each count on the room the other needs.
        val bookings = recordings.filter { it.status == RecordingStatus.SCHEDULED }
            .sortedWith(compareBy({ it.scheduledStartMillis }, { it.id }))
        for ((store, onStore) in bookings.groupBy { RecordingStore.of(it.filePath) }) {
            // What's written already is gone from what's free; only the rest of each is still to come.
            var room = (free[store] ?: continue) -
                running.filter { RecordingStore.of(it.filePath) == store }
                    .sumOf { it.minutesLeft * bytesPerMinute(it.channelId) }
            for (booking in onStore) {
                val rate = bytesPerMinute(booking.channelId)
                val needs = booking.minutes * rate
                if (room < needs) put(booking.id, (room / rate).coerceAtLeast(0))
                room -= needs
            }
        }
    }

    /** How long [this] running recording still has to go; nothing for one with no end. */
    private val Recording.minutesLeft
        get() = if (scheduledEndMillis > 0) (scheduledEndMillis - nowMillis).coerceAtLeast(0) / MINUTE else 0

    // Newest first, so a channel's last few are the first few. Only what has a size and length to go by.
    private val recorded = recordings
        .filter { it.status == RecordingStatus.COMPLETED && it.sizeBytes > 0 && it.durationMillis >= MINUTE }
        .sortedByDescending { it.endedAtMillis }

    /**
     * What recording [channelId] takes a minute: going by its own recordings here, or else by those of
     * channels of the same quality, or else by a typical figure for its quality.
     */
    private fun bytesPerMinute(channelId: Long): Long {
        val quality = qualities[channelId]
        return rateOf(recorded.filter { it.channelId == channelId })
            ?: rateOf(recorded.filter { quality != null && qualities[it.channelId] == quality })
            ?: (typicalGigabytesPerHour(quality) * GIGABYTE / MINUTES_PER_HOUR).toLong()
    }

    /** Roughly what an hour of a live stream of [quality] comes to; HD when the name doesn't say. */
    private fun typicalGigabytesPerHour(quality: Int?): Double = when {
        quality == null -> HD_GB_PER_HOUR
        quality >= ChannelNameNormalizer.RANK_4K -> UHD_GB_PER_HOUR
        quality >= ChannelNameNormalizer.RANK_FHD -> FHD_GB_PER_HOUR
        quality == ChannelNameNormalizer.RANK_SD -> SD_GB_PER_HOUR
        else -> HD_GB_PER_HOUR
    }

    /**
     * Size over length across the last few of [past], or null with nothing to go by. Only the last few,
     * as a provider changing how it sends a channel makes older ones wrong.
     */
    private fun rateOf(past: List<Recording>): Long? {
        val lastFew = past.take(LAST_FEW)
        val minutes = lastFew.sumOf { it.durationMillis / MINUTE }
        return if (minutes > 0) lastFew.sumOf { it.sizeBytes } / minutes else null
    }

    private val Recording.minutes get() = (scheduledEndMillis - scheduledStartMillis) / MINUTE

    private companion object {
        const val MINUTE = 60_000L
        const val MINUTES_PER_HOUR = 60
        const val GIGABYTE = 1_000_000_000L
        const val LAST_FEW = 5

        // Roughly what an hour of each quality comes to from IPTV providers: 4K and 8K alike.
        const val SD_GB_PER_HOUR = 1.0
        const val HD_GB_PER_HOUR = 2.5
        const val FHD_GB_PER_HOUR = 3.5
        const val UHD_GB_PER_HOUR = 8.0
    }
}
