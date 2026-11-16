/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Profile
import app.opentv.data.model.Source
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import coil.compose.AsyncImage

/** The screen the app opens on. */
enum class FirstScreen { ADD_SOURCE, WHOS_WATCHING, HOME }

/**
 * Whether any of [sources] is a provider yet. One whose channels never loaded isn't: Settee closed
 * during Setup's first step leaves one, and it should open on the provider choice, not an empty Home.
 */
fun hasProvider(sources: List<Source>): Boolean = sources.any { it.lastCatalogSyncMillis > 0 }

/**
 * Who's watching? is asked once per process, not per activity: rotating, or coming back from the
 * launcher to an app that is still running, carries on as whoever was watching, while a fresh
 * process (a cold start, or Android restoring the app after killing it) asks again.
 *
 * A launch that asks to play something, from a reminder or a recording starting, skips the
 * question: someone already chose what to watch, and a live start shouldn't wait on a picker.
 */
fun firstScreen(
    hasProvider: Boolean,
    profileCount: Int,
    askedThisProcess: Boolean,
    launchedToPlay: Boolean,
): FirstScreen = when {
    !hasProvider -> FirstScreen.ADD_SOURCE
    profileCount > 1 && !askedThisProcess && !launchedToPlay -> FirstScreen.WHOS_WATCHING
    else -> FirstScreen.HOME
}

/**
 * Whether this process has settled who's watching, by asking or by opening without asking. An
 * object rather than saved state on purpose: it has to survive a rotation and coming back from the
 * launcher, but not the process, so a cold start or a restore after Android killed the app asks.
 */
object WhosWatchingAsked {
    var settled by mutableStateOf(false)
}

/**
 * Who's watching?: one card per Profile, starting on [lastActiveId]. There's no Back handling of
 * its own; nothing sits behind it, so Back leaves the app as it does from Home. [stills] holds a frame
 * from what each Profile last played, by Profile id, to sit behind their initial.
 */
@Composable
fun WhosWatchingScreen(
    profiles: List<Profile>,
    lastActiveId: Long,
    stills: Map<Long, String> = emptyMap(),
    onChoose: (Long) -> Unit,
) {
    val start = profiles.indexOfFirst { it.id == lastActiveId }.coerceAtLeast(0)
    val row = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val highlight = remember { FocusRequester() }
    // The row places its cards a frame after the first composition, as on Home.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { highlight.requestFocus() }
    }

    ScreenFrame {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.whos_watching_heading), style = SetteeType.ScreenTitle)
            Spacer(Modifier.height(HEADING_GAP))
            LazyRow(
                state = row,
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                // Room round the cards for the Highlight's lift and glow, which the row would cut off.
                contentPadding = PaddingValues(horizontal = LIFT_ROOM, vertical = LIFT_ROOM),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        still = stills[profile.id],
                        modifier = if (profile.id == profiles[start].id) Modifier.focusRequester(highlight) else Modifier,
                        onClick = { onChoose(profile.id) },
                    )
                }
            }
        }
    }
}

/**
 * A Profile's card: their initial over a frame from what they last watched, with their name beneath.
 * It takes the Highlight as every card does (the ring, the lift and the one glow). The name sits
 * outside the card, so the lift doesn't move it out of line with the others, and turns coral with
 * the card so the row still says whose it is from across the room.
 */
@Composable
private fun ProfileCard(profile: Profile, still: String?, modifier: Modifier, onClick: () -> Unit) {
    var highlighted by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HighlightSurface(
            onClick = onClick,
            style = HighlightStyle.Card,
            modifier = modifier
                .onFocusChanged { highlighted = it.isFocused }
                .semantics { contentDescription = profile.name },
        ) {
            Box(Modifier.size(CARD_SIZE).background(SetteeColors.Raised), contentAlignment = Alignment.Center) {
                if (still != null) {
                    AsyncImage(
                        model = still,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Even, not a fade: the letter sits in the middle. Enough background over a white
                    // frame for the Display-size letter to keep 4:1.
                    Box(Modifier.fillMaxSize().background(StillShade))
                }
                Text(profile.name.take(1).uppercase(), style = SetteeType.Display)
            }
        }
        Spacer(Modifier.height(NAME_GAP))
        Text(
            profile.name,
            style = SetteeType.RowTitle,
            color = if (highlighted) SetteeColors.Accent else SetteeColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Read out with the card, which carries the name.
            modifier = Modifier.width(CARD_SIZE).clearAndSetSemantics {},
        )
    }
}

private val CARD_SIZE = 160.dp
private val CARD_GAP = 32.dp
private val HEADING_GAP = 40.dp
private val NAME_GAP = 14.dp

/** A 1.05 lift on a 160 dp card reaches 4 dp past it; the glow, 14 dp. */
private val LIFT_ROOM = 16.dp

private val StillShade = SetteeColors.Background.copy(alpha = 0.6f)
