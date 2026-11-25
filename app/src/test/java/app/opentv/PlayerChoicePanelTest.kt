/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.player.PlayerChoice
import app.opentv.ui.player.PlayerChoicePanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The player's choice panel on its own, for what no player screen shows yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PlayerChoicePanelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a panel with no rows and nothing to highlight in its footer still takes the highlight, and Back closes it`() {
        val start = FocusRequester()
        var closed = 0
        compose.setTvContent {
            PlayerChoicePanel(
                title = "Audio",
                choices = emptyList(),
                startFocus = start,
                onClose = { closed++ },
                empty = "",
                footer = { Text("Couldn't reach OpenSubtitles", SetteeType.Body) },
            )
        }

        compose.runOnIdle { start.requestFocus() }

        compose.onNode(isFocused()).assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun `rows arriving while the empty panel has the highlight take it, on the selected one`() {
        val start = FocusRequester()
        var choices by mutableStateOf(emptyList<PlayerChoice>())
        compose.setTvContent {
            PlayerChoicePanel(title = "Audio", choices = choices, startFocus = start, onClose = {})
        }
        compose.runOnIdle { start.requestFocus() }
        compose.onNode(isFocused()).assert(hasText("None available"))

        choices = listOf(PlayerChoice("English", selected = false, onClick = {}), PlayerChoice("French", true, {}))
        compose.waitForIdle()

        compose.onNodeWithText("French").assertIsFocused()
    }

    @Test
    fun `a choice panel slides in from the right edge`() {
        assertThat(slideOf(LayoutDirection.Ltr)).isGreaterThan(0f)
    }

    @Test
    fun `in a right-to-left language a choice panel slides in from the left edge`() {
        assertThat(slideOf(LayoutDirection.Rtl)).isLessThan(0f)
    }

    /** How far the panel's row is from where it settles, early in its slide in [direction]. */
    private fun slideOf(direction: LayoutDirection): Float {
        var open by mutableStateOf(false)
        val start = FocusRequester()
        compose.setTvContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                if (open) {
                    PlayerChoicePanel(
                        title = "Audio",
                        choices = listOf(PlayerChoice("English", selected = true, onClick = {})),
                        startFocus = start,
                        onClose = {},
                    )
                }
            }
        }
        compose.holdClock()
        compose.changeWithClockHeld { open = true }
        compose.mainClock.advanceTimeByFrame()
        val early = compose.onNodeWithText("English").getUnclippedBoundsInRoot().left
        compose.mainClock.advanceTimeBy(1_000)
        return (early - compose.onNodeWithText("English").getUnclippedBoundsInRoot().left).value
    }
}
