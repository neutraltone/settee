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
import app.opentv.ui.ExitPrompt
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
class ExitPromptTest {

    @get:Rule
    val compose = createComposeRule()

    private var left = 0
    private var stayed = 0

    private fun show() = compose.setTvContent { ExitPrompt(onLeave = { left++ }, onStay = { stayed++ }) }

    @Test
    fun `it asks in a Side Panel with the highlight on Stay`() {
        show()

        compose.onNodeWithText("Leave Settee?").assertExists()
        compose.onNodeWithText("You'll go back to your TV's home screen.").assertExists()
        compose.onNodeWithText("Stay").assertIsFocused().assertIsInSidePanel()
        captureScreen("exit_prompt")
    }

    @Test
    fun `back stays`() {
        show()

        compose.pressBackOnPanel()

        assertThat(stayed).isEqualTo(1)
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `choosing Leave leaves`() {
        show()

        compose.onNodeWithText("Leave").performClick()

        assertThat(left).isEqualTo(1)
    }
}
