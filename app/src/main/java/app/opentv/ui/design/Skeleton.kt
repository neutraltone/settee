/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/**
 * A row still loading: its real [title] over blank cards, as many as reach the screen's edge. The
 * blank cards pulse slowly as one layer ([rememberSkeletonPulse]); the title is real, so it stays still.
 *
 * No card can take the Highlight, except the first when [opening] is given: the card a screen opens
 * on, which holds the Highlight until the real row arrives to take it over. OK does nothing on it, and
 * it stands outside the pulse, so the Highlight on it holds steady. [opening] goes on that card, for
 * the screen to ask for the Highlight and hear it go.
 */
@Composable
fun ScreenFrameScope.SkeletonShelf(
    title: String,
    cards: SkeletonCards = SkeletonCards.Covers,
    opening: Modifier? = null,
) {
    val pulse = rememberSkeletonPulse()
    Column {
        Text(title, SetteeType.Section, maxLines = 1)
        BoxWithConstraints(Modifier.bleed().padding(vertical = SetteeSpace.LiftRoom)) {
            val count = cardsToFill(maxWidth.bounded(FALLBACK_WIDTH) - SetteeSpace.ScreenSide, cards)
            Row(
                horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                verticalAlignment = cards.alignment,
                // Unbounded, or the Row squeezes the card that runs past the edge to fit.
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(bleedPadding),
            ) {
                val first = if (opening != null) 1 else 0
                if (opening != null) {
                    val size = cards.card(0)
                    OpeningBlank(size.style, SetteeShapes.Card, opening.size(size.width, size.height))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                    verticalAlignment = cards.alignment,
                    modifier = pulse,
                ) {
                    for (index in first until count) {
                        val size = cards.card(index)
                        Box(Modifier.size(size.width, size.height).blank(SetteeShapes.Card))
                    }
                }
            }
        }
    }
}

/**
 * Home's Live Preview tile still loading: a blank picture [pictureHeight] tall, 16:9, beside a blank
 * line where the channel's name goes, as tall as the tile with the room for its lift. Given
 * [opening], the picture holds the Highlight Home opens on until the tile arrives, outside the pulse.
 */
@Composable
fun SkeletonTile(pictureHeight: Dp, opening: Modifier? = null) {
    val pulse = rememberSkeletonPulse()
    Row(
        Modifier.fillMaxWidth().padding(vertical = SetteeSpace.LiftRoom).height(pictureHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val picture = Modifier.fillMaxHeight().aspectRatio(16f / 9f)
        if (opening != null) {
            OpeningBlank(HighlightStyle.Card, SetteeShapes.Card, opening.then(picture))
        } else {
            Box(picture.then(pulse).blank(SetteeShapes.Card))
        }
        Spacer(Modifier.width(TILE_GAP))
        Box(Modifier.width(TILE_NAME_WIDTH).height(TILE_NAME_HEIGHT).then(pulse).blank(SetteeShapes.Cell))
    }
}

/**
 * A grid of covers still loading, as a category's whole grid lays them out: rows of blank covers
 * filling the space, pulsing as one layer ([rememberSkeletonPulse]). None can take the Highlight.
 */
@Composable
fun SkeletonGrid(modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulse()
    val cover = ArtCardSize.Portrait
    BoxWithConstraints(modifier.fillMaxSize().padding(vertical = SetteeSpace.LiftRoom)) {
        // As many whole columns as the grid fits, and as many rows as reach the bottom.
        val width = maxWidth.bounded(FALLBACK_WIDTH)
        val height = maxHeight.bounded(FALLBACK_HEIGHT)
        val columns = ((width + SetteeSpace.CardGap) / (cover.width + SetteeSpace.CardGap)).toInt().coerceAtLeast(1)
        val rows = ceil(height / (cover.height + SetteeSpace.RowGap)).toInt().coerceAtLeast(1)
        Column(pulse, verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap)) {
                    repeat(columns) { Box(Modifier.size(cover.width, cover.height).blank(SetteeShapes.Card)) }
                }
            }
        }
    }
}

/**
 * A guide still being built, as the guide lays itself out: a band [header] tall, then rows [rowHeight]
 * tall, each a blank channel cell [channelColumn] wide and blank programmes of a few lengths in
 * [halfHour]s, filling the space and pulsing as one layer ([rememberSkeletonPulse]).
 *
 * Only the first row's channel cell can take the Highlight, given [opening]: the row the guide opens
 * on, which holds it until the real rows arrive, outside the pulse. OK does nothing on it.
 */
