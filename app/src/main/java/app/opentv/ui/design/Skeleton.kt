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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos

/**
 * A row still loading: its real [title] over blank cards, as many as reach the screen's edge. The
 * cards pulse slowly as one ([skeletonPulse]); the title is real, so it stays still.
 *
 * No card can take the Highlight, except the first when [opening] is given: the card a screen opens
 * on, which holds the Highlight until the real row arrives to take it over. OK does nothing on it.
 * [opening] goes on that card, for the screen to ask for the Highlight and hear it go.
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
        BoxWithConstraints(Modifier.bleed().padding(vertical = SHELF_LIFT_ROOM)) {
            val count = cardsToFill(maxWidth - SetteeSpace.ScreenSide, cards)
            Row(
                horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                verticalAlignment = cards.alignment,
                // Unbounded, or the Row squeezes the card that runs past the edge to fit.
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(bleedPadding),
            ) {
                repeat(count) { index ->
                    val size = cards.card(index)
                    val blank = Modifier
                        .size(size.width, size.height)
                        .then(pulse)
                        .background(SetteeColors.Surface, SetteeShapes.Card)
                    if (index == 0 && opening != null) {
                        HighlightSurface(onClick = {}, style = size.style, modifier = opening.testTag(SKELETON_CARD)) {
                            Box(blank)
                        }
                    } else {
                        Box(Modifier.testTag(SKELETON_CARD).then(blank))
                    }
                }
            }
        }
    }
}

/**
 * A grid of covers still loading, as a category's whole grid lays them out: rows of blank covers
 * filling the space, pulsing as one ([rememberSkeletonPulse]). None can take the Highlight.
 */
@Composable
fun SkeletonGrid(modifier: Modifier = Modifier, liftRoom: Dp = SHELF_LIFT_ROOM) {
    val pulse = rememberSkeletonPulse()
    val cover = ArtCardSize.Portrait
    BoxWithConstraints(modifier.fillMaxSize().padding(vertical = liftRoom)) {
        // As many whole columns as the grid fits, and as many rows as reach the bottom.
        val columns = ((maxWidth + SetteeSpace.CardGap) / (cover.width + SetteeSpace.CardGap)).toInt().coerceAtLeast(1)
        val rows = ceil(maxHeight / (cover.height + SetteeSpace.RowGap)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap)) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap)) {
                    repeat(columns) {
                        Box(
                            Modifier
                                .testTag(SKELETON_CARD)
                                .size(cover.width, cover.height)
                                .then(pulse)
                                .background(SetteeColors.Surface, SetteeShapes.Card),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A guide still being built, as the guide lays itself out: a band [header] tall, then rows [rowHeight]
 * tall, each a blank channel cell [channelColumn] wide and blank programmes of a few lengths in
 * [halfHour]s, filling the space and pulsing as one ([rememberSkeletonPulse]).
 *
 * Only the first row's channel cell can take the Highlight, given [opening]: the row the guide opens
 * on, which holds it until the real rows arrive. OK does nothing on it.
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
        val rows = ceil((maxHeight - header) / rowHeight).toInt().coerceAtLeast(1)
        val width = maxWidth
        Column(Modifier.padding(top = header)) {
            repeat(rows) { row ->
                Row(
                    Modifier.height(rowHeight).wrapContentWidth(Alignment.Start, unbounded = true),
                    horizontalArrangement = Arrangement.spacedBy(GUIDE_GAP),
                ) {
                    val cell = Modifier
                        .width(channelColumn - GUIDE_GAP)
                        .fillMaxHeight()
                        .padding(vertical = GUIDE_GAP / 2)
                    val blankCell = Modifier
                        .fillMaxSize()
                        .then(pulse)
                        .background(SetteeColors.Surface, SetteeShapes.Cell)
                    if (row == 0 && opening != null) {
                        HighlightSurface(
                            onClick = {},
                            style = HighlightStyle.Cell,
                            modifier = cell.then(opening).testTag(SKELETON_CARD),
                        ) { highlighted ->
                            // A highlighted cell is the Highlight's coral fill, as the guide's own is.
                            if (!highlighted) Box(blankCell)
                        }
                    } else {
                        Box(cell) { Box(blankCell.testTag(SKELETON_CARD)) }
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
                                .then(pulse)
                                .background(SetteeColors.Surface, SetteeShapes.Cell),
                        )
                        reached += length
                        block++
                    }
                }
            }
        }
    }
}

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

/**
 * Settee's skeleton pulse, for each blank card of a skeleton: its opacity easing between full and [PULSE_LOW] and back
 * every [PULSE_MILLIS], about the aerial TV's beat. One slow breath on every card at once, not a
 * shimmer across them: alive from the sofa, never a flashing light in a dark room. Opacity on a layer
 * redraws nothing inside it, so the pulse costs the Stick no layout or drawing.
 *
 * It follows the frame clock's own time, not the time since it appeared, so every skeleton on the
 * screen breathes together. Like [AerialTv] it ignores the animation scale: the motion is the only
 * sign the screen isn't frozen, which WCAG counts as essential (docs/LOOK.md, Waiting).
 */
@Composable
fun rememberSkeletonPulse(): Modifier {
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

/** A guide skeleton's programmes, in half hours, in turn. */
private val PROGRAMME_HALF_HOURS = listOf(2, 1, 3, 2, 1, 4)

/** Between a guide skeleton's blocks, as the guide's rules part its cells. */
private val GUIDE_GAP = 4.dp

/** As much as a Shelf leaves above and below its covers: placeholders are as tall as a row. */
private val SHELF_LIFT_ROOM = 12.dp
