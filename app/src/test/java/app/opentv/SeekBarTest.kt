/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

    /** An hour-long film [startMs] in, whose bar shows wherever the last scrub got to. */
    private fun showBar(startMs: Long = TEN_MINUTES) = compose.setTvContent {
        var shown by remember { mutableLongStateOf(startMs) }
        SeekBar(
            positionMs = shown,
            durationMs = AN_HOUR,
            onScrub = { to ->
                shown = to
                events += "scrub $to"
            },
            onScrubEnd = { events += "end" },
            onPlayPause = { events += "play/pause" },
            modifier = Modifier.padding(48.dp).width(600.dp).testTag(BAR),
        )
    }

    private fun bar() = compose.onNodeWithTag(BAR)

    private fun shownPosition() =
        bar().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current.toLong()

    @Test
    fun `Right moves the bar ten seconds on and Left moves it back, each finishing as the key comes up`() {
        showBar()
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionRight) }
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES + 10_000)

        bar().performKeyInput { pressKey(Key.DirectionLeft) }
        bar().performKeyInput { pressKey(Key.DirectionLeft) }
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES - 10_000)
        assertThat(events).containsExactly(
            "scrub ${TEN_MINUTES + 10_000}",
            "end",
            "scrub $TEN_MINUTES",
            "end",
            "scrub ${TEN_MINUTES - 10_000}",
            "end",
        ).inOrder()
    }

    @Test
    fun `with no length known yet, Left and Right leave it where it is rather than seeking to the start`() {
        compose.setTvContent {
            SeekBar(TEN_MINUTES, 0, { events += "scrub $it" }, { events += "end" }, {}, Modifier.testTag(BAR))
        }
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionRight) }
        bar().performKeyInput { pressKey(Key.DirectionLeft) }

        assertThat(events).isEmpty()
        bar().assertIsFocused()
    }

    @Test
    fun `OK plays and pauses without moving the bar`() {
        showBar()
        bar().requestFocus()

        bar().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(events).containsExactly("play/pause")
        assertThat(shownPosition()).isEqualTo(TEN_MINUTES)
    }

    @Test
    fun `the key-up of the press that brought the Highlight to the bar finishes nothing`() {
        compose.setTvContent {
            Row(Modifier.padding(48.dp)) {
                Neighbour("left", Modifier)
                SeekBar(TEN_MINUTES, AN_HOUR, {
                    events += "scrub"
                }, { events += "end" }, {}, Modifier.width(600.dp).testTag(BAR))
            }
        }
        compose.onNodeWithTag("left").requestFocus()

        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        bar().assertIsFocused()
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }

        assertThat(events).isEmpty()
    }

    /** A button as wide as the bar: Compose's search weighs how far apart centres are, not just edges. */
    @Composable
    private fun Neighbour(tag: String, modifier: Modifier = Modifier.width(600.dp)) {
        HighlightSurface(onClick = {}, style = HighlightStyle.Button, modifier = modifier.testTag(tag)) {
            Text(tag, SetteeType.Body)
        }
    }

    @Test
    fun `Up and Down leave the bar for what's above and below it`() {
        compose.setTvContent {
            Column(Modifier.padding(48.dp)) {
                Neighbour("above")
                SeekBar(TEN_MINUTES, AN_HOUR, {}, {}, {}, Modifier.width(600.dp).testTag(BAR))
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
    }

    @Test
    fun `a key let go after the Highlight left the bar is forgotten, and the next press starts from what it shows`() {
        var shown by mutableLongStateOf(TEN_MINUTES)
        compose.setTvContent {
            Column(Modifier.padding(48.dp)) {
                SeekBar(
                    positionMs = shown,
                    durationMs = AN_HOUR,
                    onScrub = { events += "scrub $it" },
                    onScrubEnd = { events += "end" },
                    onPlayPause = {},
                    modifier = Modifier.width(600.dp).testTag(BAR),
                )
                Neighbour("below")
            }
        }
        bar().requestFocus()

        compose.onRoot().performKeyInput { keyDown(Key.DirectionRight) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { keyUp(Key.DirectionRight) }
        // Meanwhile the caller dropped that seek, so the bar is back where playback is.
        shown = TEN_MINUTES
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        bar().assertIsFocused()
        events.clear()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }

        assertThat(events).containsExactly("scrub ${TEN_MINUTES + 10_000}", "end").inOrder()
    }

    @Test
    fun `holding Right speeds up, to 30 seconds a repeat after one second and a minute after three`() {
        showBar(startMs = 0)
        bar().requestFocus()

        bar().performKeyInput {
            keyDown(Key.DirectionRight)
            advanceEventTime(3_500)
        }
        val steps = events.filter { it.startsWith("scrub") }
            .map { it.removePrefix("scrub ").toLong() }
            .zipWithNext { a, b -> b - a }

        assertThat(events.first()).isEqualTo("scrub 10000")
        assertThat(steps.first()).isEqualTo(10_000)
        assertThat(steps).contains(30_000L)
        assertThat(steps.last()).isEqualTo(60_000)
        assertThat(events).doesNotContain("end")

        bar().performKeyInput { keyUp(Key.DirectionRight) }
        assertThat(events.last()).isEqualTo("end")
    }

    @Test
    fun `a finger on the bar scrubs to where it is, counts as dragging while it rests, and lifting it finishes`() {
        val touches = MutableInteractionSource()
        var dragging = false
        compose.setTvContent {
            dragging = touches.collectIsDraggedAsState().value
            SeekBar(
                positionMs = 0,
                durationMs = AN_HOUR,
                onScrub = { events += "scrub $it" },
                onScrubEnd = { events += "end" },
                onPlayPause = { events += "play/pause" },
                interactionSource = touches,
                modifier = Modifier.padding(48.dp).width(600.dp).testTag(BAR),
            )
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
        assertThat(events.last()).isEqualTo("end")
        assertThat(events).doesNotContain("play/pause")
        assertThat(dragging).isFalse()
    }

    private companion object {
        const val BAR = "bar"
        const val TEN_MINUTES = 600_000L
        const val AN_HOUR = 3_600_000L
    }
}
