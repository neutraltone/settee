/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.onboarding.AddSourceScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A TV-sized screen that isn't in TV mode: on a TV the form opens on the phone-pairing QR first.
// Plain Application: OpenTvApp schedules WorkManager on start, which the screen doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddSourceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun show() {
        val viewModel = SourcesViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setTvContent {
            view = LocalView.current
            AddSourceScreen(viewModel = viewModel, onFinished = {})
        }
    }

    @Test
    fun `the provider password is typed on a keyboard that hides it`() {
        show()

        val (info, _) = device.connection(device.box(PASSWORD))
        captureScreen("add_source_xtream")

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }

    // On a phone or tablet there's no OK to press: a tap is how the keyboard opens.
    @Test
    fun `tapping a box opens the keyboard`() {
        show()

        device.tap(device.box(SERVER))

        assertThat(device.showing).isTrue()
    }

    @Test
    fun `OK on a box's Paste button pastes rather than opening the keyboard`() {
        show()
        ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(null, "http://example.com"))
        compose.onAllNodesWithContentDescription("Paste")[0].requestFocus()

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(device.showing).isFalse()
        assertThat(device.textShownBy(device.box(SERVER))).isEqualTo("http://example.com")
    }

    @Test
    fun `a screen reader hears each box's label`() {
        show()

        val heard = compose.runOnIdle { device.box(PASSWORD).createAccessibilityNodeInfo().hintText?.toString() }

        assertThat(heard).isEqualTo("Password")
    }
}

private const val SERVER = 1

/** Name, server address, username, password: the order an Xtream login lays them out in. */
private const val PASSWORD = 3
