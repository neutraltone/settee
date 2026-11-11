/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/**
 * The player's seek bar, which takes the Highlight. Left and Right move it through the video and OK
 * calls [onPlayPause]: the bar keeps Left and Right for itself, so they never move along the row.
 *
 * It's stateless: [positionMs] is what it shows, and each press asks [onScrub] to show somewhere
 * else. [onScrubEnd] comes when the key that started a scrub comes up, which is when the caller seeks.
 * A key-up with no key-down on the bar (the press that brought the Highlight here) finishes nothing.
 *
 * A finger scrubs to wherever it is on the bar and finishes as it lifts. While it's down the bar
 * emits a drag on [interactionSource], so the caller can tell a resting finger from a lost key-up.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onScrub: (toMs: Long) -> Unit,
    onScrubEnd: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val scrub = remember { ScrubKeys() }
    val length = durationMs.coerceAtLeast(0L)
    val shown = positionMs.coerceIn(0L, length)
    val touches = interactionSource ?: remember { MutableInteractionSource() }
    val latestScrub by rememberUpdatedState(onScrub)
    val latestEnd by rememberUpdatedState(onScrubEnd)
    HighlightSurface(
        onClick = onPlayPause,
        style = HighlightStyle.Bar,
        modifier = modifier
            .scrubByTouch(length, touches, { latestScrub(it) }, { latestEnd() })
            .scrubByKeys(scrub, positionMs, length, onScrub, onScrubEnd)
            // A key let go once the Highlight has gone is heard elsewhere, so the bar forgets it
            // here: otherwise the next press would carry on from a seek the caller has dropped.
            .onFocusChanged { if (!it.hasFocus) scrub.target = null }
            // Plain semantics, not progressSemantics, which adds a focus target of its own.
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = shown.toFloat(),
                    range = 0f..length.coerceAtLeast(1L).toFloat(),
                )
            },
    ) { highlighted ->
        SeekTrack(fraction = if (length > 0) shown.toFloat() / length else 0f, highlighted = highlighted)
    }
}

/**
 * Left and Right: each press or repeat asks to move on by [stepFor] how long the key has been held,
 * and the key-up finishes the scrub, if the key went down here.
 */
private fun Modifier.scrubByKeys(
    scrub: ScrubKeys,
    positionMs: Long,
    length: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    val direction = when (event.key) {
        Key.DirectionRight -> 1
        Key.DirectionLeft -> -1
        else -> return@onPreviewKeyEvent false
    }
    // Until the length is known every target clamps to 0, and seeking there would restart the video.
    if (length <= 0L) return@onPreviewKeyEvent true
    if (event.type == KeyEventType.KeyDown) {
        // Repeats can come faster than frames, so a held key carries on from where it last asked to
        // be, not from positionMs, which may not have caught up.
        val from = scrub.target ?: positionMs
        val held = event.nativeKeyEvent.let { it.eventTime - it.downTime }
        val to = (from + direction * stepFor(held)).coerceIn(0L, length)
        scrub.target = to
        onScrub(to)
    } else if (event.type == KeyEventType.KeyUp && scrub.target != null) {
        scrub.target = null
        onScrubEnd()
    }
    true
}

/** A finger: scrubs to where it is, and finishes as it lifts, with a drag on [touches] meanwhile. */
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
        // Taken on the way in, so the surface's own click (play/pause) never sees it.
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
 * A plain holder: where the Left or Right that went down on the bar has asked to be, or null when no
 * such key is down.
 */
private class ScrubKeys {
    var target: Long? = null
}

/** How far one press or repeat moves the bar, by how long the key has been held. */
private fun stepFor(heldMillis: Long) = when {
    heldMillis >= FASTEST_AFTER_MILLIS -> FASTEST_STEP_MILLIS
    heldMillis >= FASTER_AFTER_MILLIS -> FASTER_STEP_MILLIS
    else -> STEP_MILLIS
}

@Composable
private fun SeekTrack(fraction: Float, highlighted: Boolean) {
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
        val thumb = if (highlighted) ThumbSize.toPx() / 2 else track
        drawCircle(
            if (highlighted) SetteeColors.Text else SetteeColors.Accent,
            radius = thumb,
            center = Offset(size.width * fraction, size.height / 2),
        )
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
private val TrackColor = Color.White.copy(alpha = 0.3f)
