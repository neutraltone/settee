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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.opentv.ui.settings.RecordingSettingsScreen
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
class RecordingSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `the network share's password stays hidden until it's revealed`() {
        compose.setTvContent {
            view = LocalView.current
            RecordingSettingsScreen(onBack = {})
        }
        compose.onNodeWithText("NAS / network share (SMB)").performScrollTo().performClick()
        val password = device.box(PASSWORD)
        device.type("secret", password)
        assertThat(device.connection(password).first.inputType)
            .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        // Far enough down to leave out the internal storage path, which changes with every run.
        compose.onNodeWithText("Test connection").performScrollTo()
        captureScreen("recording_settings_network_share")

        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()

        assertThat(device.connection(password).first.inputType)
            .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertThat(device.textShownBy(password)).isEqualTo("secret")
    }
}

/** Server, share, folder, username, password. */
private const val PASSWORD = 4
