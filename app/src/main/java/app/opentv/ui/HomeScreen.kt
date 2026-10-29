/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.optionsClickable
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.ContinueWatchingRow
import app.opentv.ui.vod.MoviePosterRow
import app.opentv.ui.vod.SectionHeader
import app.opentv.ui.vod.SeriesPosterRow
import app.opentv.ui.vod.rememberRowState
import app.opentv.ui.vod.takesFocus
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
    takeFocus: Boolean,
    onNothingToShow: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state = viewModel.state.collectAsState().value ?: return
    HomeContent(
        state.continueWatching,
        state.favouriteShows,
        state.favouriteMovies,
        state.favouriteChannels,
        onResume,
        onOpenSeries,
        onOpenMovie,
        onPlayChannel,
        takeFocus,
        state.recent,
        onNothingToShow,
    )
}

/**
 * Home's rows, top to bottom: what the Profile can pick back up, its Favourites by kind, then the
 * [recent] titles. An empty row is left out rather than shown blank.
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
    takeFocus: Boolean,
    recent: RecentRows = RecentRows(),
    onNothingToShow: () -> Unit = {},
) {
    val shelves = remember(continueWatching, favouriteShows, favouriteMovies, favouriteChannels, recent) {
        homeShelves(continueWatching, favouriteShows, favouriteMovies, favouriteChannels, recent)
    }
    if (shelves.isEmpty()) {
        // A hint can't take the highlight, so hand it to the side menu rather than leave the remote
        // with nothing to move. It waits a frame, as the request for the first card does below.
        LaunchedEffect(Unit) {
            if (!takeFocus) return@LaunchedEffect
            withFrameNanos {}
            onNothingToShow()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.home_empty_hint),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // The card picked last, kept while the viewer is off in the player or on a title's page so the
    // highlight can go back to it. It's spent the first time Home comes back, highlight or not:
    // Home reached any other way opens on its first card, as it always has.
    var picked by rememberSaveable(stateSaver = HomePick.Saver) { mutableStateOf<HomePick?>(null) }
    // Until its card has the highlight, the pick being restored follows the rows as they change, so
    // it lands on the card itself rather than on where the card stood.
    var restoring by remember { mutableStateOf(picked?.takeIf { takeFocus }) }
    val target = restoring?.let { restoreTarget(shelves.map(Shelf::row), it) }
    val start = remember { FocusRequester() }
    val focus = remember(target) {
        target?.let {
            CardFocus(it.index) { focused ->
                restoring = null
                // The card couldn't take the highlight, so the list does, rather than leave the
                // remote with nothing to move.
                if (!focused) runCatching { start.requestFocus() }
            }
        }
    }

    // Home opens on its first card, so OK straight away picks up what was last watched — unless
    // [takeFocus] is false because Home was just picked in the side menu, where the highlight
    // stays. The request lands on the list, which hands focus to its first card; it waits a frame
    // because a lazy list only composes its cards when it is first laid out. Coming back to a
    // picked card, the list instead scrolls to its row, which highlights the card itself.
    val column = rememberLazyListState()
    LaunchedEffect(start) {
        picked = null
        if (!takeFocus || restoring != null) return@LaunchedEffect
        column.scrollToItem(0)
        withFrameNanos {}
        runCatching { start.requestFocus() }
    }
    LaunchedEffect(target?.rowIndex) { target?.let { column.scrollToItem(it.rowIndex) } }
    LazyColumn(
        state = column,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().focusRequester(start).focusGroup(),
    ) {
        shelves.forEachIndexed { rowIndex, shelf ->
            item(key = shelf.row.key) {
                val rowFocus = focus?.takeIf { target?.row == shelf.row.key }
                fun pick(item: String, open: () -> Unit) {
                    picked = HomePick(shelf.row.key, rowIndex, item, shelf.row.items.indexOf(item))
                    open()
                }
                when (shelf) {
                    is Shelf.Resume -> ContinueWatchingRow(
                        shelf.cards,
                        rowFocus,
                        onResume = { key, url, title -> pick(key) { onResume(key, url, title) } },
                    )
                    is Shelf.Shows -> SeriesPosterRow(
                        stringResource(shelf.title),
                        shelf.shows,
                        { show -> pick(show.pickKey) { onOpenSeries(show) } },
                        rowFocus,
                    )
                    is Shelf.Movies -> MoviePosterRow(
                        stringResource(shelf.title),
                        shelf.movies,
                        { movie -> pick(movie.pickKey) { onOpenMovie(movie) } },
                        rowFocus,
                    )
                    is Shelf.Channels -> ChannelRow(
                        shelf.channels,
                        { channel -> pick(channel.pickKey) { onPlayChannel(channel) } },
                        rowFocus,
                    )
                }
            }
        }
    }
}

/** One of Home's rows as the highlight sees it: its list key and its cards' keys, in order. */
internal class HomeRow(val key: String, val items: List<String>)

