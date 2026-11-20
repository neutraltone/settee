/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.ui.Alignment

/** Which cards a [SkeletonShelf] holds the place of. */
sealed interface SkeletonCards {
    /** The card at [index]. */
    fun card(index: Int): ArtCardSize

    /** Where cards of different heights line up, as the real row lines them up. */
    val alignment: Alignment.Vertical get() = Alignment.Bottom

    /** Shows' and movies' covers. */
    data object Covers : SkeletonCards {
        override fun card(index: Int) = ArtCardSize.Portrait
    }

    /** Continue Watching's cards, the first one large when Home has no Live Preview above it. */
    data class ContinueWatching(val largeFirst: Boolean) : SkeletonCards {
        override fun card(index: Int) = if (index == 0 && largeFirst) ArtCardSize.Large else ArtCardSize.Landscape

        // As Continue Watching hangs its cards from the top under a large first card.
        override val alignment: Alignment.Vertical get() = if (largeFirst) Alignment.Top else Alignment.Bottom
    }
}
