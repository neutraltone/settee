/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * A screen: Settee's background, with [content] inside the screen margin (48 dp at the sides, 27 dp
 * top and bottom), clear of a TV's overscan.
 *
 * The background is solid, so nothing behind the screen shows through (docs/LOOK.md, Motion and
 * cost), except under a [ScreenFrameScope.VideoArea], where the video plays behind the window.
 */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable ScreenFrameScope.() -> Unit) {
    val frame = remember { FrameGeometry() }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { frame.origin = it.positionInRoot() }
            .drawBehind { drawBackground(frame.video?.translate(-frame.origin)) }
            .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom),
    ) {
        remember(frame) { ScreenFrameScope(frame) }.content()
    }
}

/** Where the frame and its video area are, in the root's coordinates. */
@Stable
internal class FrameGeometry {
    var origin by mutableStateOf(Offset.Zero)
    var video by mutableStateOf<Rect?>(null)
}

/**
 * The background around [hole], as up to four rectangles. Cheaper than clearing the hole with a
 * blend mode, which would need the whole screen drawn to an offscreen layer every frame.
 */
private fun DrawScope.drawBackground(hole: Rect?) {
    val colour = SetteeColors.Background
    if (hole == null) {
        drawRect(colour)
        return
    }
    val h = hole.intersect(Rect(Offset.Zero, size))
    // Off the frame (scrolled away, mid-transition): nothing to leave clear. Drawing the strips
    // anyway would give negative sizes, which Skia flips onto the wrong side.
    if (h.isEmpty) {
        drawRect(colour)
        return
    }
    drawRect(colour, Offset.Zero, Size(size.width, h.top))
    drawRect(colour, Offset(0f, h.bottom), Size(size.width, size.height - h.bottom))
    drawRect(colour, Offset(0f, h.top), Size(h.left, h.height))
    drawRect(colour, Offset(h.right, h.top), Size(size.width - h.right, h.height))
}

/**
 * What a [ScreenFrame]'s content can do: run a row to the edge, and show video. Deliberately not a
 * BoxScope: that carries a DSL marker, which would hide these inside the Column every screen has.
 */
@Stable
class ScreenFrameScope internal constructor(private val frame: FrameGeometry) {

    /** The padding that puts a bled row's first item back on the margin. */
    val bleedPadding = PaddingValues(horizontal = SetteeSpace.ScreenSide)

    /**
     * Runs this row out to the screen's left and right edges, so it scrolls under the margin
     * rather than stopping at it. Give the row [bleedPadding] so its first item still starts at
     * the margin.
     */
    fun Modifier.bleed(): Modifier = layout { measurable, constraints ->
        val side = SetteeSpace.ScreenSide.roundToPx()
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val wide = constraints.copy(
            minWidth = constraints.minWidth + side * 2,
            maxWidth = constraints.maxWidth + side * 2,
        )
        val placeable = measurable.measure(wide)
        layout(placeable.width - side * 2, placeable.height) { placeable.place(-side, 0) }
    }

    /**
     * Where video plays (the players, the Live Preview): the frame leaves this area unpainted, so
     * the picture behind the window shows. One per screen.
     *
     * Until every screen is on ScreenFrame, MainActivity's root Surface still paints underneath, so
     * only a SurfaceView (which punches through the window itself, as ExoPlayer's does) shows here.
     */
    @Composable
    fun VideoArea(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
        DisposableEffect(Unit) { onDispose { frame.video = null } }
        Box(modifier.onGloballyPositioned { frame.video = it.boundsInRoot() }, content = content)
    }
}
