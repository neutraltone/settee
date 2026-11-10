/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.opentv.ui.Backdrop
import app.opentv.ui.coverStatusText
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** The backdrop shown, and what a cover calls when it takes the Highlight. */
internal class RestingBackdrop(val shown: Backdrop?, val onHighlight: (Long) -> Unit)

/**
 * Follows the Highlight only once it has rested on a title for [BACKDROP_REST_MILLIS], so moving
 * quickly along a row doesn't load a backdrop for every title it passes (docs/LOOK.md, Shows and
 * Movies). Changing on each press cost a few frames on a Stick.
 */
@Composable
internal fun rememberRestingBackdrop(backdropFor: suspend (Long) -> Backdrop?): RestingBackdrop {
    var highlighted by remember { mutableStateOf<Long?>(null) }
    var shown by remember { mutableStateOf<Backdrop?>(null) }
    LaunchedEffect(highlighted) {
        val id = highlighted ?: return@LaunchedEffect
        delay(BACKDROP_REST_MILLIS)
        shown = backdropFor(id)
    }
    return RestingBackdrop(shown) { highlighted = it }
}

private const val BACKDROP_REST_MILLIS = 400L

/**
 * The highlighted title's wide art, in the top corner of the screen and behind the text. The fades
 * are in the same layout as the text, so they move with it when the sidebar pushes the screen
 * along, and the text never lands on the bright part of the art.
 */
@Composable
internal fun BackdropArt(backdrop: Backdrop?) {
    val url = backdrop?.imageUrl ?: return
    Box(Modifier.fillMaxWidth().height(BACKDROP_HEIGHT), contentAlignment = Alignment.TopEnd) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(BACKDROP_WIDTH).fillMaxHeight(),
        )
        Box(Modifier.matchParentSize().background(AcrossFade))
        Box(Modifier.matchParentSize().background(DownFade))
    }
}

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
