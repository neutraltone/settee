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
import app.opentv.ui.RecordingBackgroundDialog
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
class RecordingBackgroundDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private var allowed = 0
    private var notNow = 0

    private fun show() = compose.setTvContent {
        RecordingBackgroundDialog(onAllow = { allowed++ }, onDismiss = { notNow++ })
    }

    @Test
    fun `it opens as a Side Panel with the highlight on Allow`() {
        show()

        compose.onNodeWithText("Keep recording when you leave?").assertExists()
        compose.onNodeWithText("Allow").assertIsFocused().assertIsInSidePanel()
        captureScreen("recording_background_prompt")
    }

    @Test
    fun `choosing Allow reports it`() {
        show()

        compose.onNodeWithText("Allow").performClick()

        assertThat(allowed).isEqualTo(1)
    }

    @Test
    fun `back is Not now`() {
        show()

        compose.pressBackOnPanel()

        assertThat(notNow).isEqualTo(1)
        assertThat(allowed).isEqualTo(0)
    }
}
