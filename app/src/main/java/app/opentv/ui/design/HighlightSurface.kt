/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The card ring (docs/LOOK.md, Highlight): a [width] band of [color] with an [edgeWidth] band of
 * [edgeColor] inside it, which keeps the ring visible on warm, white and printed artwork.
 */
@Immutable
class HighlightRing(
    val color: Color = SetteeColors.Accent,
    val width: Dp = 4.dp,
    val edgeColor: Color = SetteeColors.Background,
    val edgeWidth: Dp = 2.dp,
)

/** How a [HighlightSurface] looks in one state. */
@Immutable
class HighlightLook(
    val shape: Shape,
    val fill: Color = Color.Transparent,
    val ring: HighlightRing? = null,
    val scale: Float = 1f,
    val glow: Dp = 0.dp,
    val glowColor: Color = SetteeColors.Accent,
)

/**
 * How a [HighlightSurface] looks resting and highlighted, by role. LOOK.md has two treatments: the
 * ring and lift for things with artwork, and a solid coral fill for list-like things, where a ring
 * would crowd the text.
 */
@Immutable
class HighlightStyle(val resting: HighlightLook, val highlighted: HighlightLook) {
    companion object {
        /** Cards and the Live Preview: the ring, a 1.05 lift and the one glow. */
        val Card = ring(SetteeShapes.Card, lift = 1.05f)

        /**
         * Home's large first card. A 1.05 lift on something this big would push the ring past the
         * row's edge, where it's cut off.
         */
        val FirstCard = ring(SetteeShapes.Card, lift = 1.03f)

        /** Episode rows and rail items. */
        val Row = fill(SetteeShapes.Card)

        /**
         * The rail item for the tab that's open. It rests on a Raised fill, so the rail still shows
         * where the viewer is while it's closed and the Highlight is elsewhere.
         */
        val CurrentRow = fill(SetteeShapes.Card, resting = SetteeColors.Raised)

        /**
         * Settings' rows. They rest on a Surface fill, so a pane reads as a list of things to
         * press even before the Highlight reaches it.
         */
        val ListRow = fill(SetteeShapes.Card, resting = SetteeColors.Surface)

        /** Guide cells and sidebar rows. */
        val Cell = fill(SetteeShapes.Cell)

        /** Buttons, which also need a resting fill to read as buttons. */
        val Button = fill(SetteeShapes.Pill, resting = SetteeColors.Raised)

        /**
         * The player's seek bar. A coral fill would hide the coral progress, so it takes a Raised
         * fill and the bar grows its thumb.
         */
        val Bar = HighlightStyle(
            resting = HighlightLook(SetteeShapes.Pill),
            highlighted = HighlightLook(SetteeShapes.Pill, fill = SetteeColors.Raised),
        )

        private fun ring(shape: Shape, lift: Float) = HighlightStyle(
            resting = HighlightLook(shape),
            highlighted = HighlightLook(shape, ring = HighlightRing(), scale = lift, glow = 14.dp),
        )

        private fun fill(shape: Shape, resting: Color = Color.Transparent) = HighlightStyle(
            resting = HighlightLook(shape, fill = resting),
            highlighted = HighlightLook(shape, fill = SetteeColors.Accent),
        )
    }
}

/**
 * Anything that can take the Highlight. [content] is told whether it has it, to change its own
 * text colour (On accent on the coral fill) or move text clear of the ring.
 *
 * OK, or a tap, calls [onClick]. With [onOptions], the remote's Menu button, a held OK or a long
 * press opens the item's options instead.
 *
 * The lift and glow are drawn in one `graphicsLayer`, so moving the Highlight never measures or
 * lays anything out again, and only the highlighted item has a glow (the Stick can afford one).
 * There's no animation between the states: the budget allows the press's own frame to change them,
 * and an animated scale would cost frames on every move.
 */
@Composable
fun HighlightSurface(
    onClick: () -> Unit,
    style: HighlightStyle,
    modifier: Modifier = Modifier,
    onOptions: (() -> Unit)? = null,
    content: @Composable BoxScope.(highlighted: Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val highlighted by interactions.collectIsFocusedAsState()
    val look = if (highlighted) style.highlighted else style.resting
    // By style, not state: a card has no ring at rest, but its artwork still needs its corners.
    val clips = style.highlighted.ring != null
    Box(
        modifier
            .graphicsLayer {
                scaleX = look.scale
                scaleY = look.scale
                shadowElevation = look.glow.toPx()
                ambientShadowColor = look.glowColor
                spotShadowColor = look.glowColor
                shape = look.shape
                // Ringed styles hold artwork, which stays inside the corners, and their ring relies
                // on the clip (see ring). List-like ones don't clip, so a badge or chevron can hang
                // past the edge. The glow is drawn outside the clip either way.
                clip = clips
            }
            .background(look.fill, look.shape)
            .pressable(interactions, onClick, onOptions)
            .ring(look),
    ) {
        content(highlighted)
    }
}

/**
 * Clicks and the options press. Unlike `clickable`, which fires on OK's key-up however long it was
 * held, `combinedClickable` times a held OK itself and then drops the key-up, so only Menu needs
 * handling here.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pressable(
    interactions: MutableInteractionSource,
    onClick: () -> Unit,
    onOptions: (() -> Unit)?,
): Modifier {
    // The Highlight is drawn by HighlightSurface itself, so no ripple or state layer.
    if (onOptions == null) {
        return clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
    }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.Menu) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onOptions()
        true
    }.combinedClickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onLongClick = onOptions,
        onClick = onClick,
    )
}

/**
 * Draws [look]'s ring over the content. Each band is a stroke along the shape's own outline at
 * twice the band's width: the clip cuts off the outer half, which leaves the band's inner corners
 * following the shape (10 dp corners give a 6 dp inner edge, as in the prototype) for any shape,
 * pills included, with no inner shape to keep in step.
 */
private fun Modifier.ring(look: HighlightLook): Modifier {
    val ring = look.ring ?: return this
    return drawWithCache {
        val outline = look.shape.createOutline(size, layoutDirection, this)
        val edge = Stroke(width = (ring.width + ring.edgeWidth).toPx() * 2)
        val band = Stroke(width = ring.width.toPx() * 2)
        onDrawWithContent {
            drawContent()
            drawOutline(outline, ring.edgeColor, style = edge)
            drawOutline(outline, ring.color, style = band)
        }
    }
}
