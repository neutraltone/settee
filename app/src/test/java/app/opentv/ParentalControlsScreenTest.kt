/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.opentv.ui.settings.ParentalControlsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which the screen doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParentalControlsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `it explains how a lock travels between devices`() {
        compose.setTvContent { ParentalControlsScreen(onBack = {}) }

        compose.onNodeWithText(
            "Categories you lock here are also locked on your other devices when they sync. " +
                "Unlocking one only unlocks it on this device.",
        ).performScrollTo().assertExists()
        captureScreen("parental_controls_explanation")
    }
}
