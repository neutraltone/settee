/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.NextEpisodeCountdown.Decision
import app.opentv.player.NextEpisodeCountdown.Overlay
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NextEpisodeCountdownTest {

    private val countdown = NextEpisodeCountdown().apply { newEpisode(hasNext = true) }

    /** A 40-minute episode, [secondsLeft] from its end. */
    private fun at(secondsLeft: Int) = NextEpisodeCountdown.Playback(
        positionMillis = EPISODE_MILLIS - secondsLeft * 1_000L,
        durationMillis = EPISODE_MILLIS,
    )

    /** The same episode, [millis] in. */
    private fun atMillis(millis: Long) =
        NextEpisodeCountdown.Playback(positionMillis = millis, durationMillis = EPISODE_MILLIS)

    @Test
    fun `with no timings the card comes up 30 seconds before the end and counts down from 15`() {
        countdown.update(at(secondsLeft = 31))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.update(at(secondsLeft = 30))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))

        assertThat(countdown.update(at(secondsLeft = 16))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 1, counting = true))
    }

    @Test
    fun `when the countdown runs out the Next Episode plays`() {
        countdown.update(at(secondsLeft = 30))
        assertThat(countdown.update(at(secondsLeft = 16))).isNull()

        assertThat(countdown.update(at(secondsLeft = 15))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `on a timed episode the card comes up at its cue and counts down 15 seconds from there`() {
        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }

        countdown.update(atMillis(CREDITS_CARD_MILLIS - 1))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.update(atMillis(CREDITS_CARD_MILLIS))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))

        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 14_000))).isNull()
        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 15_000))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `timings that arrive while the episode plays move the card`() {
        countdown.update(atMillis(CREDITS_CARD_MILLIS))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }
        countdown.update(atMillis(CREDITS_CARD_MILLIS))

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `a new episode goes back to 30 seconds before the end until its own timings are known`() {
        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }

        countdown.newEpisode(hasNext = true)
        countdown.update(atMillis(CREDITS_CARD_MILLIS))

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
    }

    @Test
    fun `a scene after the credits gets the card without a countdown, and the episode plays to its end`() {
        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS, countsDown = false) }

        countdown.update(atMillis(CREDITS_CARD_MILLIS))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = false))
        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 60_000))).isNull()

        assertThat(countdown.update(at(secondsLeft = 0).copy(ended = true))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `when the episode ends before the countdown the Next Episode plays`() {
        countdown.update(at(secondsLeft = 20))

        assertThat(countdown.update(at(secondsLeft = 20).copy(ended = true))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `a stream that ends before its cue still offers the Next Episode`() {
        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }

        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS - 60_000).copy(ended = true)))
            .isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `pausing holds the countdown where it is`() {
        countdown.update(at(secondsLeft = 30))
        countdown.update(at(secondsLeft = 24))
        assertThat(countdown.update(at(secondsLeft = 24))).isNull()

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 9, counting = true))
    }

    @Test
    fun `OK on the card plays the Next Episode at once`() {
        countdown.update(at(secondsLeft = 27))

        assertThat(countdown.ok()).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `Cancel dismisses the card, the credits play to their end, and then the Next Episode plays`() {
        countdown.update(at(secondsLeft = 27))

        countdown.cancel()

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
        assertThat(countdown.update(at(secondsLeft = 5))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.None)
        assertThat(countdown.update(at(secondsLeft = 0).copy(ended = true))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `with auto-play off, the end of the credits after Cancel moves on to nothing, so the player can close`() {
        val manual = NextEpisodeCountdown(autoPlay = { false }).apply { newEpisode(hasNext = true) }
        manual.update(at(secondsLeft = 27))
        manual.cancel()

        assertThat(manual.update(at(secondsLeft = 0).copy(ended = true))).isNull()
        assertThat(manual.overlay).isEqualTo(Overlay.None)
    }

    @Test
    fun `with auto-play off, a card still up when the episode ends says so, and Cancel then takes it away`() {
        val manual = NextEpisodeCountdown(autoPlay = { false }).apply { newEpisode(hasNext = true) }
        manual.update(at(secondsLeft = 27))

        assertThat(manual.update(at(secondsLeft = 0).copy(ended = true))).isNull()
        assertThat(manual.overlay).isEqualTo(Overlay.Card(secondsLeft = 0, counting = false, atEnd = true))

        manual.cancel()
        assertThat(manual.update(at(secondsLeft = 0).copy(ended = true))).isNull()
        assertThat(manual.overlay).isEqualTo(Overlay.None)
    }

    @Test
    fun `Cancel shows someone is watching, so the episode it plays on to starts the run again`() {
        repeat(3) { countdown.runToEnd() }

        countdown.update(at(secondsLeft = 27))
        countdown.cancel()
        assertThat(countdown.update(at(secondsLeft = 0).copy(ended = true))).isEqualTo(Decision.PlayNext)
        countdown.newEpisode(hasNext = true)

        repeat(3) { assertThat(countdown.runToEnd()).isEqualTo(Decision.PlayNext) }
    }

    @Test
    fun `seeking back before the card hides it, and back past it counts down afresh from where it lands`() {
        countdown.update(at(secondsLeft = 27))

        countdown.update(at(secondsLeft = 300))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.update(at(secondsLeft = 23))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `a seek past the card's countdown brings the card up with all 15 seconds, rather than moving on`() {
        // The owner's report: a seek to 44:32 played the Next Episode before any of the credits.
        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }
        countdown.update(atMillis(CREDITS_CARD_MILLIS - 600_000))

        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 60_000))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))

        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 74_000))).isNull()
        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 75_000))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `starting partway through the credits, as a resume point can, counts down all 15 seconds`() {
        countdown.update(at(secondsLeft = 22))

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `a seek into the last 15 seconds brings no card, and the Next Episode plays when the episode ends`() {
        countdown.update(at(secondsLeft = 300))

        assertThat(countdown.update(at(secondsLeft = 10))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.None)
        assertThat(countdown.update(at(secondsLeft = 0).copy(ended = true))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `after a seek into the last 15 seconds, seeking back before the credits and into them brings the card back`() {
        countdown.update(at(secondsLeft = 300))
        countdown.update(at(secondsLeft = 10))

        countdown.update(at(secondsLeft = 300))
        countdown.update(at(secondsLeft = 25))

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `timings that arrive with playback already past the new cue count down from where it is`() {
        countdown.update(atMillis(CREDITS_CARD_MILLIS + 40_000))

        countdown.cue = { NextEpisodeCountdown.Cue(cardAtMillis = CREDITS_CARD_MILLIS) }

        assertThat(countdown.update(atMillis(CREDITS_CARD_MILLIS + 40_500))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `seeking back before the card after Watch credits brings the card back`() {
        countdown.update(at(secondsLeft = 27))
        countdown.cancel()

        countdown.update(at(secondsLeft = 300))
        countdown.update(at(secondsLeft = 27))

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `with auto-play off the card shows but waits for OK`() {
        val manual = NextEpisodeCountdown(autoPlay = { false }).apply { newEpisode(hasNext = true) }

        manual.update(at(secondsLeft = 30))
        manual.update(at(secondsLeft = 25))
        assertThat(manual.overlay).isEqualTo(Overlay.Card(secondsLeft = 10, counting = false))
        assertThat(manual.update(at(secondsLeft = 0).copy(ended = true))).isNull()

        assertThat(manual.ok()).isEqualTo(Decision.PlayNext)
    }

    /** Plays the current episode through its credits to the end, as nobody touching the remote would. */
    private fun NextEpisodeCountdown.runToEnd(): Decision? {
        (30 downTo 16).forEach { update(at(secondsLeft = it)) }
        return update(at(secondsLeft = 15)).also { if (it == Decision.PlayNext) newEpisode(hasNext = true) }
    }

    @Test
    fun `after 3 episodes started by the countdown the player asks whether anyone is still watching`() {
        repeat(3) { assertThat(countdown.runToEnd()).isEqualTo(Decision.PlayNext) }

        assertThat(countdown.runToEnd()).isEqualTo(Decision.Pause)
        assertThat(countdown.overlay).isEqualTo(Overlay.StillWatching(streamStopped = false))
    }

    @Test
    fun `any input during playback starts the run again`() {
        repeat(3) { countdown.runToEnd() }

        countdown.input()

        repeat(3) { assertThat(countdown.runToEnd()).isEqualTo(Decision.PlayNext) }
        assertThat(countdown.runToEnd()).isEqualTo(Decision.Pause)
    }

    @Test
    fun `OK on the card starts the run again`() {
        repeat(3) { countdown.runToEnd() }

        countdown.update(at(secondsLeft = 25))
        countdown.ok()
        countdown.newEpisode(hasNext = true)

        repeat(3) { assertThat(countdown.runToEnd()).isEqualTo(Decision.PlayNext) }
    }

    @Test
    fun `Continue watching plays the Next Episode and starts a new run`() {
        repeat(4) { countdown.runToEnd() }

        assertThat(countdown.continueWatching()).isEqualTo(Decision.PlayNext)
        countdown.newEpisode(hasNext = true)

        repeat(3) { assertThat(countdown.runToEnd()).isEqualTo(Decision.PlayNext) }
    }

    @Test
    fun `with no answer the stream is stopped after 5 minutes and the question stays up`() {
        var now = 0L
        val unattended = NextEpisodeCountdown(clock = { now }).apply { newEpisode(hasNext = true) }
        repeat(4) { unattended.runToEnd() }

        now += 299_000
        assertThat(unattended.update(at(secondsLeft = 0))).isNull()

        now += 1_000
        assertThat(unattended.update(at(secondsLeft = 0))).isEqualTo(Decision.StopStream)
        assertThat(unattended.overlay).isEqualTo(Overlay.StillWatching(streamStopped = true))
        assertThat(unattended.update(at(secondsLeft = 0))).isNull()
    }

    @Test
    fun `the Next Episode is asked for once, however often the end is polled before it starts`() {
        countdown.update(at(secondsLeft = 30))
        assertThat(countdown.update(at(secondsLeft = 0))).isEqualTo(Decision.PlayNext)

        assertThat(countdown.update(at(secondsLeft = 0))).isNull()
        assertThat(countdown.ok()).isNull()
    }

    @Test
    fun `no card when less than 30 seconds would play before it`() {
        countdown.update(NextEpisodeCountdown.Playback(positionMillis = 30_000, durationMillis = 44_000))

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
    }

    @Test
    fun `no card when the duration is unknown`() {
        countdown.update(NextEpisodeCountdown.Playback(positionMillis = 30_000, durationMillis = 0))

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
    }

    @Test
    fun `no card on a series' last episode`() {
        countdown.newEpisode(hasNext = false)

        countdown.update(at(secondsLeft = 25))

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
    }

    private companion object {
        const val EPISODE_MILLIS = 2_400_000L

        /** Where a timed episode's card comes up: 3 s into credits that start 2 minutes before the end. */
        const val CREDITS_CARD_MILLIS = EPISODE_MILLIS - 117_000L
    }
}
