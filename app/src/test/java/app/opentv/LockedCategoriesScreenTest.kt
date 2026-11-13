/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import app.opentv.ui.design.SidebarEntry
import app.opentv.ui.settings.LockedCategoriesContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Locked categories: every live category's Parental Lock switch, on a screen of its own (#267). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockedCategoriesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    /** In provider order, as the pane listed them: not alphabetical, so a sort would show. */
    private val categories = listOf(
        SidebarEntry("news", "News"),
        SidebarEntry("kids", "Kids"),
        SidebarEntry("adult", "Adult"),
        SidebarEntry("sport", "Sport"),
    )

    private var locked by mutableStateOf(setOf<String>())
    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private var left = 0

    private fun show(entries: List<SidebarEntry>? = categories) = compose.setTvContent {
        view = LocalView.current
        LockedCategoriesContent(
            categories = entries,
            locked = locked,
            onLock = { key, lock ->
                toggles += key to lock
                locked = if (lock) locked + key else locked - key
            },
            onBack = { left++ },
        )
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun switchedOn(on: Boolean) =
        SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(on))

    /** The category rows' labels, top to bottom. */
    private fun rowLabels(): List<String> = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } }
        .filter { label -> categories.any { it.label == label } }

    @Test
    fun `opens on the first category, listing every one in provider order`() {
        show()

        highlighted().assertHighlightIs("News")
        assertThat(rowLabels()).containsExactly("News", "Kids", "Adult", "Sport").inOrder()
        assertThat(device.highlighted()).isFalse()
        captureScreen("locked_categories_initial")
    }

    @Test
    fun `OK on a category locks it and OK again unlocks it, and the row stays where it was`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        highlighted().assertHighlightIs("Adult")

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(toggles).containsExactly("adult" to true)
        compose.onNode(hasText("Adult") and hasClickAction()).assert(switchedOn(true))
        compose.onNode(hasText("News") and hasClickAction()).assert(switchedOn(false))
        assertThat(rowLabels()).containsExactly("News", "Kids", "Adult", "Sport").inOrder()
        highlighted().assertHighlightIs("Adult")
        captureScreen("locked_categories_locked")

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(toggles).containsExactly("adult" to true, "adult" to false).inOrder()
        compose.onNode(hasText("Adult") and hasClickAction()).assert(switchedOn(false))
    }

    @Test
    fun `while the categories are still being read it says nothing, then opens on the first one`() {
        var loaded by mutableStateOf<List<SidebarEntry>?>(null)
        compose.setTvContent {
            view = LocalView.current
            LockedCategoriesContent(categories = loaded, locked = locked, onLock = { _, _ -> }, onBack = {})
        }

        compose.onNodeWithText("No categories yet", substring = true).assertDoesNotExist()

        loaded = categories

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("News") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(device.highlighted()).isFalse()
    }

    @Test
    fun `with no categories it says so`() {
        show(entries = emptyList())

        compose.onNodeWithText("No categories yet", substring = true).assertExists()
        captureScreen("locked_categories_empty")
    }

    @Test
    fun `Up from the first category reaches Find a category, and Down comes back to the list`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(device.highlighted()).isTrue()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(device.highlighted()).isFalse()
        highlighted().assertHighlightIs("News")
    }

    @Test
    fun `Find a category narrows the list by name, whatever the case, and counts what's showing`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER)

        device.type("SPO")

        compose.onNode(hasText("Sport") and hasClickAction()).assertExists()
        compose.onAllNodesWithText("News").assertCountEquals(0)
        compose.onAllNodesWithText("Adult").assertCountEquals(0)
        compose.onNodeWithText("1 of 4").assertExists()
        captureScreen("locked_categories_find")
        device.close()
    }

    @Test
    fun `only the categories on screen are built`() {
        val many = (1..400).map { SidebarEntry("c$it", "Category $it") }
        show(entries = many)

        compose.onNode(hasText("Category 1") and hasClickAction()).assertExists()
        compose.onAllNodesWithText("Category 400").assertCountEquals(0)
    }

    @Test
    fun `Back leaves the screen`() {
        show()

        Espresso.pressBack()

        compose.runOnIdle { assertThat(left).isEqualTo(1) }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHighlightIs(label: String) {
        assertIsFocused()
        assert(hasText(label))
    }
}
