/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.opentv.ui.StatusLine
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
class StatusLineTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `work that knows how far through it is shows the percentage and a bar`() {
        compose.setTvContent { StatusLine("Building the guide", progress = 0.4f) }

        compose.onNodeWithText("Building the guide").assertExists()
        compose.onNodeWithText("40%").assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.4f, 0f..1f))).assertExists()
        compose.onNodeWithContentDescription("Still working").assertDoesNotExist()
        captureScreen("status_line_progress")
    }

    @Test
    fun `work that doesn't know how far through it is shows the small aerial TV`() {
        compose.setTvContent { StatusLine("Loading channels", progress = null) }

        compose.onNodeWithText("Loading channels").assertExists()
        compose.onNodeWithContentDescription("Still working").assertExists()
        compose.onNodeWithText("%", substring = true).assertDoesNotExist()
        captureScreen("status_line_working")
    }
}
