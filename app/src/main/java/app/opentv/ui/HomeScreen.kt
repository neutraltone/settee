/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.Text
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.rememberQualityBadge
import app.opentv.ui.vod.rememberRowState
import app.opentv.ui.vod.takesFocus

/**
 * Home: the tab the app opens on, gathering what the active Profile can pick back up, its
 * Favourites and what's Recently Added. Cards do what they do on the other tabs: Continue Watching
 * plays, a show or movie opens its page, a channel plays.
 */
@Composable
internal fun HomeScreen(
    onResume: (MediaKey) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    onOpenLive: () -> Unit,
    takeFocus: Boolean,
    onNothingToShow: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state = viewModel.state.collectAsState().value ?: return
    val live = state.live
    val preview = rememberLivePreviewPlayer(live?.preview?.channel)
    // Home has no list of its own to zap through, so channel up and down go through every Channel.
    val play: (Channel) -> Unit = { channel ->
        viewModel.zapFromAll()
        onPlayChannel(channel)
    }
    HomeContent(
        state.continueWatching,
        state.favouriteShows,
        state.favouriteMovies,
        state.favouriteChannels,
        onResume,
        onOpenSeries,
        onOpenMovie,
        play,
        takeFocus,
        state.recentShows,
        state.recentMovies,
        onNothingToShow,
        live = live?.let { LiveTile(it, preview.player, preview.error) },
        onWatchLive = play,
        onOpenLive = onOpenLive,
        stopPreview = preview.stop,
    )
}

/**
 * Home's rows, top to bottom: the Live Preview, what the Profile can pick back up, what's Recently
 * Added ([recentShows], [recentMovies]), then its Favourite shows, movies and channels. An empty row
 * is left out rather than shown blank.
 */
@Composable
internal fun HomeContent(
    continueWatching: List<ResumeItem>,
    favouriteShows: List<Cover>,
    favouriteMovies: List<Cover>,
    favouriteChannels: List<HomeChannel>,
    onResume: (MediaKey) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenMovie: (Movie) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    takeFocus: Boolean,
    recentShows: List<Cover> = emptyList(),
    recentMovies: List<Cover> = emptyList(),
    onNothingToShow: () -> Unit = {},
    live: LiveTile? = null,
    onWatchLive: (Channel) -> Unit = {},
    onOpenLive: () -> Unit = {},
    stopPreview: () -> Unit = {},
) {
    val shelves =
        remember(
            live,
            continueWatching,
            favouriteShows,
            favouriteMovies,
            favouriteChannels,
            recentShows,
            recentMovies,
        ) {
            homeShelves(
                live,
                continueWatching,
                HomeTitles(recentShows, recentMovies, favouriteShows, favouriteMovies),
                favouriteChannels,
            )
        }
    ScreenFrame {
        if (shelves.isEmpty()) {
            // A hint can't take the highlight, so hand it to the side menu rather than leave the remote
            // with nothing to move. It waits a frame, as the request for the first card does below.
            LaunchedEffect(Unit) {
                if (!takeFocus) return@LaunchedEffect
                withFrameNanos {}
                onNothingToShow()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.home_empty_hint), style = SetteeType.Body, color = SetteeColors.QuietText)
            }
            return@ScreenFrame
        }
        HomeRows(
            shelves,
            takeFocus,
            HomeActions(
                onResume,
                onOpenTitle = { title ->
                    when (title) {
                        is VodTitle.OfMovie -> onOpenMovie(title.movie)
                        is VodTitle.OfSeries -> onOpenSeries(title.series)
                    }
                },
                onPlayChannel,
                onWatchLive,
                onOpenLive,
                stopPreview,
            ),
        )
    }
}

/** What Home's cards do when they're picked. */
private class HomeActions(
    val onResume: (MediaKey) -> Unit,
    val onOpenTitle: (VodTitle) -> Unit,
    val onPlayChannel: (Channel) -> Unit,
    val onWatchLive: (Channel) -> Unit,
    val onOpenLive: () -> Unit,
    val stopPreview: () -> Unit,
)

