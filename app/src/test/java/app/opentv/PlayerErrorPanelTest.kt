/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.DecoderSize
import app.opentv.player.PlayerController
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

/** A film that can't go on leaves the remote somewhere to go: the viewer was stuck on the sentence (#180). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class PlayerErrorPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val graph = ServiceLocator.Graph(app)
    private val viewModel = VodPlayerViewModel(app, graph)
    private val noPicks = SubtitlePicks(onOff = {}, onPicked = {})
    private val toRelease = mutableListOf<PlayerController>()

    @After
    fun release() {
        toRelease.forEach { it.release() }
        players.close()
        graph.database.close()
    }

    private fun show(controller: PlayerController, onBack: () -> Unit = {}) {
        toRelease += controller
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack) }
        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
    }

    private fun refused(): PlayerController {
        players.decoders = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
        players.formats = listOf(
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160).build(),
        )
        return players.controller()
    }

    private fun waitForSentence(text: String) {
        compose.waitUntil(5_000) {
            players.advance(100)
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a video this device can't play offers Back alone, with focus on it, and OK goes back`() {
        var wentBack = false
        show(refused()) { wentBack = true }
        waitForSentence("can't play this video")

        compose.onNodeWithText("Back").assertIsFocused()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        captureScreen("vod_player_cant_play")

        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertThat(wentBack).isTrue()
    }

    @Test
    fun `Back leaves at once when a picker was open as the stream failed`() {
        val fine = "https://example.com/1"
        val tooBig = "https://example.com/2"
        players.decoders = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265)
        players.formatsAt[fine] = listOf(hevc.setWidth(1920).setHeight(1080).build())
        players.formatsAt[tooBig] = listOf(hevc.setWidth(3840).setHeight(2160).build())
        val controller = players.controller { key ->
            val url = if (key == MediaKey.Movie(1)) fine else tooBig
            Playable.Ready(key, url, userAgent = "", title = "Film", isLive = false)
        }
        var backs = 0
        toRelease += controller
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = { backs++ }) }
        players.play(controller)
        compose.waitForIdle()
        compose.onNodeWithText("Audio").performClick()
        compose.waitForIdle()

        controller.play(PlayerController.Request(MediaKey.Movie(2)), debounce = false)
        waitForSentence("can't play this video")
        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `one Back leaves, with focus on the panel's button`() {
        var backs = 0
        val controller = refused()
        toRelease += controller
        compose.setTvContent { VodPlayerContent(controller, viewModel, false, noPicks, onBack = { backs++ }) }
        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        waitForSentence("can't play this video")

        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a failure trying again could fix lands on Try again, with Back beside it`() {
        show(players.controller { key -> Playable.Failed(key, Playable.Failure.GONE, "Film") })
        compose.waitUntil(5_000) {
            players.advance(100)
            compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Try again").assertIsFocused()
        compose.onNodeWithText("Back").assertExists()
    }
}
