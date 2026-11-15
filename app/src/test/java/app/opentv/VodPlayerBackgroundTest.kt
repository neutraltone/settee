/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.PipState
import app.opentv.core.ServiceLocator
import app.opentv.player.PlayerController
import app.opentv.ui.player.SubtitlePicks
import app.opentv.ui.vod.VodPlayerContent
import app.opentv.ui.vod.vodPlayback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A film or episode behind another app pauses, keeps its place and waits there for Play (#356). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class VodPlayerBackgroundTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)
    private val controller = players.controller()
    private val graph = ServiceLocator.Graph(app)
    private val viewModels = TestViewModels()

    // From a store, so it's cleared before the database closes: looking up the next episode on its
    // scope could otherwise land after the close and fail a test with "connection is closed".
    private val viewModel = viewModels.vodPlayer(app, graph)
    private val lifecycle = TestLifecycle()

    @After
    fun release() {
        controller.release()
        players.close()
        viewModels.clear()
        graph.database.close()
    }

    /** An episode playing ten minutes in, in a screen whose lifecycle [lifecycle] moves. */
    private fun watchEpisodeTenMinutesIn() {
        runBlocking { graph.database.episodes().upsertAll(listOf(testEpisode(1, 1))) }
        compose.setTvContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                VodPlayerContent(controller, viewModel, false, SubtitlePicks(onOff = {}, onPicked = {}), onBack = {})
            }
        }
        runBlocking { viewModel.attach(testEpisode(1, 1).key, controller.vodPlayback()) }
        players.untilReady(controller.player)
        controller.player.seekTo(TEN_MINUTES)
        players.untilReady(controller.player)
        compose.waitForIdle()
    }

    @Test
    fun `behind another app the film pauses and keeps its place, and coming back it's still paused there`() {
        watchEpisodeTenMinutesIn()

        lifecycle.goBehind()
        compose.waitForIdle()

        assertThat(controller.playWhenReady.value).isFalse()
        compose.waitUntil(WAIT_MILLIS) { runBlocking { graph.viewerLibrary.resumeFrom("ep:11") } == TEN_MINUTES }

        lifecycle.comeBack()
        compose.waitForIdle()

        assertThat(controller.playWhenReady.value).isFalse()
        assertThat(controller.player.currentPosition).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `a stream that starts while the app is behind stays paused, as after a dropped connection`() {
        watchEpisodeTenMinutesIn()
        lifecycle.goBehind()
        compose.waitForIdle()

        // What an automatic restart, a step down or a stream that was still being found does.
        val again = PlayerController.Request(testEpisode(1, 1).key, startPositionMillis = TEN_MINUTES)
        controller.play(again, debounce = false)
        players.untilReady(controller.player)

        assertThat(controller.playWhenReady.value).isFalse()
    }

    @Test
    fun `in picture-in-picture the film plays on when the app is behind another`() {
        PipState.setInPip(true)
        try {
            watchEpisodeTenMinutesIn()

            lifecycle.goBehind()
            compose.waitForIdle()

            assertThat(controller.playWhenReady.value).isTrue()

            // Leaving picture-in-picture while behind is going behind, and saves the place off the main
            // thread: that write has to land before the database closes, or it fails with "already closed".
            PipState.setInPip(false)
            compose.waitUntil(WAIT_MILLIS) {
                runBlocking { graph.viewerLibrary.resumeFrom("ep:11") } >= TEN_MINUTES
            }
        } finally {
            PipState.setInPip(false)
        }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val TEN_MINUTES = 600_000L
    }
}
