/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * The player's seek bar, which takes the Highlight and is picked up before it moves (docs/LOOK.md,
 * Player). Resting, it leaves Left and Right to move along the row, and OK calls [onPickUp]. Picked
 * up ([pickedUp]), the thumb grows in the Highlight colour with [timeLabel]'s time over it, Left and
 * Right move it, and the Highlight can't leave: OK puts it down there (`onPutDown(jump = true)`) and
 * Back puts it down where it started (`onPutDown(jump = false)`), and Back goes no further.
 *
 * It's stateless: [positionMs] is what it shows, each press asks [onScrub] to show somewhere else,
 * and the caller holds whether it's picked up, so it can put it down itself (a timeout, a skip).
 *
 * A finger scrubs to wherever it is on the bar and jumps there as it lifts, with no picking up. While
 * it's down the bar emits a drag on [interactionSource], so the caller can tell a resting finger from
 * a bar left picked up.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    pickedUp: Boolean,
    onPickUp: () -> Unit,
    onScrub: (toMs: Long) -> Unit,
    onPutDown: (jump: Boolean) -> Unit,
    timeLabel: (positionMs: Long) -> String,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val scrub = remember { ScrubKeys() }
    val length = durationMs.coerceAtLeast(0L)
    val shown = positionMs.coerceIn(0L, length)
    val touches = interactionSource ?: remember { MutableInteractionSource() }
    val latestScrub by rememberUpdatedState(onScrub)
    val latestPutDown by rememberUpdatedState(onPutDown)
    val fraction = if (length > 0) shown.toFloat() / length else 0f
    HighlightSurface(
        onClick = {
            when {
                pickedUp -> onPutDown(true)
                // Until the length is known every target clamps to 0: there's nowhere to move it.
                length > 0L -> {
                    scrub.target = null
                    onPickUp()
                }
            }
        },
        style = HighlightStyle.Bar,
        modifier = modifier
            .scrubByTouch(length, touches, { latestScrub(it) }, { latestPutDown(true) })
            .backPutsDown(pickedUp, scrub, onPutDown)
            .scrubWhilePickedUp(pickedUp, scrub, positionMs, length, onScrub)
            // A key let go once the Highlight has gone is heard elsewhere, so the bar forgets it here.
            .onFocusChanged { if (!it.hasFocus) scrub.target = null }
            // Plain semantics, not progressSemantics, which adds a focus target of its own.
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = shown.toFloat(),
                    range = 0f..length.coerceAtLeast(1L).toFloat(),
                )
            },
    ) { highlighted ->
        SeekTrack(fraction = fraction, highlighted = highlighted, pickedUp = pickedUp)
        if (pickedUp) TimeBubble(timeLabel(shown), fraction, Modifier.matchParentSize())
    }
}

/**
 * Back on the picked-up bar puts it back, on the release like Back everywhere in the player. The press
 * and its release are both kept from the player, which would otherwise hide the controls or leave.
 */
private fun Modifier.backPutsDown(pickedUp: Boolean, scrub: ScrubKeys, onPutDown: (jump: Boolean) -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val back = event.key == Key.Back || event.key == Key.Escape
        when {
            !back -> false
            event.type == KeyEventType.KeyDown && pickedUp -> {
                scrub.backHeld = true
                true
            }
            event.type == KeyEventType.KeyUp && scrub.backHeld -> {
                scrub.backHeld = false
                if (pickedUp) onPutDown(false)
                true
            }
            else -> false
        }
    }

/**
 * Left and Right on the picked-up bar: each press or repeat asks to move on by [stepFor] how long the
 * key has been held. Up and Down stay here too. Resting, it leaves every key alone.
 */
private fun Modifier.scrubWhilePickedUp(
    pickedUp: Boolean,
    scrub: ScrubKeys,
    positionMs: Long,
    length: Long,
    onScrub: (Long) -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionRight -> 1
        Key.DirectionLeft -> -1
        Key.DirectionUp, Key.DirectionDown -> 0
        else -> return@onPreviewKeyEvent false
    }
    if (!pickedUp || direction == 0) return@onPreviewKeyEvent pickedUp
    if (event.type == KeyEventType.KeyDown && length > 0L) {
        // Repeats can come faster than frames, so a held key carries on from where it last asked to
        // be, not from positionMs, which may not have caught up.
        val from = scrub.target ?: positionMs
        val held = event.nativeKeyEvent.let { it.eventTime - it.downTime }
        val to = (from + direction * stepFor(held)).coerceIn(0L, length)
        scrub.target = to
        onScrub(to)
    }
    true
}

