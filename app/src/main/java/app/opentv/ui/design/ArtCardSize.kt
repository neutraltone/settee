/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** An [ArtCard]'s size and shape, and how it lifts (docs/LOOK.md, Home and Highlight). */
@Immutable
class ArtCardSize private constructor(val width: Dp, val height: Dp, val style: HighlightStyle) {
    companion object {
        /** A show's or movie's cover. */
        val Portrait = ArtCardSize(118.dp, 177.dp, HighlightStyle.Card)

        /** A Continue Watching card. */
        val Landscape = ArtCardSize(186.dp, 105.dp, HighlightStyle.Card)

        /** Home's large first Continue Watching card, which lifts less so its ring stays on screen. */
        val Large = ArtCardSize(300.dp, 169.dp, HighlightStyle.FirstCard)
    }
}
