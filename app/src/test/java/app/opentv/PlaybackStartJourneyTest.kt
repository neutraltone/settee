/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.JourneyTimer
import app.opentv.core.ReportedSource
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The `playback-start` journey (#634): asked to play to the first frame, timed by the players. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackStartJourneyTest {

    private val players = TestPlayers(ApplicationProvider.getApplicationContext<Context>())
    private val slow = mutableListOf<JourneyTimer.SlowJourney>()

    @After
    fun close() = players.close()

    private fun channelTakingToStart(millis: Long): PlayerController {
        players.journeys = JourneyTimer(slow = { slow += it })
        val controller = players.controller(resolve = { key ->
            delay(millis)
            Playable.Ready(key, "https://example.com/1", "", "Channel", isLive = true, source = ReportedSource.STALKER)
        })
        controller.play(PlayerController.Request(MediaKey.Live(1)), debounce = false)
        players.until { controller.state.value is PlayerController.State.Playing }
        players.advance(1_000)
        return controller
    }

    @Test
    fun `a channel slow to show its first picture is a slow start of playback, for its Source's kind`() {
        channelTakingToStart(6_000)

        val journey = slow.single()
        assertThat(journey.journey).isEqualTo(JourneyTimer.Journey.PLAYBACK_START)
        assertThat(journey.ownMillis).isAtLeast(6_000L)
        assertThat(journey.source).isEqualTo(ReportedSource.STALKER)
    }

    @Test
    fun `a channel that starts in good time isn't`() {
        channelTakingToStart(500)

        assertThat(slow).isEmpty()
    }
}
