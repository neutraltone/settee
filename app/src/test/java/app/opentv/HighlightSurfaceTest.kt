/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightSurfaceTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    /** A surface whose slot says whether it's highlighted, so the test can read it. */
    @Composable
    private fun Item(name: String, style: HighlightStyle, withOptions: Boolean = true) {
        HighlightSurface(
            onClick = { events += "click $name" },
            style = style,
            modifier = Modifier.testTag(name),
            onOptions = if (withOptions) ({ events += "options $name" }) else null,
        ) { highlighted ->
            // Cards always have artwork or a fallback behind them, so this one does too.
            val ringed = style.highlighted.ring != null
            val art = if (ringed) SetteeColors.Raised else Color.Transparent
            Box(
                Modifier.size(width = 140.dp, height = 80.dp).background(art).padding(12.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    "$name ${if (highlighted) "highlighted" else "resting"}",
                    style = SetteeType.CardTitle,
                    color = if (highlighted && !ringed) SetteeColors.OnAccent else SetteeColors.Text,
                )
            }
        }
    }

    private fun showTwo(style: HighlightStyle, withOptions: Boolean = true) = compose.setTvContent {
        Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap)) {
            Item("A", style, withOptions)
            Item("B", style, withOptions)
        }
    }

    @Test
    fun `only the item with focus is highlighted, and the highlight follows the remote`() {
        showTwo(HighlightStyle.Card)

        compose.onNodeWithTag("A").requestFocus()
        compose.onNodeWithText("A highlighted").assertExists()
        compose.onNodeWithText("B resting").assertExists()

        compose.onNodeWithTag("A").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("A resting").assertExists()
        compose.onNodeWithText("B highlighted").assertExists()
    }

    @Test
    fun `OK on the remote chooses the item`() {
        showTwo(HighlightStyle.Row)

        compose.onNodeWithTag("B").requestFocus()
        compose.onNodeWithTag("B").performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(events).containsExactly("click B")
    }

    @Test
    fun `Menu on the remote opens the item's options without choosing it`() {
        showTwo(HighlightStyle.Card)

        compose.onNodeWithTag("A").requestFocus()
        compose.onNodeWithTag("A").performKeyInput { pressKey(Key.Menu) }

        assertThat(events).containsExactly("options A")
    }

    @Test
    fun `a long press by touch opens the item's options`() {
        showTwo(HighlightStyle.Card)

        compose.onNodeWithTag("B").performTouchInput { longClick() }

        assertThat(events).containsExactly("options B")
    }

    @Test
    fun `an item without options ignores Menu but still takes OK`() {
        showTwo(HighlightStyle.Button, withOptions = false)

        compose.onNodeWithTag("A").requestFocus()
        compose.onNodeWithTag("A").performKeyInput { pressKey(Key.Menu) }
        compose.onNodeWithTag("A").performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(events).containsExactly("click A")
    }

    @Test
    fun `each treatment, resting and highlighted`() {
        compose.setTvContent {
            Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Item("Card", HighlightStyle.Card)
                Item("First", HighlightStyle.FirstCard)
                Item("Row", HighlightStyle.Row)
                Item("Cell", HighlightStyle.Cell)
                Item("Button", HighlightStyle.Button)
            }
        }
        captureScreen("ds_highlight_surface_resting")

        for (name in listOf("Card", "First", "Row", "Cell", "Button")) {
            compose.onNodeWithTag(name).requestFocus()
            compose.onNodeWithText("$name highlighted").assertExists()
            captureScreen("ds_highlight_surface_${name.lowercase()}_highlighted")
        }
    }

    @Test
    fun `list-like items don't clip what their content draws past the edge, cards do`() {
        compose.setTvContent {
            Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(200.dp)) {
                for (style in listOf(HighlightStyle.Row, HighlightStyle.Card)) {
                    HighlightSurface(onClick = {}, style = style) {
                        Box(Modifier.size(100.dp)) {
                            // A badge hanging 20 dp off the right edge.
                            Box(Modifier.offset(x = 90.dp).size(30.dp).background(Color.Red))
                        }
                    }
                }
            }
        }

        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val dp = pixels.width / 960f
        fun at(x: Int, y: Int) = pixels[(x * dp).toInt(), (y * dp).toInt()]
        // Row starts at x = 48: its badge runs to 168, past its 148 edge, and shows.
        assertThat(at(160, 60)).isEqualTo(Color.Red)
        // Card starts at 348: its badge past 448 is cut off by the card's corners.
        assertThat(at(460, 60)).isNotEqualTo(Color.Red)
    }

    @Test
    fun `an item given focus the moment it appears is drawn highlighted`() {
        // As when the focused row is swapped for another in its place: the new one has focus before
        // anything that listens for focus events has started listening.
        var shown by mutableStateOf(false)
        val focus = FocusRequester()
        compose.setTvContent {
            if (shown) {
                Box(Modifier.focusRequester(focus)) { Item("Carry on", HighlightStyle.Button, withOptions = false) }
                SideEffect { focus.requestFocus() }
            }
        }

        compose.runOnIdle { shown = true }

        compose.onNodeWithText("Carry on highlighted").assertExists()
    }
}
