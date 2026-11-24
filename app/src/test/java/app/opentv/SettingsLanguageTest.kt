/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsTags
import app.opentv.ui.settings.rememberSettingsMemory
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Choosing a language makes the activity again, so the whole app reloads translated. Settings comes
 * back where it was, on Display and playback with the Highlight on Language, so the viewer can check
 * or undo the change (#443).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLanguageTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private lateinit var view: View
    private var made = 0

    // Draws Settings in every activity made, as MainActivity draws itself in onCreate: a test's own
    // setContent would only reach the first, and the language change makes another.
    private val drawsSettings = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            made++
            (activity as ComponentActivity).setTvContent {
                view = LocalView.current
                SettingsScreen(memory = rememberSettingsMemory(), onBack = {}, openSubtitles = null)
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before
    fun drawSettings() = testGraph.app.registerActivityLifecycleCallbacks(drawsSettings)

    @After
    fun stopDrawing() {
        testGraph.app.unregisterActivityLifecycleCallbacks(drawsSettings)
        testGraph.graph.settings.setLanguageTag("")
    }

    private fun press(vararg keys: Int) = keys.forEach { key ->
        compose.runOnIdle {
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        compose.waitForIdle()
    }

    @Test
    fun `choosing a language comes back to Display and playback with the Highlight on Language`() {
        ActivityScenario.launch(ComponentActivity::class.java).use {
            compose.onNode(isFocused()).assertTextContains("Providers")
            // Down to Display and playback, the fourth section, then into its settings.
            press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNode(isFocused()).assertTextContains("Display and playback")
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Language").requestFocus()
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            // The languages are in a Side Panel, a window of its own, so OK goes to it. Choosing one
            // makes the activity again, as it does on the TV.
            compose.onNodeWithText("Français").requestFocus().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()

            assertThat(made).isEqualTo(2)
            compose.onNodeWithTag(SettingsTags.PANE_TITLE).assertTextEquals("Display and playback")
            compose.onNode(isFocused()).assertTextContains("Language", substring = true)
            captureScreen("settings_after_language_change")
        }
    }
}
