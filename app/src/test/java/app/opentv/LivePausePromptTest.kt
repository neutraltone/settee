/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.opentv.ui.player.LivePausePrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LivePausePromptTest {

    @get:Rule
    val compose = createComposeRule()

    private var turnedOn = 0
    private var notNow = 0
    private var dontAsk = 0

    private fun show() = compose.setTvContent {
        LivePausePrompt(onTurnOn = { turnedOn++ }, onDontAskAgain = { dontAsk++ }, onDismiss = { notNow++ })
    }

    @Test
    fun `it opens as a Side Panel with the highlight on Turn on and pause`() {
        show()

        compose.onNodeWithText("Pause live TV?").assertExists()
        compose.onNodeWithText("Turn on and pause").assertIsFocused().assertIsInSidePanel()
        captureScreen("live_pause_prompt")
    }

    @Test
    fun `choosing Turn on and pause reports it`() {
        show()

        compose.onNodeWithText("Turn on and pause").performClick()

        assertThat(turnedOn).isEqualTo(1)
    }

    @Test
    fun `back is Not now`() {
        show()

        compose.pressBackOnPanel()

        assertThat(notNow).isEqualTo(1)
        assertThat(turnedOn).isEqualTo(0)
    }

    @Test
    fun `Don't ask again reports it`() {
        show()

        compose.onNodeWithText("Don't ask again").performClick()

        assertThat(dontAsk).isEqualTo(1)
    }
}
