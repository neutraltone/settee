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
import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.ui.vod.NextEpisodeOverlay
import app.opentv.ui.vod.VodPlayerViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Next Episode card with the system's animations removed. Apart from [NextEpisodeOverlayTest]
 * because the animation scale belongs to the compose rule, and so to the whole class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NextEpisodeOverlayAnimationsOffTest {

    private val compose = createComposeRule(effectContext = AnimationsOff)

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val playback = FakePlayback()
    private val vm by lazy { testGraph.viewModel { VodPlayerViewModel(app, graph) } }

    // Its very first frame is drawn before the animation has had a frame to finish on, so it's
    // checked from the second: one invisible frame, which nobody sees.
    @Test
    fun `with the system's animations removed the card doesn't rise`() {
        runBlocking { vm.startSeason(graph, playback) }
        compose.setTvContent {
            Box(Modifier.fillMaxSize().focusable()) { NextEpisodeOverlay(vm, onStop = {}) }
        }
        compose.mainClock.autoAdvance = false

        runBlocking { vm.pollWithSecondsLeft(playback, 12) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val secondFrame = compose.cardTop()
        compose.mainClock.advanceTimeBy(1_000)

        assertThat(secondFrame).isEqualTo(compose.cardTop())
    }
}
