/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import app.opentv.data.model.Programme
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.OptionsPanel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptionsPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var chosen: ItemOption? = null
    private var dismissed = 0

    private fun show(vararg options: ItemOption) = compose.setTvContent {
        OptionsPanel(
            title = "The Show",
            options = options.toList(),
            onChoose = { chosen = it },
            onDismiss = { dismissed++ },
        )
    }

    @Test
    fun `the panel shows only the options it is given, the first highlighted`() {
        show(ItemOption.MarkWatched, ItemOption.RemoveFromContinueWatching, ItemOption.AddFavourite)

        compose.onNodeWithText("Mark watched").assertIsFocused().assertIsInSidePanel()
        compose.onNodeWithText("Remove from Continue Watching").assertExists()
        compose.onNodeWithText("Add to Favourites").assertExists()
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.onNodeWithText("Remove from Favourites").assertDoesNotExist()
        captureScreen("options_panel")
    }

    @Test
    fun `back closes the panel without choosing`() {
        show(ItemOption.MarkWatched, ItemOption.AddFavourite)

        compose.pressBackOnPanel()

        assertThat(dismissed).isEqualTo(1)
        assertThat(chosen).isNull()
    }

    @Test
    fun `choosing an option reports which`() {
        show(ItemOption.MarkUnwatched, ItemOption.RemoveFavourite)

        compose.onNodeWithText("Remove from Favourites").performClick()

        assertThat(chosen).isEqualTo(ItemOption.RemoveFavourite)
    }

    @Test
    fun `a Select still held from opening the panel doesn't choose anything, and a new press does`() {
        show(ItemOption.Watch, ItemOption.HideChannel)
        val watch = compose.onNodeWithText("Watch", substring = true)

        watch.performKeyPress(selectKey(NativeKeyEvent.ACTION_DOWN, repeat = 3))
        watch.performKeyPress(selectKey(NativeKeyEvent.ACTION_UP))
        compose.waitForIdle()
        assertThat(chosen).isNull()

        watch.performKeyPress(selectKey(NativeKeyEvent.ACTION_DOWN))
        watch.performKeyPress(selectKey(NativeKeyEvent.ACTION_UP))
        compose.waitForIdle()
        assertThat(chosen).isEqualTo(ItemOption.Watch)
    }

    private fun selectKey(action: Int, repeat: Int = 0) =
        KeyEvent(NativeKeyEvent(0, 0, action, NativeKeyEvent.KEYCODE_DPAD_CENTER, repeat))

    @Test
    fun `Record whole series names the programme`() {
        show(ItemOption.RecordWholeSeries(programme("The News")))

        compose.onNodeWithText("Record whole series: The News").assertIsFocused()
    }

    @Test
    fun `a long label wraps rather than losing its end`() {
        val title = "The Great British Sewing Bee: The Christmas Special Extended Edition"
        show(ItemOption.RecordWholeSeries(programme(title)))

        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Record whole series: $title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }

        assertThat(layout.single().hasVisualOverflow).isFalse()
    }

    @Test
    fun `a list taller than the screen can be walked to its end with the remote`() {
        val programmes = (1..30).map { later("Programme $it") }
        show(*programmes.toTypedArray())

        repeat(programmes.size - 1) {
            compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }

        val last = compose.onNode(hasText("Programme 30", substring = true))
        last.assertIsFocused()
        last.assertIsDisplayed()
    }

    @Test
    fun `moving on to a new list highlights its first option`() {
        var options by mutableStateOf(listOf<ItemOption>(ItemOption.Watch, ItemOption.ScheduleLater))
        compose.setTvContent {
            OptionsPanel(title = "BBC One", options = options, onChoose = {}, onDismiss = {})
        }
        compose.onNodeWithText("Schedule a later programme").assertExists()

        options = listOf(later("Quiz"), later("Film"))

        compose.onNode(hasText("Quiz", substring = true)).assertIsFocused()
        captureScreen("options_panel_channel_schedule_later")
    }

    private fun later(title: String) = ItemOption.LaterProgramme(programme(title))

    private fun programme(title: String) =
        Programme(feedId = 1, epgChannelId = "bbc1", startUtcMillis = 0, endUtcMillis = 1, title = title)
}
