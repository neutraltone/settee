/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.ShowEpisode
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
     * The card for an episode with [timings], if it has any, in a stream [streamMillis] long; the
     * [restOfShow]'s usual credits length stands in when this one isn't timed.
     */
    fun place(timings: EpisodeTimings?, restOfShow: List<ShowEpisode>, streamMillis: Long): Cue {
        val timed = timings?.creditsStartMillis?.takeIf { creditsFit(it, streamMillis) }
        // Someone confirmed there are no credits: the show's usual length would be a guess at nothing.
        val usual = if (timed != null || timings?.noCredits == true) null else usualStart(restOfShow, streamMillis)
        return when {
            // The scene-after-credits marking belongs to the timings, so it counts only where they fit.
            timed != null -> Cue(timed + INTO_CREDITS_MILLIS, countsDown = timings?.sceneAfterCredits != true)
            usual != null -> Cue(usual)
            else -> fallback(streamMillis)
        }
    }

    /**
     * Where the show's usual credits would start, measured back from this stream's end, if that can fit
     * it. An episode whose own credits don't fit its length, where the provider gave one, is another
     * cut's or a mistake, and doesn't count.
     */
    private fun usualStart(restOfShow: List<ShowEpisode>, streamMillis: Long): Long? = restOfShow
        .filter { other ->
            val length = other.lengthMillis ?: return@filter true
            other.timings.creditsStartMillis?.let { creditsFit(it, length) } == true
        }
        .mapNotNull { it.timings.creditsMillis }
        .filter { it > 0 }
        .median()
        ?.let { streamMillis - it }
        ?.takeIf { creditsFit(it, streamMillis) }

    /**
     * Whether credits starting at [startMillis] can belong to this stream: inside it, in its second half
     * and within 12 minutes of its end. The longest credits measured ran 6 minutes; anything outside
     * these is another cut's timings or a mistake.
     */
    internal fun creditsFit(startMillis: Long, streamMillis: Long): Boolean = startMillis < streamMillis &&
        startMillis >= streamMillis / 2 &&
        streamMillis - startMillis <= LONGEST_CREDITS_MILLIS

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = size / 2
        return if (size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    private const val FALLBACK_MILLIS = 30_000L

    /**
     * Far enough into timed credits that they've plainly started when the card arrives. Not added to the
     * show's usual length, which is an estimate already.
     */
    private const val INTO_CREDITS_MILLIS = 3_000L
    private const val LONGEST_CREDITS_MILLIS = 12 * 60_000L
}
