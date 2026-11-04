/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.ui.directions.Direction
import app.opentv.ui.directions.DirectionScreen
import app.opentv.ui.directions.Highlight
import app.opentv.ui.directions.ProtoLook
import app.opentv.ui.directions.Screen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PROTOTYPE for #151: every highlight option on the hardest artwork and on rows. Throwaway, never merged. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightScreenshotTest(private val option: Highlight) {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun reset() {
        ProtoLook.highlight = Highlight.APPROVED
        ProtoLook.lining = false
        ProtoLook.floor = false
        ProtoLook.captionScrim = false
    }

    private fun show(screen: Screen, keys: List<Key> = emptyList()) {
        ProtoLook.highlight = option
        compose.setTvContent { DirectionScreen(Direction.COMBINED, screen) }
        compose.waitForIdle()
        keys.forEach { key ->
            compose.onNode(isFocused()).performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    @Test fun warm() { show(Screen.HOME); captureScreen("${option.key}_warm") }

    @Test fun white() { show(Screen.HOME, listOf(Key.DirectionDown, Key.DirectionLeft)); captureScreen("${option.key}_white") }

    @Test fun printed() { show(Screen.HOME, listOf(Key.DirectionDown)); captureScreen("${option.key}_printed") }

    @Test fun dark() { show(Screen.HOME, listOf(Key.DirectionDown, Key.DirectionRight)); captureScreen("${option.key}_dark") }

    @Test fun preview() { show(Screen.HOME_PREVIEW); captureScreen("${option.key}_preview") }

    @Test fun rail() { show(Screen.HOME, listOf(Key.DirectionLeft)); captureScreen("${option.key}_rail") }

    @Test fun shows() { show(Screen.SHOWS); captureScreen("${option.key}_shows") }

    @Test fun guide() { show(Screen.GUIDE); captureScreen("${option.key}_guide") }

    @Test fun sidebar() { show(Screen.GUIDE, listOf(Key.DirectionLeft)); captureScreen("${option.key}_sidebar") }

    @Test fun button() { show(Screen.DETAILS); captureScreen("${option.key}_button") }

    @Test fun episode() { show(Screen.DETAILS, listOf(Key.DirectionDown)); captureScreen("${option.key}_episode") }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Highlight.entries.map { arrayOf<Any>(it) }
    }
}

/** PROTOTYPE for #151: the two type choices the font research handed over. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypeChoiceScreenshotTest(private val name: String, private val lining: Boolean, private val floor: Boolean) {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun reset() {
        ProtoLook.lining = false
        ProtoLook.floor = false
        ProtoLook.captionScrim = false
    }

    private fun show(screen: Screen, keys: List<Key> = emptyList()) {
        ProtoLook.lining = lining
        ProtoLook.floor = floor
        compose.setTvContent { DirectionScreen(Direction.COMBINED, screen) }
        compose.waitForIdle()
        keys.forEach { key ->
            compose.onNode(isFocused()).performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    @Test fun home() { show(Screen.HOME); captureScreen("type_${name}_home") }

    @Test fun guide() { show(Screen.GUIDE); captureScreen("type_${name}_guide") }

    @Test fun details() { show(Screen.DETAILS, listOf(Key.DirectionDown)); captureScreen("type_${name}_details") }

    @Test fun sidebar() { show(Screen.SHOWS_CATEGORIES); captureScreen("type_${name}_sidebar") }

    @Test fun scrim() {
        ProtoLook.captionScrim = true
        show(Screen.HOME)
        captureScreen("type_${name}_scrim")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf<Any>("today", false, false),
            arrayOf<Any>("lining", true, false),
            arrayOf<Any>("floor", false, true),
            arrayOf<Any>("both", true, true),
        )
    }
}
