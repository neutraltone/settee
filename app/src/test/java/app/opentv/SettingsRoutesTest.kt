/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings is one route, and the rows that open something bigger than a row open its own screen,
 * through the app's real routes. Back comes back to the row that opened it (#168).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRoutesTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun setApp(memory: SettingsMemory) {
        val sources = SourcesViewModel(app)
        compose.setTvContent {
            view = LocalView.current
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.SETTINGS) {
                settingsRoutes(nav, memory)
                providerRoutes(nav, sources)
                composable(Routes.CHANNELS) { Text("Channel manager", SetteeType.Body) }
                composable(Routes.MAIN) { Text("Main", SetteeType.Body) }
            }
        }
    }

    private fun route() = compose.runOnIdle { nav.currentDestination?.route }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun openFrom(row: String) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(row)).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText(row).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun backReturnsTo(row: String) {
        Espresso.pressBack()
        assertThat(route()).isEqualTo(Routes.SETTINGS)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(row) and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `Add a provider opens the page first run opens on, and Back returns to its row`() {
        setApp(SettingsMemory(SettingsSection.Providers))

        openFrom("Add a provider")

        compose.onNodeWithText("Add your provider").assertExists()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        backReturnsTo("Add a provider")
    }

    @Test
    fun `a provider's row opens Edit provider for that provider`() {
        val id = runBlocking {
            ServiceLocator.get(app).sourceRepository.save(
                Source(name = "Routes 168", kind = SourceKind.M3U, url = "http://127.0.0.1:9/list.m3u"),
            )
        }
        setApp(SettingsMemory(SettingsSection.Providers))

        openFrom("Routes 168")

        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Edit provider")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(compose.runOnIdle { nav.currentBackStackEntry?.arguments?.getString("sourceId") })
            .isEqualTo(id.toString())
    }

    @Test
    fun `Show, hide and star channels opens the channel manager, and Back returns to its row`() {
        setApp(SettingsMemory(SettingsSection.Channels))

        openFrom("Show, hide and star channels")

        assertThat(route()).isEqualTo(Routes.CHANNELS)
        backReturnsTo("Show, hide and star channels")
    }

    @Test
    fun `Locked categories opens its own screen, and Back returns to its row`() {
        val settings = ServiceLocator.get(app).settings
        settings.clearPin()
        setApp(SettingsMemory(SettingsSection.Parental))

        openFrom("Locked categories")

        assertThat(route()).isEqualTo(Routes.LOCKED_CATEGORIES)
        compose.onNode(hasText("Switch a category on to hide it.")).assertExists()
        backReturnsTo("Locked categories")
    }

    @Test
    fun `About's Fonts row opens both font licences on a screen of their own, and Back returns to it`() {
        setApp(SettingsMemory(SettingsSection.About))

        openFrom("Fonts")

        assertThat(route()).isEqualTo(Routes.FONT_LICENCES)
        compose.onNode(hasText("Young Serif 3.003")).assertExists()
        compose.onNode(hasText("Alegreya Sans 2.004")).assertExists()
        assertThat(
            compose.onAllNodes(hasText("SIL OPEN FONT LICENSE", substring = true)).fetchSemanticsNodes(),
        ).hasSize(2)
        captureScreen("font_licences")
        backReturnsTo("Fonts")
        compose.onNode(isFocused()).assertTextContains("Fonts", substring = true)
    }
}
