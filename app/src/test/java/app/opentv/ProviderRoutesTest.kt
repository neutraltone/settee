/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.settings.SettingsMemory
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every way into adding a provider reaches the page first run opens on, through the app's real
 * provider routes. The main screen is a stand-in: Live TV's empty state hands its Add to
 * [openAddProvider], as MainActivity wires it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderRoutesTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun setApp(start: String) {
        val sources = SourcesViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                providerRoutes(nav, sources)
                settingsRoutes(nav, SettingsMemory())
                composable(Routes.MAIN) { Text("Main", SetteeType.Body) }
            }
        }
    }

    private fun onThePage() {
        compose.onNodeWithText("Add your provider").assertExists()
        compose.onNodeWithText("Xtream login").assertExists()
        assertThat(compose.runOnIdle { nav.currentDestination?.route }).isEqualTo(Routes.ADD_SOURCE)
    }

    @Test
    fun `first run opens on the page`() {
        setApp(start = Routes.ADD_SOURCE)

        onThePage()
    }

    @Test
    fun `Add a provider in Settings opens the same page, and Back returns to Settings`() {
        setApp(start = Routes.SETTINGS)

        compose.onNodeWithText("Add a provider").performClick()

        onThePage()
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentDestination?.route }).isEqualTo(Routes.SETTINGS)
    }

    @Test
    fun `Live TV's Add opens the same page`() {
        setApp(start = Routes.MAIN)

        compose.runOnIdle { nav.openAddProvider() }

        onThePage()
    }
}
