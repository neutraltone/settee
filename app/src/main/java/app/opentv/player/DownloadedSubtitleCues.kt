/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser

/**
 * A Downloaded Subtitle's lines, parsed once, for the player to draw over the picture.
 *
 * Drawn by Settee rather than handed to ExoPlayer as a side-loaded track, because of Timing: a
 * side-loaded track's times are fixed when it's prepared, so every 0.1-second step would mean
 * replacing the media item and buffering again, where the viewer needs to watch the lines move as
 * they adjust. Here the offset is applied each time the lines are looked up, and the stream itself
 * is never touched, so its position can't be lost.
 */
@OptIn(UnstableApi::class)
class DownloadedSubtitleCues private constructor(private val lines: List<CuesWithTiming>) {

    /** The lines showing at [positionMillis] of the video, the subtitle moved [offsetMillis] (positive is later). */
    fun at(positionMillis: Long, offsetMillis: Long): List<Cue> {
        val atUs = (positionMillis - offsetMillis) * MICROS
        return lines.filter { atUs >= it.startTimeUs && atUs < it.endTimeUs }.flatMap { it.cues }
    }

    companion object {
        private const val MICROS = 1_000L

        /** Parses SRT [text]; anything that isn't SRT gives no lines rather than an error mid-film. */
        fun parse(text: String): DownloadedSubtitleCues {
            val lines = mutableListOf<CuesWithTiming>()
            val bytes = text.toByteArray(Charsets.UTF_8)
            runCatching {
                SubripParser().parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { lines += it }
            }
            return DownloadedSubtitleCues(lines.filter { it.cues.isNotEmpty() })
        }
    }
}