@Composable
private fun ScreenFrameScope.HomeRows(shelves: List<HomeShelf>, takeFocus: Boolean, actions: HomeActions) {
    // The card picked last, kept while the viewer is off in the player or on a title's page so the
    // highlight can go back to it. It's spent the first time Home comes back, highlight or not:
    // Home reached any other way opens on its first card, as it always has.
    var picked by rememberSaveable(stateSaver = HomePick.Saver) { mutableStateOf<HomePick?>(null) }
    // Until its card has the highlight, the pick being restored follows the rows as they change, so
    // it lands on the card itself rather than on where the card stood.
    var restoring by remember { mutableStateOf(picked?.takeIf { takeFocus }) }
    val target = restoring?.let { restoreTarget(shelves.map(HomeShelf::row), it) }
    val start = remember { FocusRequester() }
    val focus = rememberRestoreFocus(target, start) { restoring = null }

    // Home opens on its first card, so OK straight away picks up what was last watched — unless
    // [takeFocus] is false because Home was just picked in the side menu, where the highlight
    // stays. The request lands on the list, which hands focus to its first card; it waits a frame
    // because a lazy list only composes its cards when it is first laid out. Coming back to a
    // picked card, the list instead scrolls to its row, which highlights the card itself.
    val column = rememberLazyListState()
    val confirmLive = LocalConfirmLive.current
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
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
        modifier = Modifier.fillMaxSize().focusRequester(start).focusGroup(),
    ) {
        shelves.forEachIndexed { rowIndex, shelf ->
            // Each kind of shelf draws its own composable, so its class is its content type.
            railReturnItem(key = shelf.row.key, contentType = shelf::class) {
                val rowFocus = focus?.takeIf { target?.row == shelf.row.key }
                fun pick(item: String, open: () -> Unit) {
                    picked = HomePick(shelf.row.key, rowIndex, item, shelf.row.items.indexOf(item))
                    // Before the next screen starts: a second stream can cut off a single-connection
                    // line, and waiting for the lifecycle change leaves both running for a moment.
                    actions.stopPreview()
                    open()
                }
                val leave: (String) -> LeaveScreen = { item -> { go -> pick(item, go) } }
                when (shelf) {
                    is HomeShelf.Live -> LivePreviewTile(shelf.tile, rowFocus) {
                        shelf.tile.open(
                            { channel -> confirmLive { pick(LIVE_PICK) { actions.onWatchLive(channel) } } },
                            { pick(LIVE_PICK, actions.onOpenLive) },
                        )
                    }
                    is HomeShelf.Resume -> ContinueWatchingShelf(
                        shelf.cards,
                        // The large first card is only for when nothing sits above it.
                        large = shelves.first() === shelf,
                        rowFocus,
                        onResume = { key -> pick(key.stored) { actions.onResume(key) } },
                        leave = { card -> leave(card.key.stored) },
                    )
                    is HomeShelf.Covers -> CoverShelf(
                        stringResource(shelf.title),
                        shelf.covers,
                        shelf.showsStatus,
                        rowFocus,
                        onOpen = { title -> pick(title.pickKey) { actions.onOpenTitle(title) } },
                        leave = { title -> leave(title.pickKey) },
                    )
                    is HomeShelf.Channels -> ChannelShelf(
                        shelf.channels,
                        // Asked before the pick, which stops the preview: choosing to keep recording
                        // leaves Home just as it was.
                        { channel -> confirmLive { pick(channel.pickKey) { actions.onPlayChannel(channel) } } },
                        rowFocus,
                    )
                }
            }
        }
    }
}

/**
 * The highlight's way back to [target]'s card, calling [onDone] once it has tried. When the card
 * couldn't take the highlight, [fallback] (the list) does, rather than leave the remote with nothing
 * to move.
 */
@Composable
private fun rememberRestoreFocus(target: HomePick?, fallback: FocusRequester, onDone: () -> Unit): CardFocus? =
    remember(target) {
        target?.let {
            CardFocus(it.index) { focused ->
                onDone()
                if (!focused) runCatching { fallback.requestFocus() }
            }
        }
    }

/** One of Home's rows as the highlight sees it: its list key and its cards' keys, in order. */
internal class HomeRow(val key: String, val items: List<String>)

/** One of Home's rows and what it shows; the one list both draws Home and places its highlight. */
private sealed class HomeShelf(key: String, items: List<String>) {
    val row = HomeRow(key, items)

    class Live(val tile: LiveTile) : HomeShelf("live", listOf(LIVE_PICK))

    class Resume(val cards: List<ResumeItem>) : HomeShelf("cw", cards.map { it.key.stored })

    /** A row of covers; [showsStatus] when each says where the Profile is with its title. */
    class Covers(key: String, val title: Int, val covers: List<Cover>, val showsStatus: Boolean) :
        HomeShelf(key, covers.map { it.title.pickKey })

    class Channels(val channels: List<HomeChannel>) : HomeShelf("channels", channels.map { it.channel.primary.pickKey })
}

/** Home's rows of covers, by kind. */
private class HomeTitles(
    val recentShows: List<Cover>,
    val recentMovies: List<Cover>,
    val favouriteShows: List<Cover>,
    val favouriteMovies: List<Cover>,
)

/** Home's rows that have something in them, top to bottom. */
private fun homeShelves(
    live: LiveTile?,
    continueWatching: List<ResumeItem>,
    titles: HomeTitles,
    favouriteChannels: List<HomeChannel>,
) = listOfNotNull(
    live?.let(HomeShelf::Live),
    HomeShelf.Resume(continueWatching),
    HomeShelf.Covers("recentShows", R.string.home_recently_added_shows, titles.recentShows, showsStatus = true),
    HomeShelf.Covers("recentMovies", R.string.home_recently_added_movies, titles.recentMovies, showsStatus = true),
    // A Favourite shows only its title: where the Profile is with it is Continue Watching's job.
    HomeShelf.Covers("favouriteShows", R.string.home_favourite_shows, titles.favouriteShows, showsStatus = false),
    HomeShelf.Covers("favouriteMovies", R.string.home_favourite_movies, titles.favouriteMovies, showsStatus = false),
    HomeShelf.Channels(favouriteChannels),
).filter { it.row.items.isNotEmpty() }

