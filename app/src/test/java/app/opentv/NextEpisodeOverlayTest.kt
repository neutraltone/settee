/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.MediaKey
import app.opentv.ui.vod.NextEpisodeOverlay
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.countsAsInput
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which the player doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NextEpisodeOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val playback = FakePlayback()
    private val vm = VodPlayerViewModel(app, graph)
    private var stopped = false

    @After
    fun close() {
        graph.settings.setPlayNextAutomatically(true)
        graph.database.close()
    }

    /** The overlay over a player that has started S1 E1 of a six-episode season. */
    private fun showPlayer() {
        runBlocking {
            graph.database.episodes().upsertAll(
                (1..6).map { testEpisode(1, it).copy(stillUrl = "https://example.com/still-$it.jpg") },
            )
            vm.attach(testEpisode(1, 1).key, playback)
        }
        compose.setTvContent {
            // Stands in for the player's picture, which holds focus while nothing is drawn over it.
            Box(Modifier.fillMaxSize().countsAsInput(vm).focusable()) {
                NextEpisodeOverlay(vm, onStop = { stopped = true })
            }
        }
    }

    private fun secondsLeft(seconds: Int) {
        playback.positionMillis = EPISODE_MILLIS - seconds * 1_000L
        runBlocking { vm.poll() }
        compose.waitForIdle()
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    // The view model names the episode before it has looked up where to resume, and only then starts
    // it, so waiting for the name alone can read what was played before.
    private fun waitForEpisode(id: Long) = compose.waitUntil {
        val current = vm.current.value
        current == MediaKey.Episode(id) && playback.played.lastOrNull()?.first == current
    }

    @Test
    fun `15 seconds from the end the Next Episode card comes up with focus and a countdown`() {
        showPlayer()

        secondsLeft(12)

        compose.onNodeWithText("Next episode in 12").assertExists()
        compose.onNodeWithText("Episode 2").assertIsFocused()
        captureScreen("vod_player_next_episode_card")
    }

    @Test
    fun `OK on the card plays the Next Episode`() {
        showPlayer()
        secondsLeft(12)

        press(Key.DirectionCenter)

        waitForEpisode(12)
        assertThat(playback.played.last().first).isEqualTo(testEpisode(1, 2).key)
        compose.onNodeWithText("Next episode in 12").assertDoesNotExist()
    }

    @Test
    fun `Watch credits dismisses the card and the episode plays to its end`() {
        showPlayer()
        secondsLeft(12)

        // The card sits in the corner, so Watch credits is beside it on the left.
        press(Key.DirectionLeft)
        compose.onNodeWithText("Watch credits").assertIsFocused()
        captureScreen("vod_player_next_episode_card_watch_credits")
        press(Key.DirectionCenter)
        compose.waitForIdle()

        compose.onNodeWithText("Episode 2").assertDoesNotExist()
        secondsLeft(0)
        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(11))
    }

    @Test
    fun `when the countdown runs out the Next Episode plays`() {
        showPlayer()
        secondsLeft(1)

        secondsLeft(0)

        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(12))
        compose.onNodeWithText("Episode 2").assertDoesNotExist()
    }

    @Test
    fun `with auto-play off the card asks for OK instead of counting down`() {
        graph.settings.setPlayNextAutomatically(false)
        showPlayer()

        secondsLeft(12)

        compose.onNodeWithText("Press OK to play").assertExists()
        compose.onNodeWithText("Episode 2").assertIsFocused()
        captureScreen("vod_player_next_episode_card_waiting")
    }

    private fun runUnattended(episodes: Int) = repeat(episodes) {
        secondsLeft(10)
        secondsLeft(0)
    }

    @Test
    fun `after 3 unattended episodes the player asks whether anyone is still watching`() {
        showPlayer()

        runUnattended(4)

        assertThat(playback.paused).isTrue()
        compose.onNodeWithText("Are you still watching?").assertExists()
        compose.onNodeWithText("Continue watching").assertIsFocused()
        captureScreen("vod_player_still_watching")
    }

    @Test
    fun `a key press during playback starts the run again`() {
        showPlayer()
        runUnattended(2)

        press(Key.DirectionUp)
        runUnattended(2)

        compose.onNodeWithText("Are you still watching?").assertDoesNotExist()
        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(15))
    }

    @Test
    fun `Continue watching plays the Next Episode`() {
        showPlayer()
        runUnattended(4)

        press(Key.DirectionCenter)

        waitForEpisode(15)
        compose.onNodeWithText("Are you still watching?").assertDoesNotExist()
    }

    @Test
    fun `Stop leaves the player with the Next Episode not started`() {
        showPlayer()
        runUnattended(4)

        press(Key.DirectionRight)
        compose.onNodeWithText("Stop").assertIsFocused()
        press(Key.DirectionCenter)
        compose.waitForIdle()

        assertThat(stopped).isTrue()
        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(14))
    }
}
