/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import app.opentv.ui.Backdrop
import app.opentv.ui.coverStatusText
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** The backdrop shown, and what a cover calls when it takes the Highlight. */
internal class RestingBackdrop(val shown: Backdrop?, val onHighlight: (Long) -> Unit)

/**
 * Follows the Highlight only once it has rested on a title for [BACKDROP_REST_MILLIS], so moving
 * quickly along a row doesn't load a backdrop for every title it passes (docs/LOOK.md, Shows and
 * Movies). Changing on each press cost a few frames on a Stick.
 */
@Composable
internal fun rememberRestingBackdrop(backdropFor: (Long) -> Flow<Backdrop?>): RestingBackdrop {
    var highlighted by remember { mutableStateOf<Long?>(null) }
    var shown by remember { mutableStateOf<Backdrop?>(null) }
    LaunchedEffect(highlighted) {
        val id = highlighted ?: return@LaunchedEffect
        delay(BACKDROP_REST_MILLIS)
        backdropFor(id).collect { shown = it }
    }
    return RestingBackdrop(shown) { highlighted = it }
}

private const val BACKDROP_REST_MILLIS = 400L

/**
 * The highlighted title's wide art, in the top corner of the screen and behind the text, out to
 * the screen's top and right edges rather than stopping at the margin. The fades are in the same
 * layout as the text, so they move with it when the sidebar pushes the screen along, and the text
 * never lands on the bright part of the art.
 *
 * A new title's art dips in: the old art fades out, then the new fades in once it has loaded. Only
 * one picture is drawn at a time, its alpha applied as it draws rather than through an offscreen
 * layer. Crossfading two see-through screens cost 21-45 ms a frame on a Stick (docs/LOOK.md).
 */
@Composable
internal fun BackdropArt(backdrop: Backdrop?) {
    val wanted = backdrop?.imageUrl
    var drawn by remember { mutableStateOf(wanted) }
    var loaded by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(wanted) {
        if (wanted == drawn) return@LaunchedEffect
        if (drawn != null) alpha.animateTo(0f, tween(ART_FADE_MILLIS))
        loaded = false
        drawn = wanted
    }
    LaunchedEffect(drawn, loaded) { if (drawn != null && loaded) alpha.animateTo(1f, tween(ART_FADE_MILLIS)) }
    val url = drawn ?: return
    Box(
        Modifier.fillMaxWidth().height(BACKDROP_HEIGHT).toTheEdges(),
        contentAlignment = Alignment.TopEnd,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            modifier = Modifier
                .fillMaxWidth(BACKDROP_WIDTH)
                .fillMaxHeight()
                .graphicsLayer {
                    this.alpha = alpha.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
        Box(Modifier.matchParentSize().background(AcrossFade))
        Box(Modifier.matchParentSize().background(DownFade))
    }
}

private const val ART_FADE_MILLIS = 200

/** The highlighted title's name, where the Profile is with it, and its synopsis. */
@Composable
internal fun BackdropText(backdrop: Backdrop?) {
    Column(Modifier.fillMaxWidth(BACKDROP_TEXT_WIDTH).height(BACKDROP_TEXT_HEIGHT)) {
        backdrop ?: return@Column
        Text(backdrop.title, SetteeType.Hero, maxLines = 1)
        // Home's words for a cover's status, on one line: `S4 E3 · 22 min left`.
        coverStatusText(backdrop.status).takeIf { it.isNotEmpty() }?.let { QuietLabel(it.joinToString(" · ")) }
        backdrop.synopsis?.takeIf { it.isNotBlank() }?.let { Text(it, SetteeType.Synopsis, maxLines = 3) }
    }
}

/**
 * Out past the frame's margin at the top and right, as rows run out past it at the sides, taking
 * up only the space inside it. Held to the margin, the art stopped short of the screen's corner.
 */
private fun Modifier.toTheEdges() = layout { measurable, constraints ->
    val side = SetteeSpace.ScreenSide.roundToPx()
    val top = SetteeSpace.ScreenTopBottom.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + side,
            maxWidth = constraints.maxWidth + side,
            minHeight = constraints.minHeight + top,
            maxHeight = constraints.maxHeight + top,
        ),
    )
    layout(placeable.width - side, placeable.height - top) { placeable.place(0, -top) }
}

private val BACKDROP_HEIGHT = 260.dp
private const val BACKDROP_WIDTH = 0.7f
private const val BACKDROP_TEXT_WIDTH = 0.55f
private val BACKDROP_TEXT_HEIGHT = 150.dp

/** From the screen's background at the art's left edge to the art itself. */
private val AcrossFade = Brush.horizontalGradient(
    0.3f to SetteeColors.Background,
    0.75f to Color.Transparent,
)

/** Into the background under the art, where the rows start. */
private val DownFade = Brush.verticalGradient(0.5f to Color.Transparent, 1f to SetteeColors.Background)
