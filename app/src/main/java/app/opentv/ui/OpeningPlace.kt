/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.opentv.ui.vod.CardFocus

/**
 * The Highlight on a screen's opening card while its top row is still a skeleton (#581): the place
 * holds it so the remote has somewhere to be, and when the row's cards land, the Highlight goes to
 * its first card, where the screen would have opened. When the place goes with nothing to take
 * over, as Continue Watching does when it comes back empty, the place now at the top takes it.
 */
@Stable
internal class OpeningPlace(private val topPlace: State<String?>) {
    /** Whether the place had the Highlight when it went, so the row taking over takes it. */
    var held by mutableStateOf(false)
        private set

    private var handing: CardFocus? = null

    // The place that had the Highlight, by key: losing it while that place still leads is the viewer
    // moving, but losing it because the place went isn't, and whatever leads now takes it over.
    private var holder: String? = null

    private val requester = FocusRequester()

    private val modifier: Modifier = Modifier.focusRequester(requester).onFocusChanged {
        if (it.isFocused) {
            held = true
            holder = topPlace.value
        } else if (topPlace.value != null && topPlace.value == holder) {
            letGo()
        }
    }

    private fun letGo() {
        held = false
        holder = null
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
            letGo()
            handing = null
        }.also { handing = it }
    }

    /**
     * The place now leading takes the Highlight the one before had. If it can't, nothing holds it
     * any more, so a later change at the top doesn't pull it back from wherever the viewer goes.
     */
    fun takeOver() {
        if (!held) return
        if (runCatching { requester.requestFocus() }.getOrNull() != true) letGo()
    }
}

/**
 * An [OpeningPlace], told on each composition which place leads the screen ([topPlace], its row's
 * key), or null once a real row does.
 */
@Composable
internal fun rememberOpeningPlace(topPlace: String?): OpeningPlace {
    val top = rememberUpdatedState(topPlace)
    val opening = remember { OpeningPlace(top) }
    // A new place at the top while the one before had the Highlight: it waits a frame for the
    // place's card to be composed, as a lazy list only composes it once laid out.
    LaunchedEffect(topPlace) {
        if (topPlace == null || !opening.held) return@LaunchedEffect
        withFrameNanos {}
        opening.takeOver()
    }
    return opening
}

/** An [OpeningPlace] for a screen with one place, which holds the Highlight while [placeNow]. */
@Composable
internal fun rememberOpeningPlace(placeNow: Boolean): OpeningPlace =
    rememberOpeningPlace(topPlace = SOLE_PLACE.takeIf { placeNow })

private const val SOLE_PLACE = "place"
