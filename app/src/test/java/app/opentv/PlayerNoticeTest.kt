/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.test.utils.ExoPlayerTestRunner
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.DecoderSize
import app.opentv.player.PlayerController
import app.opentv.ui.player.LivePlayerContent
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The player's short messages show over the picture for about 5 seconds and leave the D-pad alone (#184). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class PlayerNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val surface = Surface(SurfaceTexture(0))
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})

    @After
    fun close() {
        players.close()
        surface.release()
        forgetVideoDecoders()
    }

    private fun focusedId() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.id }

    @Test
    fun `the live player says it stepped down, without taking focus, and the message goes`() {
        players.formatsAt["https://example.com/4"] = listOf(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(3840).setHeight(2160).build(),
        )
        players.formatsAt["https://example.com/2"] = listOf(ExoPlayerTestRunner.VIDEO_FORMAT)
        players.decoders = FakeDecoders(MimeTypes.VIDEO_H264 to DecoderSize(1920, 1088))
        val controller = players.controller(LIVE) { key ->
            val id = (key as MediaKey.Live).channelId
            Playable.Ready(key, "https://example.com/$id", userAgent = "", title = "News", isLive = true)
        }
        compose.setTvContent {
            LivePlayerContent(
                controller = controller,
                settings = AppSettings.get(app),
                queue = emptyList(),
                variants = emptyList(),
                currentId = 4L,
                previousId = null,
                cardChannel = null,
                nowAndNext = { null },
                onNow = { emptyMap() },
                recordingThis = false,
                livePause = false,
                pausePromptDismissed = true,
                onPlayChannel = {},
                onZap = { _, _ -> },
                onTune = {},
                onToggleRecord = {},
                favourite = false,
                onToggleFavourite = {},
                onTurnOnLivePause = {},
                onDontAskAgain = {},
                subtitlePicks = noPicks,
                onBack = {},
                onChannelOptions = { _, _ -> },
            )
        }
        compose.waitForIdle()
        val focusedBefore = focusedId()

        controller.play(
            PlayerController.Request(
                MediaKey.Live(4),
                label = "4K",
                lower = listOf(PlayerController.Quality(MediaKey.Live(2), "HD")),
            ),
            debounce = false,
        )
        players.until { controller.notice.value != null }
        compose.waitForIdle()

        val message = "This device can't play 4K. Playing HD instead."
        compose.onNodeWithText(message).assertExists()
        assertThat(focusedId()).isEqualTo(focusedBefore)
        captureScreen("live_player_stepped_down")

        players.advance(5_100)
        compose.waitForIdle()
        compose.onNodeWithText(message).assertDoesNotExist()
        controller.release()
    }

    @Test
    fun `the VOD player says there's no sound, without taking focus`() {
        forgetVideoDecoders()
        listVideoDecoder("video.decoder", MimeTypes.VIDEO_H264, 1920, 1080)
        players.formats = listOf(
            ExoPlayerTestRunner.VIDEO_FORMAT,
            Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).setChannelCount(6).setSampleRate(48_000).build(),
        )
        val controller = players.realDecoderController(surface = surface)
        val graph = ServiceLocator.Graph(app)
        val viewModel = VodPlayerViewModel(app, graph)
        compose.setTvContent {
            VodPlayerContent(controller, viewModel, false, noPicks, onBack = {})
        }
        compose.waitForIdle()
        val focusedBefore = focusedId()

        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.until { controller.notice.value != null }
        // The film's own clocks would otherwise run the message out while the screen settled.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("This device can't play this audio format (E-AC-3)", substring = true).assertExists()
        assertThat(focusedId()).isEqualTo(focusedBefore)
        controller.release()
        graph.database.close()
    }
}
