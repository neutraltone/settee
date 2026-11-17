/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat

/**
 * A title's page in a NavHost, as the app has it, beside a stand-in for wherever it leads (a person's
 * page, another title's, the player), so the page is disposed while that covers it and built again on
 * Back (#429). For the tests of Back to a film's and a show's page.
 */
@OptIn(ExperimentalTestApi::class)
internal class PageBack(private val compose: ComposeContentTestRule) {
    private lateinit var nav: NavHostController

    /** Shows [page], which calls the `leave` it's given to open whatever it leads to. */
    fun show(page: @Composable (leave: () -> Unit) -> Unit) {
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = PAGE) {
                composable(PAGE) { page { nav.navigate(ELSEWHERE) } }
                composable(ELSEWHERE) { Box(Modifier.fillMaxSize()) }
            }
        }
        settle()
    }

    /** The page places its highlight a moment after it's built. */
    fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    /** [press] in a side panel, a window of its own with its own highlight beside the page's. */
    fun pressInPanel(key: Key) {
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        settle()
    }

    /** OK on what's highlighted, which leaves the page. */
    fun openHighlighted() {
        press(Key.DirectionCenter)
        assertLeft()
    }

    fun assertLeft() = compose.runOnIdle { assertThat(nav.currentDestination?.route).isEqualTo(ELSEWHERE) }

    fun back() {
        compose.runOnIdle { nav.popBackStack() }
        settle()
    }

    fun clickable(text: String): SemanticsNodeInteraction = compose.onNode(isClickable(text))

    /** The text of what's highlighted, for a cover the test reached by moving rather than by name. */
    fun highlightedText(): String =
        compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()

    companion object {
        fun isClickable(text: String): SemanticsMatcher = hasText(text) and hasClickAction()

        private const val PAGE = "page"
        private const val ELSEWHERE = "elsewhere"
        private const val SETTLE_MILLIS = 500L
    }
}