/** One of Home's rows and what it shows; the one list both draws Home and places its highlight. */
private sealed class Shelf(key: String, items: List<String>) {
    val row = HomeRow(key, items)

    class Resume(val cards: List<VodViewModel.ResumeItem>) : Shelf("cw", cards.map { it.mediaKey })

    class Shows(key: String, @StringRes val title: Int, val shows: List<Series>) :
        Shelf(key, shows.map { it.pickKey })

    class Movies(key: String, @StringRes val title: Int, val movies: List<Movie>) :
        Shelf(key, movies.map { it.pickKey })

    class Channels(val channels: List<Channel>) : Shelf("channels", channels.map { it.pickKey })
}

/** Home's rows that have something in them, top to bottom. */
private fun homeShelves(
    continueWatching: List<VodViewModel.ResumeItem>,
    favouriteShows: List<Series>,
    favouriteMovies: List<Movie>,
    favouriteChannels: List<Channel>,
    recent: RecentRows,
) = listOf(
    Shelf.Resume(continueWatching),
    Shelf.Shows("shows", R.string.home_favourite_shows, favouriteShows),
    Shelf.Movies("movies", R.string.home_favourite_movies, favouriteMovies),
    Shelf.Channels(favouriteChannels),
    Shelf.Movies("releasedMovies", R.string.home_recently_released_movies, recent.releasedMovies),
    Shelf.Movies("addedMovies", R.string.home_recently_added_movies, recent.addedMovies),
    Shelf.Shows("releasedShows", R.string.home_recently_released_shows, recent.releasedShows),
    Shelf.Shows("addedShows", R.string.home_recently_added_shows, recent.addedShows),
).filter { it.row.items.isNotEmpty() }

// A pick is kept by the provider's ids rather than the local row id, which a catalogue refresh can
// change while the viewer is away.
private val Movie.pickKey get() = "$sourceId:$streamId"
private val Series.pickKey get() = "$sourceId:$seriesId"
private val Channel.pickKey get() = "$sourceId:$streamId"

/**
 * A card the viewer picked on Home: its row and the card's key, with where each stood, so the
 * highlight has somewhere to go if the card itself has gone by the time they come back.
 */
internal data class HomePick(val row: String, val rowIndex: Int, val item: String, val index: Int) {
    companion object {
        val Saver = listSaver<HomePick?, Any>(
            save = { pick -> pick?.run { listOf(row, rowIndex, item, index) }.orEmpty() },
            restore = {
                it.takeIf {
                    it.size == 4
                }?.let { HomePick(it[0] as String, it[1] as Int, it[2] as String, it[3] as Int) }
            },
        )
    }
}

/** Where the highlight goes on coming back to Home after [pick], given the [rows] Home has now. */
internal fun restoreTarget(rows: List<HomeRow>, pick: HomePick): HomePick? {
    val rowIndex = rows.indexOfFirst { it.key == pick.row }
    return when {
        rows.isEmpty() -> null
        // The whole row has gone (its last Favourite removed, say): the row that now stands where it
        // stood takes the highlight, as it does when a row goes while the viewer is on Home.
        rowIndex < 0 -> pick.rowIndex.coerceAtMost(rows.lastIndex).let {
            HomePick(rows[it].key, it, rows[it].items.first(), 0)
        }
        else -> {
            val items = rows[rowIndex].items
            val index = items.indexOf(pick.item).takeIf { it >= 0 } ?: pick.index.coerceAtMost(items.lastIndex)
            HomePick(pick.row, rowIndex, items[index], index)
        }
    }
}

@Composable
private fun ChannelRow(channels: List<Channel>, onPlayChannel: (Channel) -> Unit, focus: CardFocus?) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.home_favourite_channels))
        LazyRow(
            state = rememberRowState(focus),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                ChannelCard(channel, Modifier.takesFocus(index, focus)) { onPlayChannel(channel) }
            }
        }
    }
}

/** A channel's logo on a landscape tile, its name underneath; focus scales it and draws the primary border. */
@Composable
private fun ChannelCard(channel: Channel, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "channelScale")
    Column(
        modifier
            .width(CHANNEL_WIDTH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .optionsClickable(onClick, openOptionsOn(OptionsSubject.OfChannel(channel)))
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
