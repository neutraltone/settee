/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.text.InputType
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.opentv.ui.settings.ParentalControlsScreen
import com.google.common.truth.Truth.assertThat
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

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `a new PIN is typed on a number keyboard that hides it, and keeps four digits`() {
        compose.setTvContent {
            view = LocalView.current
            ParentalControlsScreen(onBack = {})
        }
        val newPin = device.box(0)

        val (info, _) = device.connection(newPin)
        "12a345".forEach { device.type(it.toString(), newPin) }

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertThat(device.textShownBy(newPin)).isEqualTo("1234")
        captureScreen("parental_controls_new_pin")
    }

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