@Composable
fun SkeletonGuide(
    header: Dp,
    rowHeight: Dp,
    channelColumn: Dp,
    halfHour: Dp,
    modifier: Modifier = Modifier,
    opening: Modifier? = null,
) {
    val pulse = rememberSkeletonPulse()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val rows = ceil((maxHeight.bounded(FALLBACK_HEIGHT) - header) / rowHeight).toInt().coerceAtLeast(1)
        val width = maxWidth.bounded(FALLBACK_WIDTH)
        val cell = Modifier.width(channelColumn - GUIDE_GAP).height(rowHeight).padding(vertical = GUIDE_GAP / 2)
        // First, so it's the first blank card in reading order; it sits over the first row's place.
        if (opening != null) {
            OpeningBlank(
                HighlightStyle.Cell,
                SetteeShapes.Cell,
                Modifier.padding(top = header).then(cell).then(opening),
            )
        }
        Column(Modifier.padding(top = header).then(pulse)) {
            repeat(rows) { row ->
                Row(
                    Modifier.height(rowHeight).wrapContentWidth(Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(GUIDE_GAP),
                ) {
                    // The opening cell is drawn over this one's place, outside the pulse.
                    if (row == 0 && opening != null) {
                        Spacer(cell)
                    } else {
                        Box(cell.blank(SetteeShapes.Cell))
                    }
                    // Programmes of a few lengths, each row starting at a different one, so it reads as a guide.
                    var reached = channelColumn
                    var block = row
                    while (reached < width) {
                        val length = halfHour * PROGRAMME_HALF_HOURS[block % PROGRAMME_HALF_HOURS.size]
                        Box(
                            Modifier
                                .width(length - GUIDE_GAP)
                                .fillMaxHeight()
                                .padding(vertical = GUIDE_GAP / 2)
                                .blank(SetteeShapes.Cell),
                        )
                        reached += length
                        block++
                    }
                }
            }
        }
    }
}

/**
 * This size, or [fallback] when there's no limit (a skeleton inside something that scrolls that way):
 * the cards and rows are counted to fill the space, which without a limit would never end.
 */
private fun Dp.bounded(fallback: Dp): Dp = if (this != Dp.Infinity && !value.isNaN()) this else fallback

/** How many [cards] it takes to reach across [width]: the last may run past the edge, none wholly beyond it. */
private fun cardsToFill(width: Dp, cards: SkeletonCards): Int {
    var reached = 0.dp
    var count = 0
    while (reached < width) {
        reached += cards.card(count).width + SetteeSpace.CardGap
        count++
    }
    return count
}

/** A blank card's fill, found by tests as [SKELETON_CARD]. */
private fun Modifier.blank(shape: Shape) = testTag(SKELETON_CARD).background(SetteeColors.Surface, shape)

/**
 * The blank card a screen opens on, which can take the Highlight in [style] and does nothing on OK.
 * With a fill style it's the Highlight's coral while highlighted, as a real cell is; a ringed card
 * keeps its blank under the ring.
 */
@Composable
private fun OpeningBlank(style: HighlightStyle, shape: Shape, modifier: Modifier) {
    HighlightSurface(onClick = {}, style = style, modifier = modifier.testTag(SKELETON_CARD)) { highlighted ->
        if (!highlighted || style.highlighted.ring != null) {
            Box(Modifier.fillMaxSize().background(SetteeColors.Surface, shape))
        }
    }
}

/**
 * Settee's skeleton pulse, for a skeleton's layer of blank cards: its opacity easing between full and
 * [PULSE_LOW] and back every [PULSE_MILLIS], about the aerial TV's beat. One slow breath on the whole
 * layer, not a shimmer across the cards: alive from the sofa, never a flashing light in a dark room.
 * Opacity on a layer redraws nothing inside it, so the pulse costs the Stick no layout or drawing.
 *
 * It follows the frame clock's own time, not the time since it appeared, so every skeleton on the
 * screen breathes together. Like [AerialTv] it ignores the animation scale: the motion is the only
 * sign the screen isn't frozen, which WCAG counts as essential (docs/LOOK.md, Waiting).
 */
@Composable
private fun rememberSkeletonPulse(): Modifier {
    val millis = rememberFrameMillis()
    return remember(millis) {
        Modifier.graphicsLayer {
            // A cosine, so it eases in and out at both ends of the breath and never snaps.
            val phase = (millis.longValue % PULSE_MILLIS).toFloat() / PULSE_MILLIS
            alpha = 1f - (1f - PULSE_LOW) * (1f - cos(phase * 2 * PI.toFloat())) / 2f
        }
    }
}

/**
 * The frame clock's time, from the bare clock: rememberInfiniteTransition reads the animation scale
 * and at 0 stops dead (see [AerialTv]). Read only inside the layer, so a frame redraws nothing else.
 */
@Composable
private fun rememberFrameMillis(): LongState {
    val millis = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withInfiniteAnimationFrameMillis { now -> millis.longValue = now }
        }
    }
    return millis
}

/** What tests find a skeleton's blank cards by. */
const val SKELETON_CARD = "skeleton-card"

private const val PULSE_MILLIS = 1_800L

/** How far the pulse dims the cards: a third, enough to see it breathe, not to see it blink. */
private const val PULSE_LOW = 0.65f

/** The space a skeleton fills when it has no limit: a 1080p TV screen's. */
private val FALLBACK_WIDTH = 960.dp
private val FALLBACK_HEIGHT = 540.dp

/** A guide skeleton's programmes, in half hours, in turn. */
private val PROGRAMME_HALF_HOURS = listOf(2, 1, 3, 2, 1, 4)

/** Between a guide skeleton's blocks, as the guide's rules part its cells. */
private val GUIDE_GAP = 4.dp

/** Between the Live Preview's picture and its words, as on the tile. */
private val TILE_GAP = 18.dp

/** A channel's name, roughly, on the Live Preview tile. */
private val TILE_NAME_WIDTH = 220.dp
private val TILE_NAME_HEIGHT = 28.dp
