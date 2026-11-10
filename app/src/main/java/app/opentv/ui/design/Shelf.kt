/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * A titled row of cards that runs out to the screen's edges and scrolls under the margin, its first
 * card on the margin. Cards share a bottom edge unless [verticalAlignment] says otherwise: tiles
 * with text of varying length under them line up at the top instead. Up and Down always leave the
 * row, and coming into it they land on the card the viewer was last on ([ShelfMemory]). The
 * padding above and below leaves room for a highlighted card's lift and glow.
 */
@Composable
fun ScreenFrameScope.Shelf(
    title: String,
    memory: ShelfMemory,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    verticalAlignment: Alignment.Vertical = Alignment.Bottom,
    cards: LazyListScope.() -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxWidth()) {
        Text(title, style = SetteeType.Section, maxLines = 1)
        LazyRow(
            state = state,
            // The side padding is the bleed's, which puts the first card back on the margin.
            contentPadding = PaddingValues(horizontal = SetteeSpace.ScreenSide, vertical = LIFT_ROOM),
            horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
            verticalAlignment = verticalAlignment,
            modifier = Modifier
                .bleed()
                // Up and Down always leave the row. Searched for by position, a taller card in the
                // same row (Home's large first one) reaches above and below the others, so the
                // press would move along the row to it. With the other cards out of reach for that
                // one search, it finds the next row, or nothing in the top and bottom rows.
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        memory.leaving = true
                        try {
                            focusManager.moveFocus(direction)
                        } finally {
                            memory.leaving = false
                        }
                    }
                    true
                }
                // Only for Up and Down: a card asked for by name (coming back to a picked one) keeps
                // its request. When the remembered card can't take it (scrolled out of the row),
                // focus falls back to the nearest card, as it would with no memory at all.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection == FocusDirection.Up ||
                            requestedFocusDirection == FocusDirection.Down
                        ) {
                            runCatching { memory.requester.requestFocus() }
                        }
                    }
                }
                .focusGroup(),
            content = cards,
        )
    }
}

/** Above and below a row's cards: half of a Portrait cover's 1.05 lift, plus the ring. */
private val LIFT_ROOM = 12.dp
