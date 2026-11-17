/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.EpisodeTimings

/**
 * Which stretches of an episode get Skip intro or Skip recap (#518).
 *
 * The plan was to shift intros by the difference between the stream's length and the database's,
 * assuming any extra was at the front. Measuring the household provider's copies (#517) showed the
 * opposite: every timed intro sat at the database's time, and the extra or missing seconds were at the
 * end. So the timings are used as given, and only checked for fitting the stream. A wrong skip lands in
 * the middle of a scene, which is worse than no button, so anything doubtful offers nothing.
 */
object SkipCue {

    /** What a button skips; the two together are named in the order they play. */
    enum class Kind { INTRO, RECAP, RECAP_AND_INTRO, INTRO_AND_RECAP }

    /** A stretch from [fromMillis] to [toMillis] that one press skips to the end of. */
    data class Stretch(val kind: Kind, val fromMillis: Long, val toMillis: Long)

    /** The stretches to offer in a stream [streamMillis] long, in the order they play. */
    fun place(timings: EpisodeTimings?, streamMillis: Long): List<Stretch> {
        if (timings == null || streamMillis <= 0 || !creditsFit(timings, streamMillis)) return emptyList()
        val intro = stretch(Kind.INTRO, timings.introStartMillis, timings.introEndMillis, streamMillis)
        val recap = stretch(Kind.RECAP, timings.recapStartMillis, timings.recapEndMillis, streamMillis)
        return together(listOfNotNull(recap, intro).sortedBy { it.fromMillis })
    }

    /**
     * Whether the timed credits, if any, can belong to this stream. Ones that can't (an 88-minute
     * episode's against a 55-minute copy) mean the timings are another cut's, and its intro is no more
     * likely to fit than its credits.
     */
    private fun creditsFit(timings: EpisodeTimings, streamMillis: Long): Boolean =
        timings.creditsStartMillis?.let { UpNextCue.creditsFit(it, streamMillis) } ?: true

    /** [ordered] as one stretch when the second starts within 5 seconds of the first ending. */
    private fun together(ordered: List<Stretch>): List<Stretch> {
        if (ordered.size < 2) return ordered
        val (first, second) = ordered
        // Two buttons a few seconds apart would be pressed one after the other anyway.
        return if (second.fromMillis - first.toMillis <= TOGETHER_MILLIS) {
            val both = if (first.kind == Kind.RECAP) Kind.RECAP_AND_INTRO else Kind.INTRO_AND_RECAP
            listOf(Stretch(both, first.fromMillis, maxOf(first.toMillis, second.toMillis)))
        } else {
            ordered
        }
    }

    /** One timed stretch, if it can belong to the stream: ending after it starts, inside it, in its first half. */
    private fun stretch(kind: Kind, start: Long?, end: Long?, streamMillis: Long): Stretch? {
        if (start == null || end == null) return null
        val fits = end > start && end <= streamMillis && start < streamMillis / 2
        return Stretch(kind, start, end).takeIf { fits }
    }

    private const val TOGETHER_MILLIS = 5_000L
}
