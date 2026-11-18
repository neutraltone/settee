/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.Series
import app.opentv.data.model.TimingsSource
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Skip intro and Skip recap on the episode player, as the remote reaches them (#518). */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(UnstableApi::class)
class VodPlayerSkipIntroTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private val playback = FakePlayback()

    @After
    fun release() {
        SleepTimer.clear()
        controller.release()
        players.close()
        graph.database.close()
    }

    /**
     * S1 E1 of a show whose episodes are timed with [recap] and an intro at 2:00 to 2:50, kept on the
     * device already so nothing is asked; playing with the controls gone.
     */
    private fun showTimedEpisode(recap: LongRange? = null, onBack: () -> Unit = {}) {
        runBlocking {
            graph.database.series().upsertAll(listOf(show))
            graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) })
            // Episode 2's too: the player looks ahead to the Next Episode's, which would otherwise go to
            // TheIntroDB for real.
            (1..2).forEach { number ->
                graph.database.episodeTimings().upsert(
                    EpisodeTimings(
                        show = "tmdb:1396",
                        season = 1,
                        episode = number,
                        introStartMillis = INTRO_START,
                        introEndMillis = INTRO_END,
                        recapStartMillis = recap?.first,
                        recapEndMillis = recap?.last,
                        creditsStartMillis = EPISODE_MILLIS - 120_000,
                        source = TimingsSource.THE_INTRO_DB,
                        fetchedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack) }
        players.play(controller)
        runBlocking { viewModel.attach(testEpisode(1, 1).key, playback) }
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    /** Waits for the timings read beside playback, then polls at [millis], as the player screen does. */
    private fun playUntilButtonAt(millis: Long) {
        runBlocking {
            viewModel.timingsArrived()
            playback.positionMillis = millis
            viewModel.poll()
        }
        assertThat(viewModel.skipButton.value).isNotNull()
        settle()
    }

    private fun pollAt(millis: Long) {
        playback.positionMillis = millis
        runBlocking { viewModel.poll() }
        settle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    @Test
    fun `in the intro Skip intro comes up with the Highlight, and OK lands at the intro's end`() {
        showTimedEpisode()

        playUntilButtonAt(INTRO_START + 1_000)

        compose.onNodeWithText("Skip intro").assertIsFocused()
        captureScreen("vodplayer_skip_intro")

        press(Key.DirectionCenter)

        assertThat(playback.positionMillis).isEqualTo(INTRO_END)
        compose.onNodeWithText("Skip intro").assertDoesNotExist()
    }

    @Test
    fun `once it has gone, bringing up the controls puts it back above the row, a press Up away`() {
        showTimedEpisode()
        playUntilButtonAt(INTRO_START)
        pollAt(INTRO_START + 10_000)
        compose.onNodeWithText("Skip intro").assertDoesNotExist()

        press(Key.DirectionDown)

        compose.onNodeWithText("Skip intro").assertExists()
        compose.onNode(seekBar).assertIsFocused()

        press(Key.DirectionUp)
        compose.onNodeWithText("Skip intro").assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(playback.positionMillis).isEqualTo(INTRO_END)
    }

    @Test
    fun `Back on the button puts it away and leaves the episode playing`() {
        var left = false
        showTimedEpisode(onBack = { left = true })
        playUntilButtonAt(INTRO_START + 1_000)

        press(Key.Back)

        compose.onNodeWithText("Skip intro").assertDoesNotExist()
        assertThat(left).isFalse()
        pollAt(INTRO_START + 2_000)
        compose.onNodeWithText("Skip intro").assertDoesNotExist()
    }

    @Test
    fun `a recap just before the intro gets one button that skips both`() {
        showTimedEpisode(recap = 0L..INTRO_START - 3_000)

        playUntilButtonAt(10_000)

        compose.onNodeWithText("Skip recap and intro").assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(playback.positionMillis).isEqualTo(INTRO_END)
    }

    @Test
    fun `a recap on its own gets Skip recap`() {
        showTimedEpisode(recap = 0L..30_000L)

        playUntilButtonAt(10_000)

        compose.onNodeWithText("Skip recap").assertIsFocused()
    }

    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})

    private val show = Series(
        sourceId = 1,
        seriesId = "s",
        name = "The Show",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "1396",
    )

    private companion object {
        const val INTRO_START = 120_000L
        const val INTRO_END = 170_000L
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val SETTLE_MILLIS = 600L
    }
}