private const val LIVE_PICK = "live"

// A pick is kept by the provider's ids rather than the local row id, which a catalogue refresh can
// change while the viewer is away. Movies and shows share a row, so each says which it is.
private val VodTitle.pickKey
    get() = when (this) {
        is VodTitle.OfMovie -> "movie:${movie.sourceId}:${movie.streamId}"
        is VodTitle.OfSeries -> "series:${series.sourceId}:${series.seriesId}"
    }
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
internal fun ScreenFrameScope.ContinueWatchingShelf(
    cards: List<ResumeItem>,
    large: Boolean,
    focus: CardFocus?,
    onResume: (MediaKey) -> Unit,
    leave: (ResumeItem) -> LeaveScreen,
    onHighlight: (ResumeItem) -> Unit = {},
) {
    val memory = rememberShelfMemory(remember(cards) { cards.map { it.key.stored } })
    val openOptions = LocalOpenOptions.current
    Shelf(stringResource(R.string.vod_continue_watching), memory, state = rememberRowState(focus)) {
        itemsIndexed(cards, key = { _, card -> card.key.stored }) { index, card ->
            ArtCard(
                title = card.title,
                artworkUrl = card.posterUrl,
                onClick = { onResume(card.key) },
                size = if (large && index == 0) ArtCardSize.Large else ArtCardSize.Landscape,
                modifier = Modifier
                    .takesFocus(index, focus)
                    .shelfCard(memory, card.key.stored)
                    .onFocusChanged { if (it.isFocused) onHighlight(card) },
                status = resumeStatusText(card),
                progress = card.progress,
                badge = rememberQualityBadge(card.name),
                scrollsTitle = true,
                onOptions = openOptions?.let { open -> { open(OptionsSubject.OfContinueWatching(card), leave(card)) } },
            )
        }
    }
}

@Composable
private fun resumeStatusText(card: ResumeItem): List<String> = listOfNotNull(
    when {
        card.newEpisode -> stringResource(R.string.watch_badge_new_episode)
        else -> card.episode?.let { stringResource(R.string.home_cover_episode, it.season, it.episode) }
    },
)

@Composable
private fun ScreenFrameScope.CoverShelf(
    title: String,
    covers: List<Cover>,
    showsStatus: Boolean,
    focus: CardFocus?,
    onOpen: (VodTitle) -> Unit,
    leave: (VodTitle) -> LeaveScreen,
) {
    val memory = rememberShelfMemory(remember(covers) { covers.map { it.title.pickKey } })
    Shelf(title, memory, state = rememberRowState(focus)) {
        itemsIndexed(covers, key = { _, cover -> cover.title.pickKey }) { index, cover ->
            val subject = when (val t = cover.title) {
                is VodTitle.OfMovie -> OptionsSubject.OfMovie(t.movie)
                is VodTitle.OfSeries -> OptionsSubject.OfSeries(t.series)
            }
            ArtCard(
                title = cover.title.shownTitle,
                artworkUrl = cover.title.posterUrl,
                onClick = { onOpen(cover.title) },
                size = ArtCardSize.Portrait,
                modifier = Modifier.takesFocus(index, focus).shelfCard(memory, cover.title.pickKey),
                status = if (showsStatus) coverStatusText(cover.status) else emptyList(),
                progress = (cover.status as? CoverStatus.InProgress)?.progress?.takeIf { showsStatus },
                badge = rememberQualityBadge(cover.title.name),
                onOptions = openOptionsOn(subject, leave(cover.title)),
            )
        }
    }
}

private val VodTitle.shownTitle
    get() = when (this) {
        is VodTitle.OfMovie -> movie.displayTitle
        is VodTitle.OfSeries -> series.displayTitle
    }

private val VodTitle.posterUrl
    get() = when (this) {
        is VodTitle.OfMovie -> movie.posterUrl
        is VodTitle.OfSeries -> series.posterUrl
    }

/**
 * The quiet lines on a cover: `S4 E3` over `22 min left`, or one of `22 min left`, `S4 E4`,
 * `New episode` or `Watched`.
 */
@Composable
internal fun coverStatusText(status: CoverStatus?): List<String> = when (status) {
    null -> emptyList()
    is CoverStatus.InProgress -> {
        val episode = status.episode?.let { stringResource(R.string.home_cover_episode, it.season, it.episodeNumber) }
        val left = status.remainingMillis?.let { minutesLeftText(it) }
        listOfNotNull(episode, left).ifEmpty { listOf(stringResource(R.string.watch_badge_in_progress)) }
    }
    is CoverStatus.UpNext -> listOf(
        stringResource(R.string.home_cover_episode, status.episode.season, status.episode.episodeNumber),
    )
    CoverStatus.NewEpisode -> listOf(stringResource(R.string.watch_badge_new_episode))
    CoverStatus.Watched -> listOf(stringResource(R.string.watch_badge_watched))
}
