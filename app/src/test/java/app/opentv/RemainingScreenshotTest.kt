/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.createComposeRule
import app.opentv.ui.directions.Highlight
import app.opentv.ui.directions.ProtoLook
import app.opentv.ui.directions.Remaining
import app.opentv.ui.directions.RemainingScreen
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PROTOTYPE for #152: every option for the remaining screens, at 1080p. Throwaway, never merged. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemainingScreenshotTest(private val screen: Remaining) {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun reset() {
        ProtoLook.highlight = Highlight.APPROVED
        ProtoLook.lining = false
        ProtoLook.floor = false
    }

    @Test
    fun render() {
        ProtoLook.highlight = Highlight.KEYLINE
        ProtoLook.lining = true
        ProtoLook.floor = true
        compose.setTvContent { RemainingScreen(screen) }
        compose.waitForIdle()
        captureScreen("r_${screen.name.lowercase()}")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = Remaining.entries.map { arrayOf<Any>(it) }
    }
}
