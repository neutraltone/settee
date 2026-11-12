/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.design.ErrorMessage
import app.opentv.ui.design.ScreenFrame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every error is one sentence with a quiet detail line under it (#166). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ErrorMessageTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an error shows its sentence with the detail line under it`() {
        compose.setTvContent {
            ScreenFrame {
                ErrorMessage(
                    sentence = "Your provider refused this. Your account may be in use on another device, " +
                        "so stop it there and try again.",
                    detail = "IO bad HTTP status · HTTP 403 · HEVC, 1920x1080",
                )
            }
        }

        compose.onNodeWithText("Your provider refused this.", substring = true).assertExists()
        compose.onNodeWithTag(ERROR_DETAIL_TAG).assertTextEquals("IO bad HTTP status · HTTP 403 · HEVC, 1920x1080")
        captureScreen("ds_error_message")
    }

    @Test
    fun `an error with nothing known for its detail line draws no empty line`() {
        compose.setTvContent {
            ScreenFrame { ErrorMessage(sentence = "This didn't play. Try again, or pick something else.", detail = "") }
        }

        compose.onAllNodesWithTag(ERROR_DETAIL_TAG).assertCountEquals(0)
    }
}
