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
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.ui.vod.NextEpisodeOverlay
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

/**
 * The Next Episode card with the system's animations removed. Apart from [NextEpisodeOverlayTest]
 * because the animation scale belongs to the compose rule, and so to the whole class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NextEpisodeOverlayAnimationsOffTest {

    /** The system's animation scale at 0, as "Remove animations" sets it. */
    private object AnimationsOff : MotionDurationScale {
        override val scaleFactor = 0f
    }

    @get:Rule
    val compose = createComposeRule(effectContext = AnimationsOff)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val playback = FakePlayback()
    private val vm = VodPlayerViewModel(app, graph)

    @After
    fun close() = graph.database.close()

    private fun cardTop() = compose.onNodeWithText("Episode 2").getUnclippedBoundsInRoot().top

    @Test
    fun `with the system's animations removed the card is in place from its first frame`() {
        runBlocking {
            graph.database.episodes().upsertAll((1..6).map { testEpisode(1, it) })
            vm.attach(testEpisode(1, 1).key, playback)
        }
        compose.setTvContent {
            Box(Modifier.fillMaxSize().focusable()) { NextEpisodeOverlay(vm, onStop = {}) }
        }
        compose.mainClock.autoAdvance = false

        playback.positionMillis = EPISODE_MILLIS - 12_000L
        runBlocking { vm.poll() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val early = cardTop()
        compose.mainClock.advanceTimeBy(1_000)

        assertThat(early).isEqualTo(cardTop())
    }
}
