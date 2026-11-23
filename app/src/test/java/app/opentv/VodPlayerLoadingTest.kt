/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.opentv.data.repo.MediaKey
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.VodPlayerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A spinner while the Quality to play is being found, and not once a film has finished (#180). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerLoadingTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val app = testGraph.app
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = testGraph.graph
    private val viewModel = testGraph.track(VodPlayerViewModel(app, graph))
    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @After
    fun release() {
        controller.release()
        players.close()
    }

    private fun show() = compose.setTvContent {
        VodPlayerContent(controller, viewModel, false, SubtitlePicks(onOff = {}, onPicked = {}), onBack = {})
    }

    @Test
    fun `the aerial TV shows while nothing has started yet`() {
        show()
        compose.waitForIdle()

        compose.onNode(spinner).assertExists()
        compose.onNodeWithContentDescription("Loading").assertExists()
        captureScreen("vod_player_waiting")
    }

    @Test
    fun `nothing waits once a film has finished`() {
        show()
        runBlocking { viewModel.attach(MediaKey.Movie(1), FakePlayback()) }
        players.play(controller)

        controller.player.seekTo(controller.player.duration - 100)
        players.until { controller.player.playbackState == Player.STATE_ENDED }
        compose.waitForIdle()

        compose.onNode(spinner).assertDoesNotExist()
    }
}
