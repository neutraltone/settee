/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.ui.directions.Direction
import app.opentv.ui.directions.DirectionScreen
import app.opentv.ui.directions.Screen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PROTOTYPE for #89: E's press limits from #87. Throwaway, never merged. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectionsFocusTest {

    @get:Rule
    val compose = createComposeRule()

    private fun focusedHolds(text: String) =
        compose.onNode(isFocused() and hasAnyDescendant(hasText(text))).assertExists()

    @Test
    fun `Home opens on the first Continue Watching card`() {
        compose.setTvContent { DirectionScreen(Direction.COMBINED, Screen.HOME) }
        compose.waitForIdle()
        focusedHolds("Harbour Lights")
    }

    @Test
    fun `one Down from the Live Preview reaches the first Continue Watching card`() {
        compose.setTvContent { DirectionScreen(Direction.COMBINED, Screen.HOME_PREVIEW) }
        compose.waitForIdle()
        focusedHolds("The Evening Programme")
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        focusedHolds("Harbour Lights")
        captureScreen("e_combined_home_preview_down")
    }

    @Test
    fun `Left from the guide's first column opens categories on the current one`() {
        compose.setTvContent { DirectionScreen(Direction.COMBINED, Screen.GUIDE) }
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        focusedHolds("All channels")
    }
}
