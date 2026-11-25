/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import app.opentv.sync.NasSync
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import java.net.ConnectException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Your devices: what it says about the NAS sync (#484). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevicesPaneTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "devices-pane-test")

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = testGraph.graph

    private fun show() = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(
            memory = SettingsMemory(SettingsSection.Devices),
            onBack = {},
            onAddProvider = {},
            onEditProvider = {},
            onOpenChannels = {},
            onOpenLockedCategories = {},
            openSubtitles = null,
        )
    }

    @Test
    fun `Your devices says the last NAS sync failed, and why, in place of its reassuring note`() {
        graph.settings.setSmbConfig("nas.example.com", "share", "", "u", "p")
        graph.settings.setNasAutoSync(true)
        // As the launch's sync leaves it when the NAS is off.
        graph.lastNasSync.record(NasSync.Result.Failed(ConnectException()))
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        val failed = hasText("Sync through your network drive now") and
            hasText("Couldn't reach your NAS", substring = true)
        compose.onAllNodes(failed).onFirst().assertExists()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("Connect"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Every device you set up", substring = true).assertDoesNotExist()
        captureScreen("devices_nas_auto_failed")
    }

    @Test
    fun `a stored NAS failure goes once automatic NAS sync is turned off`() {
        graph.settings.setSmbConfig("nas.example.com", "share", "", "u", "p")
        graph.lastNasSync.record(NasSync.Result.Failed(ConnectException()))
        graph.settings.setNasAutoSync(false)
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Couldn't reach your NAS", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Every device you set up", substring = true).assertExists()
    }
}