/** A finger: scrubs to where it is, and jumps there as it lifts, with a drag on [touches] meanwhile. */
private fun Modifier.scrubByTouch(
    length: Long,
    touches: MutableInteractionSource,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
): Modifier = pointerInput(length, touches) {
    val inset = (ThumbSize / 2).toPx()
    fun at(x: Float): Long {
        val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
        return (fraction * length).toLong()
    }
    awaitEachGesture {
        // Taken on the way in, so the surface's own click (picking up) never sees it.
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        down.consume()
        // As with the keys: with no length, wherever the finger is means 0.
        if (length <= 0L) return@awaitEachGesture
        val drag = DragInteraction.Start()
        touches.tryEmit(drag)
        onScrub(at(down.position.x))
        try {
            var change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            while (change != null && change.pressed) {
                change.consume()
                onScrub(at(change.position.x))
                change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            }
            change?.consume()
            onScrubEnd()
            touches.tryEmit(DragInteraction.Stop(drag))
        } catch (cancelled: CancellationException) {
            touches.tryEmit(DragInteraction.Cancel(drag))
            throw cancelled
        }
    }
}

/**
 * A plain holder: where Left or Right last asked the picked-up bar to be, or null before they have,
 * and whether a Back that went down on the picked-up bar is still to come up.
 */
private class ScrubKeys {
    var target: Long? = null
    var backHeld = false
}

/** How far one press or repeat moves the bar, by how long the key has been held. */
private fun stepFor(heldMillis: Long) = when {
    heldMillis >= FASTEST_AFTER_MILLIS -> FASTEST_STEP_MILLIS
    heldMillis >= FASTER_AFTER_MILLIS -> FASTER_STEP_MILLIS
    else -> STEP_MILLIS
}

@Composable
private fun SeekTrack(fraction: Float, highlighted: Boolean, pickedUp: Boolean) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(ThumbSize)
            .padding(horizontal = ThumbSize / 2),
    ) {
        val track = TrackHeight.toPx()
        val top = (size.height - track) / 2
        val corner = CornerRadius(track / 2)
        drawRoundRect(TrackColor, Offset(0f, top), Size(size.width, track), corner)
        drawRoundRect(SetteeColors.Accent, Offset(0f, top), Size(size.width * fraction, track), corner)
        // The thumb is how the bar shows it has the Highlight: the track itself is too thin to ring.
        // Picked up, it grows again and takes the Highlight colour.
        val thumb = when {
            pickedUp -> PickedUpThumbSize.toPx() / 2
            highlighted -> ThumbSize.toPx() / 2
            else -> track
        }
        drawCircle(
            if (highlighted && !pickedUp) SetteeColors.Text else SetteeColors.Accent,
            radius = thumb,
            center = Offset(size.width * fraction, size.height / 2),
        )
    }
}

/**
 * [text] in a coral bubble over the thumb, at [fraction] along the track, kept inside the bar's ends.
 * It hangs above the bar rather than taking room, so picking the bar up moves nothing else.
 */
@Composable
private fun TimeBubble(text: String, fraction: Float, modifier: Modifier) {
    Layout(
        content = {
            Text(
                text,
                SetteeType.Body,
                color = SetteeColors.OnAccent,
                maxLines = 1,
                modifier = Modifier
                    .background(SetteeColors.Accent, SetteeShapes.Pill)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val bubble = measurables.first().measure(Constraints())
        val inset = (ThumbSize / 2).toPx()
        val centre = inset + (constraints.maxWidth - 2 * inset) * fraction
        val x = (centre - bubble.width / 2f).roundToInt().coerceIn(
            0,
            (constraints.maxWidth - bubble.width).coerceAtLeast(0),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            bubble.place(x, -bubble.height - BubbleGap.roundToPx())
        }
    }
}

// How far presses move the bar, and how soon holding a key speeds it up. To adjust once tried on the Stick.
private const val STEP_MILLIS = 10_000L
private const val FASTER_STEP_MILLIS = 30_000L
private const val FASTEST_STEP_MILLIS = 60_000L
private const val FASTER_AFTER_MILLIS = 1_000L
private const val FASTEST_AFTER_MILLIS = 3_000L

private val TrackHeight = 4.dp
private val ThumbSize = 18.dp
private val PickedUpThumbSize = 26.dp
private val BubbleGap = 6.dp
private val TrackColor = Color.White.copy(alpha = 0.3f)
