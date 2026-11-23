/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind.BECAUSE_YOU_WATCHED
import app.opentv.data.repo.HomeRowKind.CONTINUE_WATCHING
import app.opentv.data.repo.HomeRowKind.FAVOURITE_CHANNELS
import app.opentv.data.repo.HomeRowKind.FAVOURITE_MOVIES
import app.opentv.data.repo.HomeRowKind.FAVOURITE_SHOWS
import app.opentv.data.repo.HomeRowKind.RECENT_MOVIES
import app.opentv.data.repo.HomeRowKind.RECENT_SHOWS
import app.opentv.ui.design.ListRow
import app.opentv.ui.settings.HomeRowsContent
import app.opentv.ui.settings.homeRowGripTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → Display → Home rows: moving, hiding and restoring Home's rows for a Profile (#212). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeRowsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    /** The Profile's arrangement as the store holds it: the page saves into it. */
    private var arrangement by mutableStateOf(HomeArrangement.USUAL)

    /** How many times the page saved. */
    private var saves = 0

    /** How many times the page asked to leave. */
    private var left = 0

    /** Every arrangement the page saved, in order. */
    private val saved = mutableListOf<HomeArrangement>()

    /** When true, the store hasn't caught up yet: saves are heard but not handed back to the page. */
    private var storeLags = false

    private val usualRows =
        listOf(
            CONTINUE_WATCHING,
            BECAUSE_YOU_WATCHED,
            RECENT_SHOWS,
            RECENT_MOVIES,
            FAVOURITE_SHOWS,
            FAVOURITE_MOVIES,
            FAVOURITE_CHANNELS,
        )

    // A row to the left stands in for Settings' side menu, which Left must not reach with a row in hand.
    private fun show(initial: HomeArrangement = HomeArrangement.USUAL) {
        arrangement = initial
        compose.setTvContent {
            view = LocalView.current
            Row {
                ListRow("Side menu", onClick = {}, modifier = Modifier.width(200.dp))
                HomeRowsContent(
                    arrangement = arrangement,
                    onArrange = {
                        saved += it
                        if (!storeLags) arrangement = it
                        saves++
                    },
                    onBack = { left++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun assertOnScreenInOrder(vararg labels: String) =
        labels.toList().zipWithNext { above, below -> assertThat(topOf(above)).isLessThan(topOf(below)) }

    private val hint = "Up and down to move, select to place"

    @Test
    fun `the page opens on the Live Preview, then the rows in the Profile's order, then Restore`() {
        show(HomeArrangement.USUAL.withShown(RECENT_MOVIES, false))

        highlighted().assertTextContains("Live Preview")
        assertOnScreenInOrder(
            "Live Preview",
            "Continue watching",
            "Because you watched",
            "Recently added shows",
            "Recently added movies",
            "Favourite shows",
            "Favourite movies",
            "Favourite channels",
            "Restore the usual order",
        )
        compose.onNode(hasText("Recently added movies")).assertTextContains("Hidden")
        compose.onNode(hasText("Favourite shows")).assertTextContains("Shown")
        captureScreen("home_rows_initial")
    }

    @Test
    fun `Select, Down, Down, Select moves a row two places and saves the new order`() {
        show()

        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(hint).assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        captureScreen("home_rows_lifted")
        assertThat(saves).isEqualTo(0)

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(arrangement.rows).containsExactly(
            BECAUSE_YOU_WATCHED,
            RECENT_SHOWS,
            CONTINUE_WATCHING,
            RECENT_MOVIES,
            FAVOURITE_SHOWS,
            FAVOURITE_MOVIES,
            FAVOURITE_CHANNELS,
        ).inOrder()
        assertThat(saves).isEqualTo(1)
        highlighted().assertTextContains("Continue watching")
        compose.onNodeWithText(hint).assertDoesNotExist()
        assertOnScreenInOrder("Recently added shows", "Continue watching", "Recently added movies")
    }

    @Test
    fun `Back with a row lifted puts it back where it was and stays on the page`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)

        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(left).isEqualTo(0)
        assertThat(saves).isEqualTo(0)
        assertThat(arrangement.rows).isEqualTo(usualRows)
        assertOnScreenInOrder("Continue watching", "Recently added shows", "Recently added movies")
        highlighted().assertTextContains("Continue watching")
        compose.onNodeWithText(hint).assertDoesNotExist()

        // With nothing lifted, Back leaves.
        Espresso.pressBack()
        assertThat(left).isEqualTo(1)
    }

    @Test
    fun `a lifted row stops at the first and last Home Row, never reaching the Live Preview or Restore`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        press(KeyEvent.KEYCODE_DPAD_UP)
        highlighted().assertTextContains("Continue watching")
        assertOnScreenInOrder("Live Preview", "Continue watching", "Recently added shows")

        repeat(8) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        highlighted().assertTextContains("Continue watching")
        assertOnScreenInOrder("Favourite channels", "Continue watching", "Restore the usual order")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(arrangement.rows.last()).isEqualTo(CONTINUE_WATCHING)
    }

    @Test
    fun `Left and Right do nothing while a row is lifted`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        highlighted().assertTextContains("Continue watching")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Continue watching")
        compose.onNodeWithText(hint).assertExists()
    }

    @Test
    fun `a row's options turn Show on Home off and on, and it keeps its place`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        highlighted().assertTextContains("Recently added shows")

        press(KeyEvent.KEYCODE_MENU)
        val showOnHome = hasText("Show on Home") and hasAnyAncestor(isDialog())
        compose.onAllNodes(showOnHome).onFirst().assertExists()
        compose.onNode(showOnHome).assertTextContains("On")
        captureScreen("home_rows_show_on_home_panel")

        compose.onNode(showOnHome).performClick()
        compose.waitForIdle()
        assertThat(arrangement.hidden).containsExactly(RECENT_SHOWS)
        assertThat(arrangement.rows).isEqualTo(usualRows)
        compose.onNode(showOnHome).assertTextContains("Off")

        compose.onNode(showOnHome).performClick()
        compose.waitForIdle()
        assertThat(arrangement.hidden).isEmpty()
        assertThat(arrangement.rows).isEqualTo(usualRows)
    }

    @Test
    fun `a hidden row can still be moved, and stays hidden`() {
        show(HomeArrangement.USUAL.withShown(CONTINUE_WATCHING, false))

        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(arrangement.rows.take(2)).containsExactly(BECAUSE_YOU_WATCHED, CONTINUE_WATCHING).inOrder()
        assertThat(arrangement.hidden).containsExactly(CONTINUE_WATCHING)
    }

    @Test
    fun `Select on the Live Preview turns it off`() {
        show()

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(arrangement.livePreview).isFalse()
        assertThat(arrangement.rows).isEqualTo(usualRows)
    }

    @Test
    fun `Restore the usual order puts back the order, every row and the Live Preview`() {
        show(HomeArrangement(usualRows.reversed(), hidden = setOf(FAVOURITE_SHOWS), livePreview = false))

        // A click is a tap where the row is, so bring it into view first.
        compose.onNodeWithText("Restore the usual order").performScrollTo().performClick()
        compose.waitForIdle()

        assertThat(arrangement).isEqualTo(HomeArrangement(usualRows, hidden = emptySet(), livePreview = true))
    }

    @Test
    fun `on touch, dragging a row's grip moves it and letting go saves`() {
        show()
        val grip = compose.onNodeWithTag(homeRowGripTag(FAVOURITE_MOVIES), useUnmergedTree = true)
        val distance = topOf("Favourite movies") - topOf("Continue watching")
        val by = with(compose.density) { distance.toPx() }

        grip.performTouchInput {
            down(center)
            // In steps, as a finger moves, past the touch slop before the first.
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -by / DRAG_STEPS)) }
        }
        compose.waitForIdle()
        assertThat(saves).isEqualTo(0)
        grip.performTouchInput { up() }
        compose.waitForIdle()

        assertThat(arrangement.rows).containsExactly(
            FAVOURITE_MOVIES,
            CONTINUE_WATCHING,
            BECAUSE_YOU_WATCHED,
            RECENT_SHOWS,
            RECENT_MOVIES,
            FAVOURITE_SHOWS,
            FAVOURITE_CHANNELS,
        ).inOrder()
        assertThat(saves).isEqualTo(1)
    }

    @Test
    fun `the hint sits under the title, above the rows, whether or not a row is lifted`() {
        show()
        val resting = "Select a row to move it, or press options to hide it"
        compose.onNodeWithText(resting).assertIsDisplayed()
        val restingTop = topOf(resting)
        assertThat(restingTop).isLessThan(topOf("Live Preview"))

        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithText(resting).assertDoesNotExist()
        compose.onNodeWithText(hint).assertIsDisplayed()
        // Same place, so lifting a row doesn't move every row under it.
        assertThat(topOf(hint)).isEqualTo(restingTop)
    }

    @Test
    fun `two quick presses on the Live Preview turn it off and back on, even before the store catches up`() {
        show()
        storeLags = true

        press(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(saved.map { it.livePreview }).containsExactly(false, true).inOrder()
    }

    @Test
    fun `two quick presses on Show on Home hide the row and show it again, even before the store catches up`() {
        show()
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU)
        val showOnHome = hasText("Show on Home") and hasAnyAncestor(isDialog())
        compose.onAllNodes(showOnHome).onFirst().assertExists()
        storeLags = true

        compose.onNode(showOnHome).performClick()
        compose.onNode(showOnHome).performClick()
        compose.waitForIdle()

        assertThat(saved.map { it.hidden }).containsExactly(setOf(CONTINUE_WATCHING), emptySet<Any>()).inOrder()
    }

    @Test
    fun `on touch, a drag that's cancelled puts the row back and saves nothing`() {
        show()
        val grip = compose.onNodeWithTag(homeRowGripTag(FAVOURITE_MOVIES), useUnmergedTree = true)
        val distance = topOf("Favourite movies") - topOf("Continue watching")
        val by = with(compose.density) { distance.toPx() }

        grip.performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, -by / DRAG_STEPS)) }
            cancel()
        }
        compose.waitForIdle()

        assertThat(saves).isEqualTo(0)
        assertThat(arrangement.rows).isEqualTo(usualRows)
        assertOnScreenInOrder("Favourite shows", "Favourite movies", "Favourite channels")
    }

    @Test
    fun `on touch, a long press on a row opens its options`() {
        show()

        compose.onNodeWithText("Favourite shows").performTouchInput { longClick() }

        val showOnHome = hasText("Show on Home") and hasAnyAncestor(isDialog())
        compose.onAllNodes(showOnHome).onFirst().assertExists()
    }

    private companion object {
        const val DRAG_STEPS = 20
    }
}
