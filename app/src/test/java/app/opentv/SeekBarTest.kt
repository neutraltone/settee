/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.opentv.ui.design.SeekBar
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
@OptIn(ExperimentalTestApi::class)
class SeekBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    /**
     * An hour-long film [startMs] in, whose bar shows wherever the last scrub got to and goes back to
     * where it was picked up when put down without a jump, as the player's does.
     */
    private fun showBar(startMs: Long = TEN_MINUTES, length: Long = AN_HOUR) = compose.setTvContent {
        TestBar(startMs, length, Modifier.padding(48.dp).width(600.dp).testTag(BAR))
    }

    @Composable
    private fun TestBar(startMs: Long, length: Long, modifier: Modifier, touches: MutableInteractionSource? = null) {
        var playing by remember { mutableLongStateOf(startMs) }
        var seekTo by remember { mutableStateOf<Long?>(null) }
        SeekBar(
            positionMs = seekTo ?: playing,
            durationMs = length,
            pickedUp = seekTo != null,
            onPickUp = {
                seekTo = playing
                events += "pick up"
            },
            onScrub = { to ->
                seekTo = to
                events += "scrub $to"
            },
            onPutDown = { jump ->
                if (jump) seekTo?.let { playing = it }
                seekTo = null
                events += if (jump) "jump" else "put back"
            },
            timeLabel = { "at $it" },
            interactionSource = touches,
            modifier = modifier,
        )
    }

    private fun bar() = compose.onNodeWithTag(BAR)

    private fun shownPosition() =
        bar().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current.toLong()

    /** A button as wide as the bar: Compose's search weighs how far apart centres are, not just edges. */
    @Composable
    private fun Neighbour(tag: String, modifier: Modifier = Modifier.width(600.dp)) {
        HighlightSurface(onClick = {}, style = HighlightStyle.Button, modifier = modifier.testTag(tag)) {
            Text(tag, SetteeType.Body)
        }
    }

    @Test
    fun `Left and Right leave the bar for what's beside it, without moving it`() {
        compose.setTvContent {
            Row(Modifier.padding(48.dp)) {
                Neighbour("left", Modifier)
                TestBar(TEN_MINUTES, AN_HOUR, Modifier.width(600.dp).testTag(BAR))
                Neighbour("right", Modifier)
            }
        }
        bar().requestFocus()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithTag("left").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("right").assertIsFocused()

        assertThat(events).isEmpty()
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `OK picks the bar up, and then Right moves it ten seconds a press and Left moves it back`() {
        showBar()
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("at $TEN_MINUTES").assertExists()
        bar().performKeyInput { pressKey(Key.DirectionRight) }
        bar().performKeyInput { pressKey(Key.DirectionRight) }
        bar().performKeyInput { pressKey(Key.DirectionLeft) }

        assertThat(shownPosition()).isEqualTo(TEN_MINUTES + 10_000)
        compose.onNodeWithText("at ${TEN_MINUTES + 10_000}").assertExists()
        assertThat(events).containsExactly(
            "pick up",
            "scrub ${TEN_MINUTES + 10_000}",
            "scrub ${TEN_MINUTES + 20_000}",
            "scrub ${TEN_MINUTES + 10_000}",
        ).inOrder()
        bar().assertIsFocused()
    }

    @Test
    fun `OK on a picked-up bar puts it down there, and the Highlight stays on it`() {
        showBar()
        bar().requestFocus()
        bar().performKeyInput { pressKey(Key.DirectionCenter) }
        bar().performKeyInput { pressKey(Key.DirectionRight) }

        bar().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(events.last()).isEqualTo("jump")
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES + 10_000)
        compose.onNodeWithText("at ${TEN_MINUTES + 10_000}").assertDoesNotExist()
        bar().assertIsFocused()
    }

    @Test
    fun `Back on a picked-up bar puts it back where it started, and goes no further`() {
        var backHeard = false
        compose.setTvContent {
            Box(
                Modifier.onKeyEvent {
                    if (it.key == Key.Back) backHeard = true
                    false
                },
            ) {
                TestBar(TEN_MINUTES, AN_HOUR, Modifier.padding(48.dp).width(600.dp).testTag(BAR))
            }
        }
        bar().requestFocus()
        bar().performKeyInput { pressKey(Key.DirectionCenter) }
        bar().performKeyInput { pressKey(Key.DirectionRight) }

        bar().performKeyInput { pressKey(Key.Back) }

        assertThat(events.last()).isEqualTo("put back")
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES)
        assertThat(backHeard).isFalse()
        bar().assertIsFocused()

        bar().performKeyInput { pressKey(Key.Back) }
        assertThat(backHeard).isTrue()
    }

    @Test
    fun `with no length known yet, OK doesn't pick it up, since there's nowhere to move it`() {
        showBar(length = 0)
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(events).isEmpty()
    }

    @Test
    fun `Up and Down leave the bar when it's down, and not while it's picked up`() {
        compose.setTvContent {
            Column(Modifier.padding(48.dp)) {
                Neighbour("above")
                TestBar(TEN_MINUTES, AN_HOUR, Modifier.width(600.dp).testTag(BAR))
                Neighbour("below")
            }
        }
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("below").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        bar().assertIsFocused()
        bar().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("above").assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        bar().performKeyInput { pressKey(Key.DirectionCenter) }
        bar().performKeyInput { pressKey(Key.DirectionDown) }
        bar().performKeyInput { pressKey(Key.DirectionUp) }
        bar().assertIsFocused()
    }

    @Test
    fun `holding Right speeds up, to 30 seconds a repeat after one second and a minute after three`() {
        showBar(startMs = 0)
        bar().requestFocus()
        bar().performKeyInput { pressKey(Key.DirectionCenter) }

        bar().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(3_500)
            keyUp(Key.DirectionRight)
        }
        val steps = events.filter { it.startsWith("scrub") }
            .map { it.removePrefix("scrub ").toLong() }
            .zipWithNext { a, b -> b - a }

        assertThat(events[1]).isEqualTo("scrub 10000")
        assertThat(steps.first()).isEqualTo(10_000)
        assertThat(steps).contains(30_000L)
        assertThat(steps.last()).isEqualTo(60_000)
        // Letting go of the key leaves it picked up: only OK or Back put it down.
        assertThat(events.last()).startsWith("scrub")
    }

    @Test
    fun `a finger on the bar scrubs to where it is, counts as dragging while it rests, and lifting it jumps there`() {
        val touches = MutableInteractionSource()
        var dragging = false
        compose.setTvContent {
            dragging = touches.collectIsDraggedAsState().value
            TestBar(0, AN_HOUR, Modifier.padding(48.dp).width(600.dp).testTag(BAR), touches)
        }

        bar().performTouchInput {
            down(center)
            moveBy(Offset(width / 4f, 0f))
        }
        compose.waitForIdle()
        val scrubs = events.map { it.removePrefix("scrub ").toLong() }
        // Half way then three quarters, give or take the thumb's inset at each end.
        assertThat(scrubs.first()).isWithin(AN_HOUR / 20).of(AN_HOUR / 2)
        assertThat(scrubs.last()).isWithin(AN_HOUR / 20).of(AN_HOUR * 3 / 4)
        assertThat(dragging).isTrue()

        bar().performTouchInput { up() }
        compose.waitForIdle()
        assertThat(events.last()).isEqualTo("jump")
        assertThat(events).doesNotContain("pick up")
        assertThat(dragging).isFalse()
    }

    private companion object {
        const val BAR = "bar"
        const val TEN_MINUTES = 600_000L
        const val AN_HOUR = 3_600_000L
    }
}
