/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.EpisodeTimings
import app.opentv.player.SkipCue
import app.opentv.player.SkipCue.Kind
import app.opentv.player.SkipCue.Stretch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which stretches of an episode get Skip intro or Skip recap (#518). The worked examples are episodes
 * measured on the household's provider in #517, where every timed intro sat at the database's time
 * with no shift, whatever the difference in length.
 */
class SkipCueTest {

    private fun timings(
        intro: Pair<String, String>? = null,
        recap: Pair<String, String>? = null,
        credits: String? = null,
    ) = EpisodeTimings(
        show = "tmdb:1",
        season = 1,
        episode = 1,
        introStartMillis = intro?.first?.let(::ms),
        introEndMillis = intro?.second?.let(::ms),
        recapStartMillis = recap?.first?.let(::ms),
        recapEndMillis = recap?.second?.let(::ms),
        creditsStartMillis = credits?.let(::ms),
    )

    @Test
    fun `a timed intro is skipped as the database timed it, whatever the stream's length`() {
        // Moon Knight 1x1: a stream 79 s longer than the database's copy, intro seen at 1:53-2:27.
        val stretches = SkipCue.place(timings(intro = "1:53" to "2:27", credits = "42:59"), streamMillis = ms("46:51"))

        assertThat(stretches).containsExactly(Stretch(Kind.INTRO, ms("1:53"), ms("2:27")))
    }

    @Test
    fun `an intro is offered without any credits timing`() {
        val stretches = SkipCue.place(timings(intro = "8:34" to "9:17"), streamMillis = ms("53:51"))

        assertThat(stretches).containsExactly(Stretch(Kind.INTRO, ms("8:34"), ms("9:17")))
    }

    @Test
    fun `an intro starting at the very beginning is skipped from 0`() {
        // The clients read a missing or null start as 0, as The Office (US) 1x1's is.
        val stretches = SkipCue.place(timings(intro = "0:00" to "0:31"), streamMillis = ms("23:25"))

        assertThat(stretches).containsExactly(Stretch(Kind.INTRO, 0, ms("0:31")))
    }

    @Test
    fun `a recap gets its own stretch, apart from an intro well after it`() {
        val stretches = SkipCue.place(
            timings(recap = "0:00" to "1:10", intro = "4:00" to "4:40"),
            streamMillis = ms("50:00"),
        )

        assertThat(stretches).containsExactly(
            Stretch(Kind.RECAP, 0, ms("1:10")),
            Stretch(Kind.INTRO, ms("4:00"), ms("4:40")),
        ).inOrder()
    }

    @Test
    fun `a recap ending within 5 seconds of the intro is skipped with it in one go`() {
        val stretches = SkipCue.place(
            timings(recap = "0:00" to "1:10", intro = "1:15" to "2:00"),
            streamMillis = ms("50:00"),
        )

        assertThat(stretches).containsExactly(Stretch(Kind.RECAP_AND_INTRO, 0, ms("2:00")))
    }

    @Test
    fun `an intro ending within 5 seconds of the recap is skipped with it in one go`() {
        val stretches = SkipCue.place(
            timings(intro = "0:30" to "1:00", recap = "1:05" to "2:10"),
            streamMillis = ms("50:00"),
        )

        assertThat(stretches).containsExactly(Stretch(Kind.RECAP_AND_INTRO, ms("0:30"), ms("2:10")))
    }

    @Test
    fun `a recap more than 5 seconds from the intro keeps its own button`() {
        val stretches = SkipCue.place(
            timings(recap = "0:00" to "1:10", intro = "1:16" to "2:00"),
            streamMillis = ms("50:00"),
        )

        assertThat(stretches.map { it.kind }).containsExactly(Kind.RECAP, Kind.INTRO).inOrder()
    }

    @Test
    fun `an intro that ends past the stream's end is another cut's, and isn't offered`() {
        val stretches = SkipCue.place(timings(intro = "22:00" to "23:30"), streamMillis = ms("23:00"))

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `an intro starting in the second half isn't offered`() {
        val stretches = SkipCue.place(timings(intro = "25:01" to "25:40"), streamMillis = ms("50:00"))

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `an intro that doesn't end after it starts isn't offered`() {
        val stretches = SkipCue.place(timings(intro = "2:00" to "2:00"), streamMillis = ms("50:00"))

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `an intro with no end timed isn't offered`() {
        val stretches = SkipCue.place(
            EpisodeTimings(show = "tmdb:1", season = 1, episode = 1, introStartMillis = ms("2:00")),
            streamMillis = ms("50:00"),
        )

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `credits that can't belong to the stream mean the timings are another cut's, so nothing is skipped`() {
        // Sherlock 1x1: an 88-minute episode's timings against a 55-minute copy.
        val stretches = SkipCue.place(timings(intro = "2:04" to "2:38", credits = "87:25"), streamMillis = ms("55:15"))

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `an unknown stream length offers nothing`() {
        val stretches = SkipCue.place(timings(intro = "1:53" to "2:27"), streamMillis = 0)

        assertThat(stretches).isEmpty()
    }

    @Test
    fun `no timings offer nothing`() {
        assertThat(SkipCue.place(null, streamMillis = ms("50:00"))).isEmpty()
    }

    private fun ms(time: String): Long {
        val (minutes, seconds) = time.split(':').map { it.toLong() }
        return (minutes * 60 + seconds) * 1_000
    }
}
