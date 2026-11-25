/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.CatalogueRefresh.Setup
import app.opentv.data.repo.SetupStep
import app.opentv.ui.setup.SetupScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Setup finishing with the system's animations removed. Apart from [SetupScreenTest] because the
 * animation scale belongs to the compose rule, and so to the whole class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenAnimationsOffTest {

    @get:Rule
    val compose = createComposeRule(effectContext = AnimationsOff)

    private val summary =
        "1,204 channels, 6,400 movies and 1,100 shows. Film details will keep filling in while you watch."

    private fun topOf(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private val channels = CatalogueRefresh.StepDone(SetupStep.CHANNELS, count = 1_204)

    private val channelsIn = Setup(SetupStep.entries, running = SetupStep.GUIDE, bar = 0.2f, done = listOf(channels))

    private val finished = Setup(
        SetupStep.entries,
        running = null,
        bar = 1f,
        done = listOf(
            channels,
            CatalogueRefresh.StepDone(SetupStep.GUIDE, count = 980, of = 1_204),
            CatalogueRefresh.StepDone(SetupStep.MOVIES, count = 6_400),
            CatalogueRefresh.StepDone(SetupStep.SHOWS, count = 1_100),
        ),
        finished = true,
    )

    // Its very first frame is drawn before the animation has had a frame to finish on, so it's
    // checked from the second: one invisible frame, which nobody sees.
    @Test
    fun `with the system's animations removed, You're all set is in place at once`() {
        var setup by mutableStateOf(channelsIn)
        compose.setTvContent { SetupScreen(setup, onWatchLive = {}, onFinish = {}) }
        compose.mainClock.autoAdvance = false

        setup = finished
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val title = topOf("You're all set.")
        val rest = topOf(summary)
        compose.mainClock.advanceTimeBy(1_000)

        assertThat(topOf("You're all set.")).isEqualTo(title)
        assertThat(topOf(summary)).isEqualTo(rest)
    }
}
