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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import kotlin.math.cos

/** Which cards a [SkeletonShelf] holds the place of. */
sealed interface SkeletonCards {
    /** The width of the card at [index]. */
    fun width(index: Int): Dp

    /** How tall the cards are. */
    val height: Dp

    /** Shows' and movies' covers. */
    data object Covers : SkeletonCards {
        override fun width(index: Int) = ArtCardSize.Portrait.width
        override val height = ArtCardSize.Portrait.height
    }

    /** Continue Watching's cards, the first one large when Home has no Live Preview above it. */
    data class ContinueWatching(val largeFirst: Boolean) : SkeletonCards {
        override fun width(index: Int) =
            if (index == 0 && largeFirst) ArtCardSize.Large.width else ArtCardSize.Landscape.width
        override val height = if (largeFirst) ArtCardSize.Large.height else ArtCardSize.Landscape.height
    }
}

/**
 * A row still loading: its real [title] over blank cards, as many as reach the screen's edge, which
 * can't take the Highlight. The cards pulse slowly as one ([skeletonPulse]); the title is real, so
 * it stays still.
 */
@Composable
fun ScreenFrameScope.SkeletonShelf(title: String, cards: SkeletonCards = SkeletonCards.Covers) {
    Column {
        Text(title, SetteeType.Section, maxLines = 1)
        BoxWithConstraints(Modifier.bleed().padding(vertical = SHELF_LIFT_ROOM)) {
            val count = cardsToFill(maxWidth - SetteeSpace.ScreenSide, cards)
            Row(
                horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                // Unbounded, or the Row squeezes the card that runs past the edge to fit.
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                    .padding(bleedPadding)
                    .skeletonPulse(),
            ) {
                repeat(count) { index ->
                    Box(
                        Modifier
                            .testTag(SKELETON_CARD)
                            .size(cards.width(index), cards.height)
                            .background(SetteeColors.Surface, SetteeShapes.Card),
                    )
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
        reached += cards.width(count) + SetteeSpace.CardGap
        count++
    }
    return count
}

/**
 * Settee's skeleton pulse: the whole layer's opacity easing between full and [PULSE_LOW] and back
 * every [PULSE_MILLIS], about the aerial TV's beat. One slow breath on the layer, not a shimmer
 * across each card: alive from the sofa, never a flashing light in a dark room. Opacity on a layer
 * redraws nothing beneath it, so the pulse costs the Stick no layout or drawing.
 *
 * It follows the frame clock's own time, not the time since it appeared, so every skeleton on the
 * screen breathes together. Like [AerialTv] it ignores the animation scale: the motion is the only
 * sign the screen isn't frozen, which WCAG counts as essential (docs/LOOK.md, Waiting).
 */
@Composable
fun Modifier.skeletonPulse(): Modifier {
    val millis = rememberFrameMillis()
    return graphicsLayer {
        // A cosine, so it eases in and out at both ends of the breath and never snaps.
        val phase = (millis.longValue % PULSE_MILLIS).toFloat() / PULSE_MILLIS
        alpha = 1f - (1f - PULSE_LOW) * (1f - cos(phase * 2 * PI.toFloat())) / 2f
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

/** As much as a Shelf leaves above and below its covers: placeholders are as tall as a row. */
private val SHELF_LIFT_ROOM = 12.dp
