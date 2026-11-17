/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.EpisodeTimings
import app.opentv.player.NextEpisodeCountdown.Cue
import app.opentv.player.UpNextCue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Where the Next Episode card comes up. The worked examples are episodes measured on the household's
 * provider in #517: the stream's length, the credits seen, and what the databases said.
 */
class UpNextCueTest {

    private fun timed(start: String, end: String? = null, sceneAfter: Boolean = false) = EpisodeTimings(
        show = "tmdb:1",
        season = 1,
        episode = 1,
        creditsStartMillis = ms(start),
        creditsEndMillis = end?.let(::ms),
        sceneAfterCredits = sceneAfter,
    )

    @Test
    fun `a timed episode's card comes up 3 seconds into its credits, as the database timed them`() {
        // Moon Knight 1x1: a stream 79 s longer than the database's copy, credits seen at 42:59.
        val cue = UpNextCue.place(timed("42:59", end = "45:32"), others = emptyList(), streamMillis = ms("46:51"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("43:02")))
    }

    @Test
    fun `credits timed past the stream's end still place the card when they start inside it`() {
        // Wednesday 1x1: the stream is 79 s shorter than the database's credits end.
        val cue = UpNextCue.place(timed("55:13", end = "60:47"), others = emptyList(), streamMillis = ms("59:28"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("55:16")))
    }

    @Test
    fun `credits that start after the stream ends are another cut's, and 30 seconds before the end is used`() {
        // Brooklyn Nine-Nine 1x1: credits timed at 57 minutes in a 23-minute episode.
        val cue = UpNextCue.place(timed("57:14", end = "58:20"), others = emptyList(), streamMillis = ms("22:50"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("22:20")))
    }

    @Test
    fun `credits timed in the first half are ignored`() {
        // Within 12 minutes of the end, so only the first-half rule turns it away.
        val cue = UpNextCue.place(timed("9:59"), others = emptyList(), streamMillis = ms("20:00"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("19:30")))
    }

    @Test
    fun `credits timed more than 12 minutes before the end are ignored`() {
        val tooEarly = UpNextCue.place(timed("47:59"), others = emptyList(), streamMillis = ms("60:00"))
        val justIn = UpNextCue.place(timed("48:00"), others = emptyList(), streamMillis = ms("60:00"))

        assertThat(tooEarly).isEqualTo(Cue(cardAtMillis = ms("59:30")))
        assertThat(justIn).isEqualTo(Cue(cardAtMillis = ms("48:03")))
    }

    @Test
    fun `a scene after the credits gets the card without a countdown`() {
        val cue = UpNextCue.place(timed("52:00", sceneAfter = true), others = emptyList(), streamMillis = ms("56:00"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("52:03"), counts = false))
    }

    @Test
    fun `an untimed episode uses the show's usual credits length, back from the stream's end`() {
        val others = listOf(timed("40:00", "41:20"), timed("40:00", "41:30"), timed("40:00", "43:00"))

        val cue = UpNextCue.place(timings = null, others = others, streamMillis = ms("50:00"))

        // The median of 80, 90 and 180 seconds is 90.
        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("48:33")))
    }

    @Test
    fun `episodes whose credits run to an unknown end don't count towards the usual length`() {
        val others = listOf(timed("40:00"), timed("40:00", "41:30"))

        val cue = UpNextCue.place(timings = null, others = others, streamMillis = ms("50:00"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("48:33")))
    }

    @Test
    fun `a usual length longer than 12 minutes is ignored`() {
        val cue = UpNextCue.place(timings = null, others = listOf(timed("30:00", "42:01")), streamMillis = ms("50:00"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("49:30")))
    }

    @Test
    fun `an episode confirmed to have no credits gets 30 seconds before the end, whatever the show's usual`() {
        val none = EpisodeTimings(show = "tmdb:1", season = 1, episode = 2, noCredits = true)

        val cue = UpNextCue.place(none, others = listOf(timed("40:00", "41:30")), streamMillis = ms("50:00"))

        assertThat(cue).isEqualTo(Cue(cardAtMillis = ms("49:30")))
    }

    @Test
    fun `with nothing timed the card comes up 30 seconds before the end`() {
        assertThat(UpNextCue.place(timings = null, others = emptyList(), streamMillis = ms("50:00")))
            .isEqualTo(Cue(cardAtMillis = ms("49:30")))
        assertThat(UpNextCue.fallback(ms("50:00"))).isEqualTo(Cue(cardAtMillis = ms("49:30")))
    }

    /** `m:ss` as millis. */
    private fun ms(time: String): Long {
        val (minutes, seconds) = time.split(':').map { it.toLong() }
        return (minutes * 60 + seconds) * 1_000
    }
}
