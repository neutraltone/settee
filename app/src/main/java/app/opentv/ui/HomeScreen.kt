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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.ui.vod.ContinueWatchingRow
import app.opentv.ui.vod.MoviePosterRow
import app.opentv.ui.vod.SectionHeader
import app.opentv.ui.vod.SeriesPosterRow
import coil.compose.AsyncImage

/**
 * Home: the tab the app opens on, gathering what the active Profile can pick back up and its
 * Favourites. Cards do what they do on the other tabs: Continue Watching plays, a show or movie
 * opens its page, a channel plays.
 */
@Composable
internal fun HomeScreen(
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val continueWatching = viewModel.continueWatching.collectAsState().value
    val favouriteShows = viewModel.favouriteShows.collectAsState().value
    val favouriteMovies = viewModel.favouriteMovies.collectAsState().value
    val favouriteChannels = viewModel.favouriteChannels.collectAsState().value
    if (continueWatching == null ||
        favouriteShows == null ||
        favouriteMovies == null ||
        favouriteChannels == null
    ) {
        return
    }
    HomeContent(
        continueWatching,
        favouriteShows,
        favouriteMovies,
        favouriteChannels,
        onResume,
        onOpenSeries,
        onOpenMovie,
        onPlayChannel,
    )
}

/**
 * Home's rows, top to bottom: what the Profile can pick back up, then its Favourites by kind. An
 * empty row is left out rather than shown blank.
 */
@Composable
internal fun HomeContent(
    continueWatching: List<VodViewModel.ResumeItem>,
    favouriteShows: List<Series>,
    favouriteMovies: List<Movie>,
    favouriteChannels: List<Channel>,
    onResume: (mediaKey: String, url: String, title: String) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onPlayChannel: (Channel) -> Unit,
) {
    if (continueWatching.isEmpty() &&
        favouriteShows.isEmpty() &&
        favouriteMovies.isEmpty() &&
        favouriteChannels.isEmpty()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.home_empty_hint),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Home opens on its first card, so OK straight away picks up what was last watched. The
    // request lands on the list, which hands focus to its first card; it waits a frame because a
    // lazy list only composes its cards when it is first laid out, and until then has none to give.
    val start = remember { FocusRequester() }
    LaunchedEffect(start) {
        withFrameNanos {}
        runCatching { start.requestFocus() }
    }
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().focusRequester(start).focusGroup(),
    ) {
        if (continueWatching.isNotEmpty()) item(key = "cw") { ContinueWatchingRow(continueWatching, onResume) }
        if (favouriteShows.isNotEmpty()) {
            item(key = "shows") {
                SeriesPosterRow(stringResource(R.string.home_favourite_shows), favouriteShows, onOpenSeries)
            }
        }
        if (favouriteMovies.isNotEmpty()) {
            item(key = "movies") {
                MoviePosterRow(stringResource(R.string.home_favourite_movies), favouriteMovies, onOpenMovie)
            }
        }
        if (favouriteChannels.isNotEmpty()) item(key = "channels") { ChannelRow(favouriteChannels, onPlayChannel) }
    }
}

@Composable
private fun ChannelRow(channels: List<Channel>, onPlayChannel: (Channel) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.home_favourite_channels))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                ChannelCard(channel) { onPlayChannel(channel) }
            }
        }
    }
}

/** A channel's logo on a landscape tile, its name underneath; focus scales it and draws the primary border. */
@Composable
private fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "channelScale")
    Column(
        Modifier
            .width(CHANNEL_WIDTH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (focused) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            // Logos come in every shape; fitted with a margin they never lose their edges to a crop.
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            channel.shownName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CHANNEL_WIDTH = 200.dp
