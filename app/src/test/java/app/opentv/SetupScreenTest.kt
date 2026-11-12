/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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

/** Setup's screen (docs/LOOK.md, Setting up), driven by the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SetupScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pressed = mutableListOf<String>()

    private fun setScreen(setup: Setup, liveTv: Boolean = true) = compose.setTvContent {
        SetupScreen(
            setup = setup,
            onWatchLive = if (liveTv) ({ pressed += "watch live" }) else null,
            onFinish = { pressed += "finish" },
        )
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `while the channels load it says so, and nothing on the remote does anything`() {
        setScreen(Setup(SetupStep.entries))

        compose.onNodeWithText("Adding your channels").assertIsDisplayed()
        captureScreen("setup_adding_channels")
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionCenter)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionRight)
        }
        back()

        assertThat(compose.onAllNodes(isFocused()).fetchSemanticsNodes()).isEmpty()
        assertThat(pressed).isEmpty()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    private val channelsIn = Setup(
        SetupStep.entries,
        running = SetupStep.GUIDE,
        bar = 0.2f,
        done = listOf(CatalogueRefresh.StepDone(SetupStep.CHANNELS, count = 1_204)),
    )

    @Test
    fun `once the channels are in, Watch live TV now has the highlight and OK goes to live TV`() {
        setScreen(channelsIn)

        compose.onNodeWithText("Watch live TV now").assertIsFocused()
        captureScreen("setup_ready_to_leave")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(pressed).containsExactly("watch live")
    }

    @Test
    fun `once the channels are in, Back goes to live TV too`() {
        setScreen(channelsIn)

        back()

        assertThat(pressed).containsExactly("watch live")
    }

    @Test
    fun `a step that's done says what it found for a moment, then the next step is named`() {
        var setup by mutableStateOf(Setup(SetupStep.entries))
        compose.setTvContent { SetupScreen(setup, onWatchLive = {}, onFinish = {}) }
        compose.mainClock.autoAdvance = false

        setup = channelsIn
        // With the clock paused, nothing else tells Compose the state changed.
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("1,204 channels added").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("Loading the TV guide").assertIsDisplayed()
        compose.onNodeWithText("1,204 channels added").assertDoesNotExist()
    }

    @Test
    fun `with Live TV switched off there's no way out until setup is done`() {
        setScreen(channelsIn, liveTv = false)

        back()

        compose.onNodeWithText("Watch live TV now").assertDoesNotExist()
        assertThat(pressed).isEmpty()
        assertThat(compose.activity.isFinishing).isFalse()
    }

    private fun finished(vararg failed: SetupStep) = Setup(
        SetupStep.entries,
        running = null,
        bar = 1f,
        done = listOf(
            CatalogueRefresh.StepDone(SetupStep.CHANNELS, count = 1_204),
            CatalogueRefresh.StepDone(SetupStep.GUIDE, count = 980, of = 1_204, failed = SetupStep.GUIDE in failed),
            CatalogueRefresh.StepDone(SetupStep.MOVIES, count = 6_400, failed = SetupStep.MOVIES in failed),
            CatalogueRefresh.StepDone(SetupStep.SHOWS, count = 1_100, failed = SetupStep.SHOWS in failed),
        ),
        finished = true,
    )

    @Test
    fun `once finished it sums up the library, and Take me to Settee has the highlight`() {
        setScreen(finished())

        compose.onNodeWithText("You're all set.").assertIsDisplayed()
        compose.onNodeWithText(
            "1,204 channels, 6,400 movies and 1,100 shows. Film details will keep filling in while you watch.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Watch live TV now").assertDoesNotExist()
        compose.onNodeWithText("Take me to Settee").assertIsFocused()
        captureScreen("setup_finished")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(pressed).containsExactly("finish")
    }

    @Test
    fun `once finished, Back takes you in too`() {
        setScreen(finished())

        back()

        assertThat(pressed).containsExactly("finish")
    }

    @Test
    fun `a guide or list that didn't load is named on the finished screen`() {
        setScreen(finished(SetupStep.GUIDE, SetupStep.SHOWS))

        compose.onNodeWithText("The TV guide didn't load. Settee will try again on the next refresh.")
            .assertIsDisplayed()
        compose.onNodeWithText(
            "Shows didn't load. Settee will try again on the next refresh, or when you open Shows.",
        ).assertIsDisplayed()
        // The summary counts only what loaded.
        compose.onNodeWithText(
            "1,204 channels and 6,400 movies. Film details will keep filling in while you watch.",
        ).assertIsDisplayed()
        captureScreen("setup_finished_with_failures")
    }
}
