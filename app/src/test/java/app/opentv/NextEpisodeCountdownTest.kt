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

    @Test
    fun `15 seconds before the end the card shows with the time left`() {
        countdown.update(at(secondsLeft = 16))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.update(at(secondsLeft = 15))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 15, counting = true))
    }

    @Test
    fun `when the countdown runs out the Next Episode plays`() {
        assertThat(countdown.update(at(secondsLeft = 1))).isNull()

        assertThat(countdown.update(at(secondsLeft = 0))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `when the episode ends before the countdown the Next Episode plays`() {
        countdown.update(at(secondsLeft = 2))

        assertThat(countdown.update(at(secondsLeft = 2).copy(ended = true))).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `pausing holds the countdown where it is`() {
        countdown.update(at(secondsLeft = 9))
        assertThat(countdown.update(at(secondsLeft = 9))).isNull()

        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 9, counting = true))
    }

    @Test
    fun `OK on the card plays the Next Episode at once`() {
        countdown.update(at(secondsLeft = 12))

        assertThat(countdown.ok()).isEqualTo(Decision.PlayNext)
    }

    @Test
    fun `Cancel dismisses the card and the episode plays to its end without advancing`() {
        countdown.update(at(secondsLeft = 12))

        countdown.cancel()

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
        assertThat(countdown.update(at(secondsLeft = 5))).isNull()
        assertThat(countdown.overlay).isEqualTo(Overlay.None)
        assertThat(countdown.update(at(secondsLeft = 0))).isNull()
    }

    @Test
    fun `seeking back out of the last 15 seconds hides the card, and back into them shows it`() {
        countdown.update(at(secondsLeft = 12))

        countdown.update(at(secondsLeft = 300))
        assertThat(countdown.overlay).isEqualTo(Overlay.None)

        countdown.update(at(secondsLeft = 8))
        assertThat(countdown.overlay).isEqualTo(Overlay.Card(secondsLeft = 8, counting = true))
    }

    @Test
    fun `with auto-play off the card shows but waits for OK`() {
        val manual = NextEpisodeCountdown(autoPlay = { false }).apply { newEpisode(hasNext = true) }

        manual.update(at(secondsLeft = 10))
        assertThat(manual.overlay).isEqualTo(Overlay.Card(secondsLeft = 10, counting = false))
        assertThat(manual.update(at(secondsLeft = 0).copy(ended = true))).isNull()

        assertThat(manual.ok()).isEqualTo(Decision.PlayNext)
    }

    /** Plays the current episode through its credits to the end, as nobody touching the remote would. */
    private fun NextEpisodeCountdown.runToEnd(): Decision? {
        (15 downTo 1).forEach { update(at(secondsLeft = it)) }
        return update(at(secondsLeft = 0)).also { if (it == Decision.PlayNext) newEpisode(hasNext = true) }
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

        countdown.update(at(secondsLeft = 10))
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
        countdown.update(at(secondsLeft = 0))

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

        countdown.update(at(secondsLeft = 10))

        assertThat(countdown.overlay).isEqualTo(Overlay.None)
    }

    private companion object {
        const val EPISODE_MILLIS = 2_400_000L
    }
}
