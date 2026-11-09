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
import app.opentv.ui.settings.StremioAddonsScreen
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
class StremioAddonsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `an add-on's address is typed on a keyboard for web addresses`() {
        compose.setTvContent {
            view = LocalView.current
            StremioAddonsScreen(onBack = {})
        }

        val (info, _) = device.connection()
        captureScreen("stremio_addons")

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    }
}
