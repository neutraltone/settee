/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * A card that is its artwork: [title] and, when there is one, up to two quiet [status] lines on a dark fade
 * at the foot of the art, and a [progress] bar along its bottom edge for anything part-watched.
 * Missing or slow artwork leaves the Raised fill, so the title still reads. An optional [badge],
 * such as the title's Quality, sits in the top corner. With [scrollsTitle], the title keeps to one
 * line and scrolls while the card is highlighted.
 *
 * Highlighted, the bar moves in clear of the ring and the text moves up clear of the bar
 * (docs/LOOK.md, Highlight).
 */
@Composable
fun ArtCard(
    title: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    size: ArtCardSize,
    modifier: Modifier = Modifier,
    status: List<String> = emptyList(),
    progress: Float? = null,
    badge: String? = null,
    scrollsTitle: Boolean = false,
    onOptions: (() -> Unit)? = null,
) {
    HighlightSurface(onClick, size.style, modifier, onOptions) { highlighted ->
        Box(Modifier.size(size.width, size.height).background(SetteeColors.Raised)) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(FADE_HEIGHT)
                    .background(Fade),
            )
            badge?.let { Badge(it, Modifier.align(Alignment.TopEnd).padding(BADGE_INSET)) }
            val barInset = if (highlighted) RING_CLEARANCE else 0.dp
            val textBottom = if (progress == null) TEXT_INSET else barInset + ProgressBarHeight + TEXT_INSET
            Column(
                Modifier.align(
                    Alignment.BottomStart,
                ).padding(start = TEXT_INSET, end = TEXT_INSET, bottom = textBottom),
            ) {
                if (scrollsTitle) {
                    // One line, cut off at rest. Highlighted, it scrolls so the whole title can be
                    // read. The marquee moves the drawn text only, so it costs no layout per frame.
                    Text(
                        title,
                        style = SetteeType.CardTitle,
                        maxLines = 1,
                        modifier = if (highlighted) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                    )
                } else {
                    Text(title, style = SetteeType.CardTitle, maxLines = 2)
                }
                // A line each, not one phrase: "S4 E3 · 22 min left" is wider than a cover at the
                // smallest text size, and wrapped wherever it ran out it looked accidental.
                status.forEach { QuietLabel(it) }
            }
            progress?.let {
                ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(barInset))
            }
        }
    }
}

/**
 * A short label in the art's top corner, such as a title's Quality, on a dark pill so it reads over
 * any artwork. Not in the accent colour, which on a TV reads as something to press.
 */
@Composable
private fun Badge(text: String, modifier: Modifier) {
    Text(
        text,
        style = SetteeType.Body,
        maxLines = 1,
        modifier = modifier
            .background(SetteeColors.Background.copy(alpha = 0.8f), SetteeShapes.Pill)
            .padding(horizontal = 8.dp),
    )
}

/** Clear of the ring when the card is highlighted. */
private val BADGE_INSET = 8.dp

/** Inside the ring's 4 dp coral and 2 dp dark edge. */
private val RING_CLEARANCE = 6.dp
private val TEXT_INSET = 10.dp

/** The lower part of the art the fade covers: enough for two lines of title and the status lines. */
private const val FADE_HEIGHT = 0.6f

private val Fade = Brush.verticalGradient(
    0f to Color.Transparent,
    1f to SetteeColors.Background.copy(alpha = 0.92f),
)
