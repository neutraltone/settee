/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.EpisodeTimings
import app.opentv.player.NextEpisodeCountdown.Cue

/**
 * Where the Next Episode card comes up in a stream: just into the credits where they're timed, so the
 * viewer isn't left watching a minute of names first (#517).
 *
 * The timings are used as the database gave them, not measured back from the stream's end. Measuring
 * the household provider's copies showed they line up with the databases' at the start, and differ in
 * length at the end (end cards, a missing ident), so placing credits from the end put them out by the
 * whole difference. What can't fit this stream is thrown away rather than trusted: a wrong card is
 * worse than a late one.
 */
object UpNextCue {

    /** With nothing better: 30 seconds before the end, which still beats most shows' credits to it. */
    fun fallback(streamMillis: Long): Cue = Cue(cardAtMillis = streamMillis - FALLBACK_MILLIS)

    /**
     * The card for an episode with [timings], if it has any, in a stream [streamMillis] long; [others]
     * are the same show's other episodes' timings, whose usual credits length stands in when this one
     * isn't timed.
     */
    fun place(timings: EpisodeTimings?, others: List<EpisodeTimings>, streamMillis: Long): Cue {
        val counts = timings?.sceneAfterCredits != true
        val timed = timings?.creditsStartMillis?.takeIf { fits(it, streamMillis) }
        // Someone confirmed there are no credits: the show's usual length would be a guess at nothing.
        val usual = if (timings?.noCredits == true) null else usualStart(others, streamMillis)
        val start = timed ?: usual
        return if (start != null) {
            Cue(cardAtMillis = start + INTO_CREDITS_MILLIS, counts = counts)
        } else {
            fallback(streamMillis).copy(counts = counts)
        }
    }

    /** Where the show's usual credits would start, back from this stream's end, if that can fit it. */
    private fun usualStart(others: List<EpisodeTimings>, streamMillis: Long): Long? =
        others.mapNotNull { it.creditsMillis }.filter { it > 0 }.median()
            ?.let { streamMillis - it }
            ?.takeIf { fits(it, streamMillis) }

    /**
     * Whether credits starting at [startMillis] can belong to this stream: inside it, in its second half
     * and within 12 minutes of its end. The longest credits measured ran 6 minutes; anything outside
     * these is another cut's timings or a mistake.
     */
    private fun fits(startMillis: Long, streamMillis: Long): Boolean = startMillis < streamMillis &&
        startMillis >= streamMillis / 2 &&
        streamMillis - startMillis <= LONGEST_CREDITS_MILLIS

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = size / 2
        return if (size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private const val FALLBACK_MILLIS = 30_000L

    /** Far enough in that the credits have plainly started when the card arrives. */
    private const val INTO_CREDITS_MILLIS = 3_000L
    private const val LONGEST_CREDITS_MILLIS = 12 * 60_000L
}
