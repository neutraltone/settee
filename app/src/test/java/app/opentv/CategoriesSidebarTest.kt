/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.CategoriesSidebar
import app.opentv.ui.design.SidebarEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class CategoriesSidebarTest {

    @get:Rule
    val compose = createComposeRule()

    private val pinned = listOf(SidebarEntry("home", "For you"))

    private val entries = listOf(
        SidebarEntry("action", "Action"),
        SidebarEntry("comedy", "Comedy"),
        SidebarEntry("drama", "Drama"),
    )

    private val picks = mutableListOf<String>()

    /** The sidebar beside something to come from, as a screen's content is. */
    private fun setSidebar(current: String = "home") {
        compose.setTvContent {
            var shown by remember { mutableStateOf(current) }
            Row {
                CategoriesSidebar(
                    pinned = pinned,
                    entries = entries,
                    current = shown,
                    onPick = {
                        picks += it
                        shown = it
                    },
                    findBox = { state, modifier -> FindBox(state, modifier) },
                )
                androidx.compose.foundation.layout.Box(
                    Modifier.size(100.dp).testTag("content").focusable(),
                )
            }
        }
    }

    @Composable
    private fun FindBox(state: TextFieldState, modifier: Modifier) {
        androidx.compose.foundation.text.BasicTextField(state, modifier.testTag("find"))
    }

    @Test
    fun `coming in from the content lands on the current category`() {
        setSidebar(current = "comedy")
        compose.onNodeWithTag("content").requestFocus().performKeyInput { pressKey(Key.DirectionLeft) }

        compose.onNodeWithText("Comedy").assertIsFocused()
        assertThat(picks).isEmpty()
    }

    @Test
    fun `Up from the first row reaches Find a category, and Down comes back to the list`() {
        setSidebar()
        compose.onNodeWithText("For you").requestFocus().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("find").assertIsFocused()

        compose.onNodeWithTag("find").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("For you").assertIsFocused()
    }

    @Test
    fun `typing in Find a category narrows the list and counts what's showing`() {
        setSidebar()
        compose.onNodeWithTag("find").performTextInput("co")

        compose.onNodeWithText("Comedy").assertExists()
        compose.onAllNodesWithText("Action").assertCountEquals(0)
        compose.onAllNodesWithText("Drama").assertCountEquals(0)
        // For you isn't a category: always there, and not counted.
        compose.onNodeWithText("For you").assertExists()
        compose.onNodeWithText("1 of 3").assertExists()
    }
}
