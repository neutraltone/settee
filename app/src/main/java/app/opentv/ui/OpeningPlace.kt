/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import app.opentv.ui.vod.CardFocus

/**
 * The Highlight on a screen's opening card while its top row is still a skeleton (#581): the place
 * holds it so the remote has somewhere to be, and when the row's cards land, the Highlight goes to
 * its first card, where the screen would have opened.
 */
@Stable
internal class OpeningPlace(private val placeNow: State<Boolean>) {
    /** Whether the place had the Highlight when it went, so the row taking over takes it. */
    private var held by mutableStateOf(false)

    private var handing: CardFocus? = null

    private val modifier: Modifier = Modifier.onFocusChanged {
        // Losing it as the place goes isn't the viewer moving: the real row takes it over.
        if (it.isFocused) {
            held = true
        } else if (placeNow.value) {
            held = false
        }
    }

    /** For the place's opening card when [opens], to hear the Highlight come and go; else nothing. */
    fun modifierIf(opens: Boolean): Modifier? = modifier.takeIf { opens }

    /**
     * Where a row's Highlight goes: its first card when it [takesOver] the place that had it, else
     * [otherwise], whatever the screen would give it.
     */
    fun focusFor(takesOver: Boolean, otherwise: CardFocus?): CardFocus? {
        if (!held || !takesOver) return otherwise
        return handing ?: CardFocus(0) {
            held = false
            handing = null
        }.also { handing = it }
    }
}

/** An [OpeningPlace], told on each composition whether the top row is still a place ([placeNow]). */
@Composable
internal fun rememberOpeningPlace(placeNow: Boolean): OpeningPlace {
    val now = rememberUpdatedState(placeNow)
    return remember { OpeningPlace(now) }
}
