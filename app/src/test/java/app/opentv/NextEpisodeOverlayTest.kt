/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.height
import app.opentv.core.ServiceLocator
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.Series
import app.opentv.data.model.TimingsSource
import app.opentv.data.repo.MediaKey
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.ui.vod.NEXT_EPISODE_CARD_TAG
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

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val playback = FakePlayback()
    private var now = 0L
    private val vm by lazy { testGraph.viewModel { VodPlayerViewModel(app, graph, clock = { now }) } }
    private var stopped = false

    @After
    fun close() {
        graph.settings.setPlayNextAutomatically(true)
    }

    /** The overlay over a player that has started S1 E1 of a six-episode season. */
    private fun showPlayer() {
        runBlocking { vm.startSeason(graph, playback) }
        compose.setTvContent {
            // Stands in for the player's picture, which holds focus while nothing is drawn over it.
            Box(Modifier.fillMaxSize().countsAsInput(vm).focusable()) {
                NextEpisodeOverlay(vm, onStop = { stopped = true })
            }
        }
    }

    private fun secondsLeft(seconds: Int) {
        runBlocking { vm.pollWithSecondsLeft(playback, seconds) }
        compose.waitForIdle()
    }

    private fun press(key: Key) = compose.onRoot().performKeyInput { pressKey(key) }

    // The view model names the episode before it has looked up where to resume, and only then starts
    // it, so waiting for the name alone can read what was played before.
    private fun waitForEpisode(id: Long) {
        compose.waitForIdle()
        val current = vm.current.value
        assertThat(current).isEqualTo(MediaKey.Episode(id))
        assertThat(playback.played.lastOrNull()?.first).isEqualTo(current)
    }

    @Test
    fun `with no timings, 30 seconds from the end the Next Episode card comes up with focus and a countdown`() {
        showPlayer()

        secondsLeft(12)

        compose.onNodeWithText("Next episode in 12").assertExists()
        compose.onNodeWithText("Episode 2").assertExists()
        compose.onNodeWithText("Play now").assertIsFocused()
        captureScreen("vod_player_next_episode_card")
    }

    /**
     * The player over S1 E1 of a show whose episode TheIntroDB timed, credits at 38:00 of 40:00, with
     * [sceneAfter] a scene after them; kept on the device already, with the next episode's, so nothing
     * is asked.
     */
    private fun showTimedPlayer(sceneAfter: Boolean = false) {
        runBlocking {
            graph.database.series().upsertAll(
                listOf(
                    Series(
                        sourceId = 1,
                        seriesId = "s",
                        name = "The Show",
                        categoryId = null,
                        posterUrl = null,
                        rating = null,
                        year = null,
                        plot = null,
                        tmdbId = "1396",
                    ),
                ),
            )
            // Episode 2's too: the player looks ahead to the Next Episode's, which would otherwise go to
            // TheIntroDB for real.
            (1..2).forEach { number ->
                graph.database.episodeTimings().upsert(
                    EpisodeTimings(
                        show = "tmdb:1396",
                        season = 1,
                        episode = number,
                        creditsStartMillis = TIMED_CREDITS_MILLIS,
                        sceneAfterCredits = sceneAfter,
                        source = TimingsSource.THE_INTRO_DB,
                        fetchedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        showPlayer()
        // The timings are read beside playback: wait for them, then poll 3 s into the credits.
        runBlocking {
            vm.timingsArrived()
            playback.positionMillis = TIMED_CREDITS_MILLIS + 3_000
            vm.poll()
        }
        assertThat(vm.overlay.value).isNotEqualTo(Overlay.None)
        compose.waitForIdle()
    }

    @Test
    fun `on a timed episode the card comes up 3 seconds into the credits and counts down from 15`() {
        showTimedPlayer()

        compose.onNodeWithText("Next episode in 15").assertExists()
        compose.onNodeWithText("Play now").assertIsFocused()
        captureScreen("vodplayer_upnext_credits")
    }

    @Test
    fun `before a scene after the credits the card comes up without a countdown`() {
        showTimedPlayer(sceneAfter = true)

        compose.onNodeWithText("Next episode in 15").assertDoesNotExist()
        compose.onNodeWithText("Episode 2").assertExists()
        compose.onNodeWithText("Play now").assertIsFocused()
        captureScreen("vodplayer_upnext_after_credits_scene")
    }

    @Test
    fun `the card is the same height counting down as waiting for OK`() {
        showPlayer()
        secondsLeft(12)
        val counting = compose.onNodeWithTag(NEXT_EPISODE_CARD_TAG).getUnclippedBoundsInRoot().height

        graph.settings.setPlayNextAutomatically(false)
        secondsLeft(11)
        compose.onNodeWithText("Press OK to play").assertExists()
        val waiting = compose.onNodeWithTag(NEXT_EPISODE_CARD_TAG).getUnclippedBoundsInRoot().height

        assertThat(counting).isEqualTo(waiting)
    }

    @Test
    fun `the card rises into place once, not again as the countdown ticks`() {
        showPlayer()
        compose.mainClock.autoAdvance = false

        secondsLeft(12)
        compose.mainClock.advanceTimeByFrame()
        val firstFrame = compose.cardTop()
        compose.mainClock.advanceTimeBy(100)
        val midway = compose.cardTop()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = compose.cardTop()

        assertThat(firstFrame).isGreaterThan(midway)
        assertThat(midway).isGreaterThan(settled)

        secondsLeft(11)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Next episode in 11").assertExists()
        assertThat(compose.cardTop()).isEqualTo(settled)
    }

    /** The card 50 ms into its rise, with the clock held there. */
    private fun cardRising() {
        showPlayer()
        compose.mainClock.autoAdvance = false
        secondsLeft(12)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(50)
    }

    @Test
    fun `OK while the card is still rising plays the Next Episode`() {
        cardRising()

        compose.onNodeWithText("Play now").assertIsFocused()
        press(Key.DirectionCenter)
        // The press has landed mid-rise; the clock runs again only so the episode can start.
        compose.mainClock.autoAdvance = true

        waitForEpisode(12)
    }

    @Test
    fun `Watch credits while the card is still rising takes it away in one frame`() {
        cardRising()

        press(Key.DirectionRight)
        compose.onNodeWithText("Watch credits").assertIsFocused()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Episode 2").assertDoesNotExist()
    }

    @Test
    fun `OK on Play now plays the Next Episode`() {
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

        // Play now, then Watch credits on its right.
        press(Key.DirectionRight)
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
        compose.onNodeWithText("Episode 2").assertExists()
        compose.onNodeWithText("Play now").assertIsFocused()
        captureScreen("vod_player_next_episode_card_waiting")
    }

    @Test
    fun `with auto-play off, a card still up when the episode ends offers Back in place of Watch credits`() {
        graph.settings.setPlayNextAutomatically(false)
        showPlayer()
        secondsLeft(12)

        playback.positionMillis = EPISODE_MILLIS
        playback.ended = true
        runBlocking { vm.poll() }
        compose.waitForIdle()

        compose.onNodeWithText("Watch credits").assertDoesNotExist()
        press(Key.DirectionRight)
        compose.onNodeWithText("Back").assertIsFocused()
        captureScreen("vodplayer_upnext_at_end")
        press(Key.DirectionCenter)
        runBlocking { vm.poll() }

        assertThat(vm.finished.value).isTrue()
        assertThat(vm.current.value).isEqualTo(MediaKey.Episode(11))
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
        compose.onNodeWithText("Keep watching").assertIsFocused()
        captureScreen("vod_player_still_watching")
    }

    /** "Are you still watching?" one frame after it's asked, with the clock held there. */
    private fun stillWatchingArriving() {
        showPlayer()
        runUnattended(3)
        compose.mainClock.autoAdvance = false
        runUnattended(1)
        compose.mainClock.advanceTimeByFrame()
    }

    /** How bright the question's words are drawn: its brightest pixel's brightest channel. */
    private fun questionBrightness(): Int {
        val image = compose.onNodeWithText("Are you still watching?").captureToImage()
        val pixels = IntArray(image.width * image.height).also { image.readPixels(it) }
        return pixels.maxOf { maxOf(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
    }

    @Test
    fun `the question fades up rather than cutting in, and not again when the stream is let go`() {
        stillWatchingArriving()
        val firstFrame = questionBrightness()
        compose.mainClock.advanceTimeBy(50)
        val midway = questionBrightness()
        compose.mainClock.advanceTimeBy(1_000)
        val settled = questionBrightness()

        assertThat(firstFrame).isLessThan(midway)
        assertThat(midway).isLessThan(settled)

        // Five minutes on, the provider connection is let go, which changes the prompt in place.
        now += 5 * 60_000
        runBlocking { vm.poll() }
        compose.mainClock.advanceTimeByFrame()
        assertThat(playback.stopped).isTrue()
        assertThat(questionBrightness()).isEqualTo(settled)
    }

    @Test
    fun `OK while the question is still fading up plays the Next Episode`() {
        stillWatchingArriving()

        compose.onNodeWithText("Keep watching").assertIsFocused()
        press(Key.DirectionCenter)
        // The press has landed mid-fade; the clock runs again only so the episode can start.
        compose.mainClock.autoAdvance = true

        waitForEpisode(15)
        compose.onNodeWithText("Are you still watching?").assertDoesNotExist()
    }

    @Test
    fun `Stop while the question is still fading up leaves the player at once`() {
        stillWatchingArriving()

        press(Key.DirectionRight)
        compose.onNodeWithText("Stop").assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(stopped).isTrue()
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
    fun `Keep watching plays the Next Episode`() {
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

/** Starts S1 E1 of a six-episode season, each episode with a still, on [playback]. */
internal suspend fun VodPlayerViewModel.startSeason(graph: ServiceLocator.Graph, playback: FakePlayback) {
    graph.database.episodes().upsertAll(
        (1..6).map { testEpisode(1, it).copy(stillUrl = "https://example.com/still-$it.jpg") },
    )
    attach(testEpisode(1, 1).key, playback)
}

/**
 * Moves [playback] to [seconds] before the card's countdown runs out and polls, as the player screen
 * does. With no timings the card comes up 30 seconds before the episode's end and its countdown runs
 * out 15 seconds before it. Playback reaches the card first, as it would playing on: a jump straight
 * into the credits is a seek, which counts down afresh from where it lands.
 */
internal suspend fun VodPlayerViewModel.pollWithSecondsLeft(playback: FakePlayback, seconds: Int) {
    val cardAt = EPISODE_MILLIS - 30_000L
    if (playback.positionMillis < cardAt) {
        playback.positionMillis = cardAt
        poll()
    }
    playback.positionMillis = EPISODE_MILLIS - 15_000L - seconds * 1_000L
    poll()
}

/** Where the Next Episode card's episode line is drawn, its rise included. */
internal fun ComposeContentTestRule.cardTop() = onNodeWithText("Episode 2").getUnclippedBoundsInRoot().top

/** Where the timed episode's credits start: 38:00 of the 40-minute episode. */
private const val TIMED_CREDITS_MILLIS = EPISODE_MILLIS - 120_000L
