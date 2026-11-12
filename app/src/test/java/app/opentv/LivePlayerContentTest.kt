/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.AppSettings
import app.opentv.core.PipState
import app.opentv.data.model.Channel
import app.opentv.data.repo.MediaKey
import app.opentv.player.PlaybackQueue
import app.opentv.player.PlayerController
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.player.BannerChannel
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
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
class LivePlayerContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val players = TestPlayers(context)
    private val controller = players.controller(LIVE, dvr = true)

    private var turnedOn = 0
    private val played = mutableListOf<Long>()
    private val zapped = mutableListOf<Int>()
    private var dontAskAgain = 0
    private var backs = 0
    private val passedThrough = mutableListOf<Key>()

    // What PlayerScreen reads from AppSettings; a test flips them the way the settings would change.
    private var livePause by mutableStateOf(true)
    private var promptDismissed by mutableStateOf(false)
    private var current by mutableStateOf(controller)
    private var programme by mutableStateOf<String?>(null)
    private var playing by mutableStateOf(1L)
    private var variants by mutableStateOf(emptyList<Channel>())
    private var programmeLookups = 0

    // PlayerScreen's Channel, when a test wants it to differ from the one queue and playing give.
    private var playingChannel by mutableStateOf<BannerChannel?>(null)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun showChannel(
        player: PlayerController = controller,
        livePause: Boolean = true,
        queue: List<PlaybackQueue.Item> = emptyList(),
        currentId: Long = 1L,
        start: (PlayerController) -> Unit = { players.play(it, isLive = true) },
    ) {
        this.livePause = livePause
        current = player
        playing = currentId
        compose.setTvContent {
            Box(
                Modifier.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) passedThrough += event.key
                    false
                },
            ) {
                LivePlayerContent(
                    controller = current,
                    settings = AppSettings.get(context),
                    queue = queue,
                    variants = variants,
                    currentId = playing,
                    previousId = null,
                    bannerChannel = playingChannel ?: queue.firstOrNull { playing in it.qualityIds }?.let {
                        BannerChannel("#${it.id}", it.number, it.name)
                    },
                    programmeOnNow = {
                        programmeLookups++
                        programme
                    },
                    recordingThis = false,
                    livePause = this@LivePlayerContentTest.livePause,
                    pausePromptDismissed = promptDismissed,
                    onPlayChannel = { played += it },
                    onZap = { delta, atEnd ->
                        zapped += delta
                        val from = queue.indexOfFirst { playing in it.qualityIds }
                        if (queue.getOrNull(from + delta) == null) atEnd()
                    },
                    onTune = {},
                    onToggleRecord = {},
                    favourite = false,
                    onToggleFavourite = {},
                    onTurnOnLivePause = { turnedOn++ },
                    onDontAskAgain = {
                        dontAskAgain++
                        promptDismissed = true
                    },
                    subtitlePicks = SubtitlePicks(onOff = {}, onPicked = {}),
                    onBack = { backs++ },
                )
            }
        }
        start(player)
        compose.waitForIdle()
    }

    private fun hideControls() {
        compose.mainClock.advanceTimeBy(CONTROLS_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    private fun row(id: Long, quality: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = "Two $quality",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$id.ts",
        qualityLabel = quality,
    )

    private fun hideBanner() = compose.mainClock.advanceTimeBy(BANNER_TIMEOUT_MILLIS)

    /** What PlayerScreen does once a zap or a pick has found its Channel. */
    private fun tuneTo(id: Long) {
        playing = id
        compose.waitForIdle()
    }

    private fun tuneWithoutWaiting(player: PlayerController) =
        player.play(PlayerController.Request(MediaKey.Live(1)), debounce = false)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `with live pause on, play-pause pauses and resumes the channel`() {
        showChannel()
        hideControls()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `with live pause on, rewind and fast-forward skip within the buffer`() {
        showChannel()
        controller.player.seekTo(60_000)
        players.untilReady(controller.player)
        hideControls()

        press(Key.MediaRewind)
        assertThat(controller.player.currentPosition).isEqualTo(45_000)

        press(Key.MediaFastForward)
        press(Key.MediaFastForward)
        assertThat(controller.player.currentPosition).isEqualTo(75_000)
    }

    @Test
    fun `with live pause on, pausing keeps the controls up counting down what's left of the buffer`() {
        showChannel()
        hideControls()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Paused · 2:00 left").assertExists()
        captureScreen("live_player_paused_countdown")

        compose.mainClock.advanceTimeBy(36_000)
        compose.onNodeWithText("Paused · 1:24 left").assertExists()
    }

    @Test
    fun `with live pause on, a pause that outlasts the buffer goes back to live`() {
        showChannel()
        controller.player.seekTo(30_000)
        players.untilReady(controller.player)

        press(Key.MediaPlayPause)
        compose.mainClock.advanceTimeBy(120_000)

        assertThat(controller.player.playWhenReady).isTrue()
        // The live edge: where the fake channel starts playing.
        assertThat(controller.player.currentPosition).isEqualTo(110_000)
        compose.onNodeWithText("Paused too long — back to live").assertExists()
    }

    @Test
    fun `with live pause off, play-pause offers to turn it on and the channel keeps playing`() {
        showChannel(livePause = false)
        hideControls()

        press(Key.MediaPlayPause)

        compose.onNodeWithText("Pause live TV?").assertExists()
        assertThat(controller.player.playWhenReady).isTrue()
        captureScreen("live_player_pause_prompt")
    }

    @Test
    fun `with live pause off, the bar's Pause button offers to turn it on too`() {
        showChannel(livePause = false)
        press(Key.DirectionCenter)

        compose.onNodeWithContentDescription("Pause").performClick()

        compose.onNodeWithText("Pause live TV?").assertExists()
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `not now and back close the prompt with nothing changed, and the next press asks again`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)

        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Pause live TV?").assertExists()
        Espresso.pressBack()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()

        assertThat(controller.player.playWhenReady).isTrue()
        assertThat(turnedOn).isEqualTo(0)
        assertThat(dontAskAgain).isEqualTo(0)
    }

    @Test
    fun `don't ask again stops pause offering, until live pause is turned on`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)

        compose.onNodeWithText("Don't ask again").performClick()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()
        assertThat(dontAskAgain).isEqualTo(1)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()

        press(Key.MediaPlayPause)
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()
        assertThat(controller.player.playWhenReady).isTrue()

        livePause = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Pause").assertExists()
        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `turn on and pause switches live pause on and pauses the channel once it's back`() {
        showChannel(livePause = false)
        press(Key.MediaPlayPause)
        compose.onNodeWithText("Turn on and pause").assertIsFocused()

        compose.onNodeWithText("Turn on and pause").performClick()
        assertThat(turnedOn).isEqualTo(1)

        // What PlayerScreen does when the setting turns on: a new player with the DVR buffer, on the
        // same channel.
        val rebuilt = players.controller(LIVE, dvr = true)
        livePause = true
        current = rebuilt
        compose.waitForIdle()
        players.play(rebuilt, isLive = true)
        compose.waitForIdle()

        assertThat(rebuilt.player.playWhenReady).isFalse()
        compose.onNodeWithText("Paused · 2:00 left").assertExists()
        rebuilt.release()
    }

    @Test
    fun `with live pause on, pause only pauses and play only resumes`() {
        showChannel()

        press(Key.MediaPause)
        press(Key.MediaPause)
        assertThat(controller.player.playWhenReady).isFalse()

        press(Key.MediaPlay)
        press(Key.MediaPlay)
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun `holding play-pause pauses once rather than flickering`() {
        showChannel()

        compose.onRoot().performKeyInput {
            keyDown(Key.MediaPlayPause)
            advanceEventTime(HOLD_MILLIS)
            keyUp(Key.MediaPlayPause)
        }
        compose.waitForIdle()

        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `the bar follows the player when something else resumes it`() {
        showChannel()
        press(Key.MediaPlayPause)

        // What the controller's automatic restart after a dropped connection does.
        controller.player.playWhenReady = true
        compose.waitForIdle()
        compose.onNodeWithText("Paused", substring = true).assertDoesNotExist()

        press(Key.MediaPlayPause)
        assertThat(controller.player.playWhenReady).isFalse()
    }

    @Test
    fun `typing a channel number tunes to it once the digits stop`() {
        showChannel(queue = CHANNELS)

        press(Key.One)
        press(Key.Two)
        compose.mainClock.advanceTimeBy(NUMBER_ENTRY_MILLIS)

        assertThat(played).containsExactly(12L)
    }

    @Test
    fun `channel up and down, and d-pad up and down, step through the channel list`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        hideControls()

        press(Key.ChannelDown)
        press(Key.DirectionDown)
        press(Key.ChannelUp)
        press(Key.DirectionUp)

        assertThat(zapped).containsExactly(1, 1, -1, -1).inOrder()
    }

    @Test
    fun `opening the player shows the banner with number, name and what's on, not the controls`() {
        programme = "News"

        showChannel(queue = CHANNELS, currentId = 11L)

        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("Two").assertExists()
        compose.onNodeWithText("News").assertExists()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        captureScreen("live_player_banner")
    }

    @Test
    fun `OK opens the controls, with the Highlight on the seek bar`() {
        showChannel()

        for (ok in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)) {
            press(ok)
            compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

            assertThat(compose.seekBarIsFocused()).isTrue()
            hideControls()
        }
    }

    @Test
    fun `holding OK opens the controls without pressing the button it lands on`() {
        showChannel(livePause = false)

        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onRoot().performKeyInput {
            advanceEventTime(HOLD_MILLIS)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()

        assertThat(compose.seekBarIsFocused()).isTrue()
        compose.onNodeWithText("Pause live TV?").assertDoesNotExist()
    }

    @Test
    fun `OK pressed again once the controls are up presses the button`() {
        showChannel(livePause = false)
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        press(Key.DirectionCenter)

        compose.onNodeWithText("Pause live TV?").assertExists()
    }

    @Test
    fun `another key with nothing showing passes through and doesn't open the controls`() {
        showChannel()

        press(Key.Info)

        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        assertThat(passedThrough).containsExactly(Key.Info)
    }

    @Test
    fun `changing channel with nothing showing shows the banner of the new channel, not the controls`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()

        for ((key, id, name) in listOf(
            Triple(Key.DirectionDown, 11L, "Two"),
            Triple(Key.ChannelDown, 12L, "Twelve"),
            Triple(Key.DirectionUp, 11L, "Two"),
            Triple(Key.ChannelUp, 10L, "One"),
        )) {
            press(key)
            tuneTo(id)

            compose.onNodeWithText(name).assertExists()
            compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        }
    }

    @Test
    fun `changing channel doesn't show the old channel's banner while the new one is found`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()

        press(Key.DirectionDown)

        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun `typing another channel's number doesn't show the old channel's banner while it tunes`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()

        press(Key.One)
        press(Key.Two)
        compose.mainClock.advanceTimeBy(NUMBER_ENTRY_MILLIS)

        assertThat(played).containsExactly(12L)
        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun `a channel that isn't in the list still gets a banner`() {
        playingChannel = BannerChannel("hidden", number = null, name = "Hidden")

        showChannel(queue = CHANNELS, currentId = 99L)

        compose.onNodeWithText("Hidden").assertExists()
    }

    @Test
    fun `the channel's details changing doesn't show the banner`() {
        playingChannel = BannerChannel("sport", number = null, name = "Sport")
        showChannel(queue = CHANNELS, currentId = 99L)
        hideBanner()

        // What a Catalogue Refresh can do while it's on: same Channel, another primary Quality.
        playingChannel = BannerChannel("sport", number = 7, name = "Sport HD")
        compose.waitForIdle()

        compose.onNodeWithText("Sport HD").assertDoesNotExist()
    }

    @Test
    fun `the banner looks up what's on when it appears, and not while it's hidden`() {
        programme = "News"
        showChannel(queue = CHANNELS, currentId = 12L)
        compose.onNodeWithText("News").assertExists()
        assertThat(programmeLookups).isEqualTo(1)
        hideBanner()

        programme = "Weather"
        compose.mainClock.advanceTimeBy(LONG_WHILE_MILLIS)
        assertThat(programmeLookups).isEqualTo(1)

        press(Key.DirectionDown)

        compose.onNodeWithText("Weather").assertExists()
        assertThat(programmeLookups).isEqualTo(2)
    }

    @Test
    fun `at the end of the list the channel stays and the banner shows it again`() {
        showChannel(queue = CHANNELS, currentId = 12L)
        hideBanner()

        press(Key.DirectionDown)

        compose.onNodeWithText("Twelve").assertExists()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `changing channel with the controls up keeps them up with no banner, and restarts their timer`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(PART_OF_A_TIMEOUT_MILLIS)

        // The channel key: with the controls up, the d-pad's Down moves to Subtitles instead.
        press(Key.ChannelDown)
        tuneTo(11L)
        compose.mainClock.advanceTimeBy(PART_OF_A_TIMEOUT_MILLIS)

        assertThat(zapped).containsExactly(1)
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithText("Two").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(PART_OF_A_TIMEOUT_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `at the end of the list with the controls up, they stay up`() {
        showChannel(queue = CHANNELS, currentId = 12L)
        hideBanner()
        press(Key.DirectionCenter)

        press(Key.DirectionDown)

        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithText("Twelve").assertDoesNotExist()
    }

    @Test
    fun `the banner hides by itself`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        compose.onNodeWithText("Two").assertExists()

        compose.mainClock.advanceTimeBy(BANNER_TIMEOUT_MILLIS)

        compose.onNodeWithText("Two").assertDoesNotExist()
    }

    @Test
    fun `zapping again restarts the banner's timer`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        compose.mainClock.advanceTimeBy(PART_OF_A_TIMEOUT_MILLIS)

        press(Key.DirectionDown)
        tuneTo(11L)
        compose.mainClock.advanceTimeBy(PART_OF_A_TIMEOUT_MILLIS)

        compose.onNodeWithText("Two").assertExists()
    }

    @Test
    fun `the banner hides by itself while the channel is buffering`() {
        val stuck = players.controller(LIVE, resolve = { awaitCancellation() })
        showChannel(stuck, queue = CHANNELS, currentId = 11L, start = { tuneWithoutWaiting(it) })
        assertThat(stuck.state.value).isInstanceOf(PlayerController.State.Buffering::class.java)
        compose.onAllNodesWithText("Two").assertCountEquals(1)

        compose.mainClock.advanceTimeBy(BANNER_TIMEOUT_MILLIS)

        compose.onAllNodesWithText("Two").assertCountEquals(0)
        stuck.release()
    }

    @Test
    fun `the banner hides by itself over an error`() {
        val failing = players.controller(LIVE, failing = 100)
        showChannel(failing, queue = CHANNELS, currentId = 11L, start = {
            tuneWithoutWaiting(it)
            players.until { (it.state.value as? PlayerController.State.Error)?.canRetry == false }
        })
        // The rule's clock hasn't moved while the controller gave up, so the banner's still in time.
        compose.onNodeWithText("Two").assertExists()

        compose.mainClock.advanceTimeBy(BANNER_TIMEOUT_MILLIS)

        compose.onNodeWithText("Two").assertDoesNotExist()
        compose.onNodeWithTag(ERROR_DETAIL_TAG).assertExists()
        captureScreen("live_player_error")
        failing.release()
    }

    @Test
    fun `typing a number shows only the number box until it tunes, and then the banner`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()

        press(Key.One)
        press(Key.Two)

        compose.onNodeWithText("12").assertExists()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
        compose.onNodeWithText("Twelve").assertDoesNotExist()

        compose.mainClock.advanceTimeBy(NUMBER_ENTRY_MILLIS)
        tuneTo(12L)

        compose.onNodeWithText("Twelve").assertExists()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `typing the number of the channel already on still shows its banner`() {
        showChannel(queue = CHANNELS, currentId = 12L)
        hideBanner()

        press(Key.One)
        press(Key.Two)
        compose.mainClock.advanceTimeBy(NUMBER_ENTRY_MILLIS)

        compose.onNodeWithText("Twelve").assertExists()
    }

    @Test
    fun `picking the channel already on from the list still shows its banner`() {
        showChannel(queue = CHANNELS, currentId = 12L)
        hideBanner()
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Twelve").performClick()
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Channels").assertDoesNotExist()
        compose.onNodeWithText("Twelve").assertExists()
    }

    @Test
    fun `opening the channel list takes the banner away, and it doesn't come back with the list open`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        compose.onAllNodesWithText("Two").assertCountEquals(1)

        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS * 5)
        compose.onAllNodesWithText("Two").assertCountEquals(1)

        tuneTo(12L)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS * 5)
        compose.onAllNodesWithText("Twelve").assertCountEquals(1)
    }

    @Test
    fun `picking from the channel list shows the banner, not the controls`() {
        showChannel(queue = CHANNELS, currentId = 10L)
        hideBanner()
        press(Key.DirectionLeft)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)

        compose.onNodeWithText("Twelve").performClick()
        tuneTo(12L)

        assertThat(played).containsExactly(12L)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS)
        compose.onNodeWithText("Channels").assertDoesNotExist()
        compose.onNodeWithText("Twelve").assertExists()
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `a quality switch doesn't show the banner`() {
        val sd = 21L
        val hd = 22L
        val quality = listOf(PlaybackQueue.Item(sd, "Sport", logoUrl = null, qualityIds = setOf(sd, hd)))
        showChannel(queue = quality, currentId = sd)
        hideBanner()

        tuneTo(hd)

        compose.onNodeWithText("Sport").assertDoesNotExist()
    }

    @Test
    fun `turning on live pause rebuilds the player without showing the banner`() {
        showChannel(queue = CHANNELS, currentId = 11L, livePause = false)
        hideBanner()

        // What PlayerScreen does when the setting turns on: a new player, on the same channel.
        val rebuilt = players.controller(LIVE, dvr = true)
        livePause = true
        current = rebuilt
        compose.waitForIdle()
        players.play(rebuilt, isLive = true)
        compose.waitForIdle()

        compose.onNodeWithText("Two").assertDoesNotExist()
        rebuilt.release()
    }

    @Test
    fun `the banner leaves out a number or a programme the channel doesn't have`() {
        val unnumbered = listOf(PlaybackQueue.Item(30L, "Radio", logoUrl = null))

        showChannel(queue = unnumbered, currentId = 30L)

        compose.onNodeWithText("Radio").assertExists()
        captureScreen("live_player_banner_name_only")
    }

    @Test
    fun `OK with the banner up opens the controls and the banner goes`() {
        showChannel(queue = CHANNELS, currentId = 11L)

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(FOCUS_MILLIS * 5)

        assertThat(compose.seekBarIsFocused()).isTrue()
        compose.onNodeWithText("Two").assertDoesNotExist()
    }

    @Test
    fun `media keys still open the controls`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        hideBanner()

        press(Key.MediaPlayPause)

        compose.onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `d-pad right opens the bar with the quality picker when there's more than one quality`() {
        variants = listOf(row(11L, "HD"), row(12L, "FHD"))
        showChannel(queue = CHANNELS, currentId = 11L)
        hideBanner()

        press(Key.DirectionRight)

        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithText("Quality").assertExists()
        compose.onNodeWithText("FHD").assertExists()
    }

    @Test
    fun `a tap toggles the controls`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        hideBanner()

        compose.onRoot().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(TAP_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertExists()

        compose.onRoot().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(TAP_MILLIS)
        compose.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `back with the banner up leaves the player`() {
        showChannel(queue = CHANNELS, currentId = 11L)
        compose.onNodeWithText("Two").assertExists()

        Espresso.pressBackUnconditionally()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `the banner stays hidden in picture-in-picture`() {
        PipState.setInPip(true)
        try {
            showChannel(queue = CHANNELS, currentId = 11L)

            compose.onNodeWithText("Two").assertDoesNotExist()
        } finally {
            PipState.setInPip(false)
        }
    }

    private companion object {
        const val FOCUS_MILLIS = 100L
        const val LONG_WHILE_MILLIS = 300_000L
        const val TAP_MILLIS = 1_000L
        const val BANNER_TIMEOUT_MILLIS = 5_000L

        // Shorter than the banner's and the controls' timeouts, and twice it is longer than either.
        const val PART_OF_A_TIMEOUT_MILLIS = 3_000L
        const val CONTROLS_TIMEOUT_MILLIS = 6_000L
        const val NUMBER_ENTRY_MILLIS = 2_500L
        const val HOLD_MILLIS = 1_000L

        val CHANNELS = listOf(
            PlaybackQueue.Item(10L, "One", logoUrl = null, number = 1),
            PlaybackQueue.Item(11L, "Two", logoUrl = null, number = 2),
            PlaybackQueue.Item(12L, "Twelve", logoUrl = null, number = 12),
        )
    }
}
