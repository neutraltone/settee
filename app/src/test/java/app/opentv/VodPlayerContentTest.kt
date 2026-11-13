/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.core.SleepTimer
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.WatchStatus
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.vodPlayback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()

    // Never attached to an episode, so no Next Episode card: these tests are about the transport.
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})

    @After
    fun release() {
        controller.release()
        players.close()
        graph.database.close()
    }

    private fun showFilm(growingRec: Boolean = false) {
        compose.setTvContent {
            VodPlayerContent(controller, viewModel, growingRec, noPicks, onBack = {})
        }
        players.play(controller)
        compose.waitForIdle()
    }

    private fun hideControls() {
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `play-pause pauses a playing film with the controls hidden, and shows them`() {
        showFilm()
        hideControls()

        press(Key.MediaPlayPause)

        assertThat(controller.player.playWhenReady).isFalse()
        compose.onNodeWithContentDescription("Play").assertExists()
        captureScreen("vod_player_paused")
    }

    @Test
    fun `pause only pauses and play only resumes, with the controls showing`() {
        showFilm()

        press(Key.MediaPause)
        press(Key.MediaPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlay)
        press(Key.MediaPlay)
        assertThat(controller.player.playWhenReady).isTrue()
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun `rewind and fast-forward skip by 15 seconds a press, with the controls hidden`() {
        showFilm()
        controller.player.seekTo(60_000)
        players.untilReady(controller.player)
        compose.waitForIdle()
        hideControls()

        press(Key.MediaRewind)
        press(Key.MediaRewind)
        assertThat(controller.player.currentPosition).isEqualTo(30_000)

        press(Key.MediaFastForward)
        assertThat(controller.player.currentPosition).isEqualTo(45_000)
    }

    @Test
    fun `fast-forward on a recording still being written stops at what's recorded`() {
        val recording = players.controller(RECORDING)
        compose.setTvContent {
            VodPlayerContent(recording, viewModel, growingRec = true, noPicks, onBack = {})
        }
        players.play(recording)
        val recorded = recording.player.bufferedPosition
        recording.player.seekTo(recorded - 20_000)
        players.untilReady(recording.player)

        press(Key.MediaFastForward)
        assertThat(recording.player.currentPosition).isEqualTo(recorded - 5_000)
        // ExoPlayer parks a seek to the very end of a window a millisecond short of it.
        press(Key.MediaFastForward)
        assertThat(
            recording.player.currentPosition,
        ).isIn(com.google.common.collect.Range.closed(recorded - 1, recorded))

        recording.release()
    }

    @Test
    fun `the bar follows the player when something else resumes it`() {
        showFilm()
        press(Key.MediaPlayPause)

        controller.player.playWhenReady = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause").assertExists()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
    }

    /** A film [TEN_MINUTES] in, with the controls up and focus on play/pause. */
    private fun showFilmPartWay() {
        showFilm()
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
    }

    /** Lets the controls take focus and the bar read the player again. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    private fun barPosition() = compose
        .onNode(seekBar)
        .fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo]
        .current
        .toLong()

    @Test
    fun `the controls open with the Highlight on the seek bar`() {
        showFilmPartWay()

        assertThat(compose.seekBarIsFocused()).isTrue()
        captureScreen("vod_player_controls")
    }

    @Test
    fun `the bar shows where the film is and leaves it playing from there`() {
        showFilmPartWay()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        captureScreen("vod_player_seek_bar")
    }

    @Test
    fun `a film whose Versions differ has a Quality pill, listing them`() {
        runBlocking {
            graph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            graph.database.movies().upsertAll(
                listOf(film(1, "EN - Superbad (2007)"), film(2, "DE - Superbad HD (2007)")),
            )
            viewModel.attach(MediaKey.Movie(1), FakePlayback())
        }
        showFilmPartWay()

        press(Key.DirectionDown)
        settle()
        compose.onNodeWithText("Quality").performClick()
        settle()

        compose.onNodeWithText("HD · German").assertIsDisplayed()
        compose.onNodeWithText("Other").assertIsDisplayed()
        captureScreen("vod_player_quality_pill")
    }

    private fun film(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = "8363",
    )

    @Test
    fun `Down from the bar goes to Subtitles without seeking and brings up the pills, and Left comes back`() {
        showFilmPartWay()
        compose.onNodeWithText("Sleep timer").assertDoesNotExist()

        press(Key.DirectionDown)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
        compose.onNodeWithText("Sleep timer").assertIsDisplayed()
        captureScreen("vod_player_pills")

        press(Key.DirectionLeft)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(compose.seekBarIsFocused()).isTrue()
    }

    @Test
    fun `Right from Subtitles reaches Audio, Down from there reaches the pills, and Up from the row reaches close`() {
        showFilmPartWay()
        press(Key.DirectionDown)

        press(Key.DirectionRight)
        compose.onNodeWithContentDescription("Audio").assertIsFocused()

        press(Key.DirectionDown)
        compose.onNodeWithText("Sleep timer").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("Options").assertIsFocused()

        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Subtitles").assertIsFocused()
        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Close").assertIsFocused()
        compose.onNodeWithText("Sleep timer").assertDoesNotExist()

        press(Key.DirectionDown)
        assertThat(compose.seekBarIsFocused()).isTrue()
        press(Key.DirectionUp)
        compose.onNodeWithContentDescription("Close").assertIsFocused()
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `Subtitles opens a panel down the right, with the Highlight on the ticked choice`() {
        showFilmPartWay()
        press(Key.DirectionDown)

        press(Key.DirectionCenter)
        settle()

        val off = compose.onNode(hasText("Off") and hasContentDescription("Selected"))
        off.assertIsFocused()
        val screenWidth = compose.onRoot().fetchSemanticsNode().size.width
        assertThat(off.fetchSemanticsNode().boundsInRoot.left).isGreaterThan(screenWidth / 2f)
        captureScreen("vod_player_subtitles")

        Espresso.pressBack()
        settle()
        compose.onNodeWithText("Off").assertDoesNotExist()
        assertThat(compose.seekBarIsFocused()).isTrue()
    }

    @Test
    fun `close leaves the player`() {
        var left = false
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = { left = true }) }
        players.play(controller)
        settle()

        press(Key.DirectionUp)
        press(Key.DirectionCenter)

        assertThat(left).isTrue()
    }

    /** On the bar, with right held down and not yet let go: a seek one [STEP] on from [TEN_MINUTES]. */
    private fun startSeekingOnTheBar() {
        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        settle()
    }

    private fun letGoOfRight() {
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
        compose.waitForIdle()
    }

    @Test
    fun `leaving the bar mid-seek and returning in time carries on from where the seek had got to`() {
        showFilmPartWay()
        startSeekingOnTheBar()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)
        captureScreen("vod_player_seek_bar_seeking")

        press(Key.DirectionDown)
        letGoOfRight()
        compose.mainClock.advanceTimeBy(WITHIN_GRACE_MILLIS)
        press(Key.DirectionLeft)
        compose.waitForIdle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)

        press(Key.DirectionRight)

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + 2 * STEP)
    }

    @Test
    fun `not returning in time drops the seek, and playback stays where it was`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.DirectionDown)
        letGoOfRight()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `a finished seek moves playback, and the bar goes on following it`() {
        showFilmPartWay()

        press(Key.DirectionRight)
        players.untilReady(controller.player)
        settle()
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + STEP)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)

        controller.player.seekTo(TEN_MINUTES * 2)
        players.untilReady(controller.player)
        settle()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES * 2)
    }

    @Test
    fun `a seek whose key-up never comes is dropped, on the bar, and the controls still hide`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `a finger resting on the bar keeps the controls up, and lifting it finishes the seek`() {
        showFilmPartWay()
        dragTheBar()
        val target = barPosition()
        assertThat(target).isNotEqualTo(TEN_MINUTES)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS + PAST_GRACE_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertExists()
        assertThat(barPosition()).isEqualTo(target)

        compose.onNode(seekBar).performTouchInput { up() }
        compose.waitForIdle()

        assertThat(controller.player.currentPosition).isEqualTo(target)
    }

    private fun dragTheBar() {
        compose.onNode(seekBar).performTouchInput {
            down(center)
            moveBy(Offset(100f, 0f))
        }
        compose.waitForIdle()
    }

    @Test
    fun `a seek left unfinished on the bar of a paused film doesn't stay there`() {
        showFilmPartWay()
        press(Key.MediaPause)
        startSeekingOnTheBar()

        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play").assertExists()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES)
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `fast-forward during a seek wins, and letting go of the key doesn't undo it`() {
        showFilmPartWay()
        startSeekingOnTheBar()

        press(Key.MediaFastForward)
        letGoOfRight()
        players.untilReady(controller.player)
        settle()

        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES + 15_000)
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + 15_000)
    }

    // What the screen hands the view model, so moving on to something else reaches the same player.
    private val playback = playbackOn(controller)

    private fun playbackOn(player: PlayerController) = object : VodPlayerViewModel.Playback {
        override fun progress() = NextEpisodeCountdown.Playback(
            player.player.currentPosition,
            player.player.duration.coerceAtLeast(0),
        )

        override fun play(request: PlayerController.Request, skipped: String?) = player.play(request, debounce = false)

        override fun pause() = Unit

        override fun stop() = Unit
    }

    @Test
    fun `the Next Episode card coming up counts as leaving the bar`() {
        val nearTheEnd = 3_588_000L
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(nearTheEnd)
        players.untilReady(controller.player)
        settle()
        startSeekingOnTheBar()

        runBlocking { viewModel.poll() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        viewModel.cancel()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(nearTheEnd)
        assertThat(controller.player.currentPosition).isEqualTo(nearTheEnd)
    }

    @Test
    fun `a finger on the bar when the Next Episode card comes up doesn't leave the bar stuck`() {
        val nearTheEnd = 3_588_000L
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(nearTheEnd)
        players.untilReady(controller.player)
        settle()
        dragTheBar()

        runBlocking { viewModel.poll() }
        compose.waitForIdle()
        viewModel.cancel()
        compose.mainClock.advanceTimeBy(PAST_GRACE_MILLIS)
        press(Key.DirectionCenter)
        settle()

        assertThat(barPosition()).isEqualTo(nearTheEnd)
        assertThat(controller.player.currentPosition).isEqualTo(nearTheEnd)
    }

    @Test
    fun `while the next one loads the bar shows where it will start, not the last one's position`() {
        val loaded = CompletableDeferred<Unit>()
        val slow = players.controller { key ->
            if (key == MediaKey.Movie(2)) loaded.await()
            Playable.Ready(key, "https://example.com/stream", userAgent = "", title = "Film", isLive = false)
        }
        val onSlow = playbackOn(slow)
        compose.setTvContent { VodPlayerContent(slow, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(MediaKey.Movie(1), onSlow) }
        players.untilReady(slow.player)
        slow.player.seekTo(TEN_MINUTES)
        players.untilReady(slow.player)
        settle()

        runBlocking { viewModel.attach(MediaKey.Movie(2), onSlow) }
        settle()

        assertThat(slow.player.currentPosition).isEqualTo(TEN_MINUTES)
        assertThat(barPosition()).isEqualTo(0)

        loaded.complete(Unit)
        players.until { slow.player.currentPosition < TEN_MINUTES }
        slow.release()
    }

    @Test
    fun `a new episode drops the seek at once, with no grace`() {
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = {}) }
        runBlocking { viewModel.attach(MediaKey.Movie(1), playback) }
        players.untilReady(controller.player)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        settle()
        startSeekingOnTheBar()
        assertThat(barPosition()).isEqualTo(TEN_MINUTES + STEP)

        runBlocking { viewModel.attach(MediaKey.Movie(2), playback) }
        players.untilReady(controller.player)
        settle()

        assertThat(barPosition()).isEqualTo(controller.player.currentPosition)
        assertThat(barPosition()).isLessThan(TEN_MINUTES)
    }

    @Test
    fun `while the Next Episode's stream is being found, saving doesn't give it the old one's position`() {
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        val loaded = CompletableDeferred<Unit>()
        val slow = players.controller { key ->
            if (key == testEpisode(1, 2).key) loaded.await()
            Playable.Ready(key, "https://example.com/stream", userAgent = "", title = "Film", isLive = false)
        }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, slow.vodPlayback()) }
        players.untilReady(slow.player)
        slow.player.seekTo(TEN_MINUTES)
        players.untilReady(slow.player)

        runBlocking {
            viewModel.markWatched()
            viewModel.saveProgress()
        }

        assertThat(runBlocking { graph.viewerLibrary.marks.statusOf("ep:12") }).isEqualTo(WatchStatus.Unwatched)
        loaded.complete(Unit)
        slow.release()
    }

    private fun inList(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun closeList() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    /** The player on [key], with a film the view model only knows as far as [fake] tells it. */
    private fun showWatching(key: MediaKey, fake: VodPlayerViewModel.Playback = playback, onBack: () -> Unit = {}) {
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack) }
        players.play(controller)
        runBlocking { viewModel.attach(key, fake) }
        compose.waitForIdle()
    }

    @Test
    fun `Menu opens the options list over the film, with Mark watched for a movie`() {
        showWatching(MediaKey.Movie(1))

        press(Key.Menu)

        inList("Subtitles").assertIsFocused()
        inList("Audio").assertExists()
        inList("Sleep timer").assertExists()
        inList("Mark watched").assertExists()
        captureScreen("vod_player_options")
    }

    @Test
    fun `a recording's list has no Mark watched`() {
        showWatching(MediaKey.Recording(1))

        press(Key.Menu)

        inList("Sleep timer").assertExists()
        inList("Mark watched").assertDoesNotExist()
    }

    @Test
    fun `Back returns the highlight to the seek bar, which hasn't timed out meanwhile`() {
        showFilm()
        settle()
        assertThat(compose.seekBarIsFocused()).isTrue()
        press(Key.Menu)

        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        inList("Sleep timer").assertExists()
        closeList()

        assertThat(compose.seekBarIsFocused()).isTrue()
    }

    @Test
    fun `the Options pill opens the same list`() {
        showFilm()
        settle()
        press(Key.DirectionDown)

        compose.onNodeWithText("Options").performClick()

        inList("Sleep timer").assertExists()
    }

    @Test
    fun `Subtitles opens the bar's picker with the bar hidden, and it stays up`() {
        showFilm()
        hideControls()
        press(Key.Menu)

        inList("Subtitles").performClick()
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)

        compose.onNodeWithText("Off").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
    }

    @Test
    fun `Menu doesn't open the list over a picker`() {
        showFilm()
        compose.onNodeWithContentDescription("Audio").performClick()

        press(Key.Menu)

        inList("Sleep timer").assertDoesNotExist()
    }

    @Test
    fun `the sleep timer offers Settings' durations, and shows the time left once set`() {
        showFilm()
        try {
            press(Key.Menu)
            inList("Sleep timer").performClick()
            inList("Off").assertExists()
            SleepTimer.presets.forEach { inList("$it minutes").assertExists() }
            captureScreen("player_options_sleep_timer")

            inList("30 minutes").performClick()
            assertThat(SleepTimer.minutesRemaining()).isEqualTo(30)

            press(Key.Menu)
            inList("Sleep timer · 30 min left").performClick()
            inList("Off").performClick()
            assertThat(SleepTimer.deadline.value).isNull()
        } finally {
            SleepTimer.clear()
        }
    }

    @Test
    fun `Mark watched on a movie marks it and leaves the player`() {
        var left = false
        showWatching(MediaKey.Movie(1), onBack = { left = true })
        press(Key.Menu)

        inList("Mark watched").performClick()
        compose.waitUntil(WAIT_MILLIS) { left }

        assertThat(runBlocking { graph.viewerLibrary.marks.statusOf("movie:1") }).isEqualTo(WatchStatus.Watched)
    }

    @Test
    fun `Mark watched on an episode moves on and stays in the player`() {
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        var left = false
        showWatching(testEpisode(1, 1).key, onBack = { left = true })
        press(Key.Menu)

        inList("Mark watched").performClick()
        compose.waitUntil(WAIT_MILLIS) { viewModel.current.value == testEpisode(1, 2).key }

        assertThat(left).isFalse()
    }

    @Test
    fun `Menu doesn't open the list over the Next Episode card, and the card coming up closes it`() {
        runBlocking { graph.database.episodes().upsertAll((1..2).map { testEpisode(1, it) }) }
        val fake = FakePlayback()
        showWatching(testEpisode(1, 1).key, fake)
        press(Key.Menu)
        inList("Sleep timer").assertExists()

        fake.positionMillis = EPISODE_MILLIS - 10_000
        runBlocking { viewModel.poll() }
        compose.waitForIdle()
        inList("Sleep timer").assertDoesNotExist()

        press(Key.Menu)
        inList("Sleep timer").assertDoesNotExist()
    }

    @Test
    fun `Menu doesn't open the list over Are you still watching`() {
        runBlocking { graph.database.episodes().upsertAll((1..6).map { testEpisode(1, it) }) }
        val fake = FakePlayback()
        showWatching(testEpisode(1, 1).key, fake)
        runBlocking { repeat(4) { viewModel.playToEnd(fake) } }
        compose.waitForIdle()
        assertThat(viewModel.overlay.value).isInstanceOf(NextEpisodeCountdown.Overlay.StillWatching::class.java)

        press(Key.Menu)

        inList("Sleep timer").assertDoesNotExist()
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val TEN_MINUTES = 600_000L
        const val SETTLE_MILLIS = 600L
        const val STEP = 10_000L
        const val WITHIN_GRACE_MILLIS = 2_800L
        const val PAST_GRACE_MILLIS = 3_200L
    }
}
