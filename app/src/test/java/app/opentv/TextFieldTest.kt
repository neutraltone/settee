/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The keyboard rules every text box in the app shares, checked once here at the design system's
// TextField rather than on each screen. Plain Application: OpenTvApp schedules WorkManager on start.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val state = TextFieldState()

    /** A box between two buttons, with the Highlight starting on the one above. */
    private fun show() {
        compose.setTvContent {
            view = LocalView.current
            Column(Modifier.width(600.dp)) {
                Button("Above")
                TextField(state = state, placeholder = "Find", maxLength = 40)
                Button("Below")
            }
        }
        compose.onNodeWithText("Above").requestFocus()
    }

    @Composable
    private fun Button(label: String) =
        HighlightSurface(onClick = {}, style = HighlightStyle.Button, modifier = Modifier.fillMaxWidth()) {
            Text(label, SetteeType.Body)
        }

    @Test
    fun `the keyboard stays shut when the Highlight arrives on the box`() {
        show()

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        assertThat(device.highlighted()).isTrue()
        assertThat(device.showing).isFalse()
        captureScreen("textfield_highlighted")
    }

    @Test
    fun `OK on the box opens the keyboard`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(device.showing).isTrue()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `Down leaves the box while the keyboard is shut`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNodeWithText("Below").assertIsFocused()
        assertThat(device.showing).isFalse()
    }

    @Test
    fun `Up leaves the box while the keyboard is shut, even with text before the cursor`() {
        state.setTextAndPlaceCursorAtEnd("bbc")
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        device.remote(KeyEvent.KEYCODE_DPAD_UP)

        compose.onNodeWithText("Above").assertIsFocused()
    }

    @Test
    fun `Back closes the keyboard and leaves the Highlight on the box`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.close()

        assertThat(device.showing).isFalse()
        assertThat(device.highlighted()).isTrue()
    }

    @Test
    fun `the keyboard's action key closes it and carries on to what's below`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.pressAction()

        assertThat(device.showing).isFalse()
        compose.onNodeWithText("Below").assertIsFocused()
    }

    @Test
    fun `dictation types into the box`() {
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.type("bbc news")

        assertThat(state.text.toString()).isEqualTo("bbc news")
        captureScreen("textfield_typed")
    }

    @Test
    fun `a label above the box and a note below it are shown`() {
        compose.setTvContent {
            view = LocalView.current
            Column(Modifier.width(600.dp)) {
                TextField(value = "", onValueChange = {}, label = "Server", supportingText = "Starts with http")
            }
        }

        compose.onNodeWithText("Server").assertIsDisplayed()
        compose.onNodeWithText("Starts with http").assertIsDisplayed()
        captureScreen("textfield_label")
    }
}
