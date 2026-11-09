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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.opentv.ui.settings.EpgSettingsScreen
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
class EpgSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `adding your own guide asks for its address on a keyboard for web addresses`() {
        compose.setTvContent {
            view = LocalView.current
            EpgSettingsScreen(onBack = {})
        }

        compose.onNodeWithText("Add your own guide (XMLTV URL)").performScrollTo().performClick()
        val (info, _) = device.connection(device.box(URL))
        captureScreen("epg_settings_add_own")

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    }
}

/** Name, then address. */
private const val URL = 1
