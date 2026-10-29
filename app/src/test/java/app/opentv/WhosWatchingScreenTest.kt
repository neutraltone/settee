/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Profile
import app.opentv.ui.WhosWatchingScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class WhosWatchingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val profiles = listOf(
        Profile(id = 1, name = "Tony"),
        Profile(id = 2, name = "Sam"),
        Profile(id = 3, name = "Kids"),
    )

    private val chosen = mutableListOf<Long>()

    private fun setScreen(lastActive: Long) = compose.setTvContent {
        WhosWatchingScreen(profiles = profiles, lastActiveId = lastActive, onChoose = { chosen += it })
    }

    @Test
    fun `the highlight starts on whoever was watching last`() {
        setScreen(lastActive = 2)

        compose.onNodeWithText("Sam").assertIsFocused()
        captureScreen("whos_watching_initial")
    }

    @Test
    fun `right then OK on the remote chooses the next Profile along`() {
        setScreen(lastActive = 2)

        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }

        compose.onNodeWithText("Kids").assertIsFocused()
        assertThat(chosen).containsExactly(3L)
    }
}
