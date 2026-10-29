/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.data.model.Profile

/** The screen the app opens on. */
enum class FirstScreen { ADD_SOURCE, WHOS_WATCHING, HOME }

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
 * its own; nothing sits behind it, so Back leaves the app as it does from Home.
 */
@Composable
fun WhosWatchingScreen(profiles: List<Profile>, lastActiveId: Long, onChoose: (Long) -> Unit) {
    val start = profiles.indexOfFirst { it.id == lastActiveId }.coerceAtLeast(0)
    val row = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val highlight = remember { FocusRequester() }
    // The row places its cards a frame after the first composition, as on Home.
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { highlight.requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.whos_watching_heading), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(40.dp))
        LazyRow(
            state = row,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    modifier = if (profile.id == profiles[start].id) Modifier.focusRequester(highlight) else Modifier,
                    onClick = { onChoose(profile.id) },
                )
            }
        }
    }
}

/** A Profile's initial over its name; the focused card scales up and gains the app's primary border. */
@Composable
private fun ProfileCard(profile: Profile, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "profileScale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(140.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.name.take(1).uppercase(),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            profile.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 140.dp, height = 28.dp),
        )
    }
}
