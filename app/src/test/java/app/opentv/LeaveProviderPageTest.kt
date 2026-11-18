/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where [leaveProviderPage] goes, with the app's route names. Each screen is a stand-in that only
 * says which screen it is (and, for the main screen, which tab it's on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LeaveProviderPageTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun setApp(start: String) = compose.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = start) {
            composable(Routes.MAIN) { entry ->
                val tab by entry.shellTab()
                BasicText("Main: $tab")
            }
            composable(Routes.SETTINGS) { BasicText("Settings") }
            composable(Routes.ADD_SOURCE) { BasicText("Add a provider") }
            composable(Routes.MOVIE_DETAIL) { BasicText("A movie") }
        }
    }

    private fun act(block: NavHostController.() -> Unit) = compose.runOnIdle { nav.block() }

    private fun page(): NavBackStackEntry = compose.runOnIdle { nav.currentBackStackEntry!! }

    private fun routes() = compose.runOnIdle { nav.backStackRoutes }

    @Test
    fun `the page Settee opened on goes to Home, as there's nothing under it`() {
        setApp(start = Routes.ADD_SOURCE)
        val page = page()

        act { leaveProviderPage(page) }

        compose.onNodeWithText("Main: HOME").assertExists()
        assertThat(routes()).containsExactly(Routes.MAIN)
    }

    @Test
    fun `the page goes back to where it was opened`() {
        setApp(start = Routes.MAIN)
        act {
            navigate(Routes.SETTINGS)
            navigate(Routes.ADD_SOURCE)
        }
        val page = page()

        act { leaveProviderPage(page) }

        compose.onNodeWithText("Settings").assertExists()
        assertThat(routes()).containsExactly(Routes.MAIN, Routes.SETTINGS).inOrder()
    }

    @Test
    fun `once the viewer has left the page, nothing moves`() {
        setApp(start = Routes.MAIN)
        act { navigate(Routes.ADD_SOURCE) }
        val page = page()
        act {
            popBackStack()
            navigate(Routes.movieDetail(9))
        }

        act { leaveProviderPage(page) }

        compose.onNodeWithText("A movie").assertExists()
        assertThat(routes()).containsExactly(Routes.MAIN, Routes.MOVIE_DETAIL).inOrder()
    }
}
