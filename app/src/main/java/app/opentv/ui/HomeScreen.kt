/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.core.JourneyTimer
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.Banner
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.ScreenFrameScope
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Shelf
import app.opentv.ui.design.SkeletonCards
import app.opentv.ui.design.SkeletonShelf
import app.opentv.ui.design.SkeletonTile
import app.opentv.ui.design.Text
import app.opentv.ui.design.rememberShelfMemory
import app.opentv.ui.design.shelfCard
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.options.LocalOpenOptions
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.rememberRowHighlight
import app.opentv.ui.vod.CardFocus
import app.opentv.ui.vod.rememberCoverBadge
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
    /** Settings › Providers, on the given provider's row. */
    onOpenProviders: (sourceId: Long) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val journeys = ServiceLocator.get(LocalContext.current).journeys
    val banner by viewModel.providerBanner.collectAsState()
    // Null only until the quick checks of which rows to expect are back: then every row is drawn,
    // as its cards or as a skeleton holding its place.
    val state = viewModel.state.collectAsState().value ?: return
    val live = state.live
    // A Live Preview the Profile turned off doesn't play unseen; Home still hears of it, for its hint.
    // Nor does a saved one drawn while the fresh one comes: nothing has checked its channel still plays.
    val preview =
        rememberLivePreviewPlayer(live?.preview?.channel?.takeIf { state.arrangement.livePreview && !state.liveStill })
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
        becauseYouWatched = state.becauseYouWatched,
        live = live?.let { LiveTile(it, preview.player, preview.error) },
        onWatchLive = play,
        onOpenLive = onOpenLive,
        stopPreview = preview.stop,
        arrangement = state.arrangement,
        banner = banner,
        onOpenProviders = onOpenProviders,
        loading = state.loading,
        liveLoading = state.liveLoading,
        onFirstRowDrawn = { journeys.end(JourneyTimer.Journey.COLD_START) },
    )
}

/**
 * Home's rows, top to bottom: the Live Preview, then the Home Rows in the order the Profile put them
 * in ([arrangement]); usually what it can pick back up, what's like the title it finished last
 * ([becauseYouWatched]), what's Recently Added ([recentShows], [recentMovies]), then its Favourite
 * shows, movies and channels. An empty row is left out rather than shown blank, and so is one the
 * Profile hid.
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
    arrangement: HomeArrangement = HomeArrangement.USUAL,
    becauseYouWatched: BecauseYouWatched? = null,
    banner: ProviderBanner? = null,
    onOpenProviders: (sourceId: Long) -> Unit = {},
    /** The rows still on their way, which hold their places as skeletons ([HomeState.loading]). */
    loading: Set<HomeRowKind> = emptySet(),
    /** Whether the Live Preview is still on its way, its place held above the rows ([HomeState.liveLoading]). */
    liveLoading: Boolean = false,
    /** Once a row has drawn with its cards, or Home has drawn with none to show: the end of a cold start. */
    onFirstRowDrawn: () -> Unit = {},
) {
    val all =
        remember(
            live,
            continueWatching,
            becauseYouWatched,
            favouriteShows,
            favouriteMovies,
            favouriteChannels,
            recentShows,
            recentMovies,
            arrangement,
            loading,
            liveLoading,
        ) {
            homeShelves(
                live,
                HomeCards(
                    continueWatching,
                    HomeTitles(recentShows, recentMovies, favouriteShows, favouriteMovies, becauseYouWatched),
                    favouriteChannels,
                ),
                arrangement,
                loading,
                liveLoading,
            )
        }
    val shelves = all.shown
    WhenDrawn(shelves.isEmpty() || shelves.any { it is HomeShelf.Row }, onFirstRowDrawn)
    ScreenFrame {
        // Above the rows rather than one of them, so the rows' list still hands its first card the
        // opening Highlight; Up from the top row reaches the banner's button.
        Column(Modifier.fillMaxSize()) {
            banner?.let {
                ProviderBannerLine(it, onOpenProviders)
                Spacer(Modifier.height(SetteeSpace.RowGap))
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
                    // Rows with something in them that the Profile hid would otherwise leave it wondering
                    // where its Favourites went.
                    val hint = if (all.anyHidden) R.string.home_hidden_rows_hint else R.string.home_empty_hint
                    Text(stringResource(hint), style = SetteeType.Body, color = SetteeColors.QuietText)
                }
                return@Column
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
}

/** One provider by name with its failure's sentence, or how many when there are more; never the detail line. */
@Composable
private fun ProviderBannerLine(banner: ProviderBanner, onOpenProviders: (sourceId: Long) -> Unit) {
    val providers = banner.providers
    val text = providers.singleOrNull()?.let {
        stringResource(R.string.home_provider_banner_one, it.name, it.error.sentence)
    } ?: pluralStringResource(R.plurals.home_provider_banner_many, providers.size, providers.size)
    Banner(
        text,
        stringResource(R.string.home_provider_banner_open),
        onAction = { onOpenProviders(providers.first().sourceId) },
    )
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
    var picked by rememberSaveable(stateSaver = RowPick.Saver) { mutableStateOf<RowPick?>(null) }
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
    val covered = LocalShellCovered.current
    val shellReturn = LocalShellReturn.current
    LaunchedEffect(start) {
        // Built under the screen it will come back from (a player let it go): the pick waits for Back.
        if (covered) return@LaunchedEffect
        picked = null
        if (!takeFocus || restoring != null) return@LaunchedEffect
        column.scrollToItem(0)
        withFrameNanos {}
        runCatching { start.requestFocus() }
    }
    // Back to Home, and the highlight couldn't go straight back to its card (KeptShell): to the
    // picked card if there is one, as when Home is built on its return, otherwise the first.
    LaunchedEffect(shellReturn.asked) {
        if (!shellReturn.asked) return@LaunchedEffect
        val pick = picked
        picked = null
        if (pick != null) {
            restoring = pick
            return@LaunchedEffect
        }
        column.scrollToItem(0)
        withFrameNanos {}
        runCatching { start.requestFocus() }
    }
    LaunchedEffect(target?.rowIndex) { target?.let { column.scrollToItem(it.rowIndex) } }
    // Whatever leads Home holds or takes its opening Highlight, as on For you: the rows can come in
    // any order, and Continue Watching, which usually leads, can come back empty.
    val top = shelves.firstOrNull()
    val opening = rememberOpeningPlace(topPlace = top?.takeIf { it is HomeShelf.Place }?.row?.key)
    LazyColumn(
        state = column,
        verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
        modifier = Modifier.fillMaxSize().focusRequester(start).focusGroup(),
    ) {
        shelves.forEachIndexed { rowIndex, shelf ->
            // Each kind of shelf draws its own composable, so its class is its content type.
            railReturnItem(key = shelf.row.key, contentType = shelf::class) {
                val restoring = focus?.takeIf { target?.row == shelf.row.key }
                val rowFocus = opening.focusFor(top === shelf, otherwise = restoring)
                fun pick(item: String, open: () -> Unit) {
                    picked = RowPick(shelf.row.key, rowIndex, item, shelf.row.items.indexOf(item))
                    // Before the next screen starts: a second stream can cut off a single-connection
                    // line, and waiting for the lifecycle change leaves both running for a moment.
                    actions.stopPreview()
                    open()
                }
                val leave: (String) -> LeaveScreen = { item -> { go -> pick(item, go) } }
                when (shelf) {
                    is HomeShelf.Place -> HomePlace(shelf, isTop = top === shelf, opening)
                    is HomeShelf.Live -> LivePreviewTile(shelf.tile, rowFocus) {
                        shelf.tile.open(
                            { channel -> confirmLive { pick(LIVE_PICK) { actions.onWatchLive(channel) } } },
                            { pick(LIVE_PICK, actions.onOpenLive) },
                        )
                    }
                    is HomeShelf.Resume -> ContinueWatchingShelf(
                        shelf.cards,
                        // The large first card is only for when nothing sits above it.
                        large = top === shelf,
                        rowFocus,
                        onResume = { card -> pick(card.titleKey) { actions.onResume(card.key) } },
                        leave = { card -> leave(card.titleKey) },
                    )
                    is HomeShelf.Covers -> CoverShelf(
                        stringResource(shelf.title, *shelf.titleArgs),
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
private fun rememberRestoreFocus(target: RowPick?, fallback: FocusRequester, onDone: () -> Unit): CardFocus? =
    remember(target) {
        target?.let {
            CardFocus(it.index) { focused ->
                onDone()
                if (!focused) runCatching { fallback.requestFocus() }
            }
        }
    }

/** One of Home's rows and what it shows; the one list both draws Home and places its highlight. */
private sealed class HomeShelf(key: String, items: List<String>) {
    val row = PickRow(key, items)

    class Live(val tile: LiveTile) : HomeShelf(LIVE_KEY, listOf(LIVE_PICK))

    /** A row still on its way, its place held by blank cards. */
    sealed class Place(key: String) : HomeShelf(key, emptyList())

    /** The Live Preview still on its way, its place held by a blank tile. */
    class LivePlace : Place(LIVE_KEY)

    /** One of the Home Rows, under [title]. */
    sealed class Row(key: String, items: List<String>, val title: Int) : HomeShelf(key, items)

    class Resume(val cards: List<ResumeItem>) :
        Row(HomeRowKind.CONTINUE_WATCHING.key, cards.map { it.titleKey }, R.string.vod_continue_watching)

    /**
     * A row of covers; [showsStatus] when each says where the Profile is with its title. [titleArg]
     * fills in [title] where it names something, as Because you watched names the title.
     */
    class Covers(
        key: String,
        title: Int,
        val covers: List<Cover>,
        val showsStatus: Boolean,
        titleArg: String? = null,
    ) : Row(key, covers.map { it.title.pickKey }, title) {
        val titleArgs: Array<Any> = listOfNotNull<Any>(titleArg).toTypedArray()
    }

    class Channels(val channels: List<HomeChannel>) :
        Row(
            HomeRowKind.FAVOURITE_CHANNELS.key,
            channels.map {
                it.channel.primary.pickKey
            },
            R.string.home_favourite_channels,
        )

    /** A Home Row still on its way, its place held by blank cards under its real [title]. */
    class Skeleton(val kind: HomeRowKind, val title: Int) : Place(kind.key)
}

/**
 * A row still on its way, in its cards' shape: the Live Preview's blank tile, Continue Watching's
 * cards, or covers. [isTop] when nothing is above it, where its place holds the opening Highlight.
 */
@Composable
private fun ScreenFrameScope.HomePlace(place: HomeShelf.Place, isTop: Boolean, opening: OpeningPlace) {
    val opens = opening.modifierIf(isTop)
    when (place) {
        is HomeShelf.LivePlace -> SkeletonTile(LIVE_HEIGHT, opening = opens)
        is HomeShelf.Skeleton -> {
            val cards = if (place.kind == HomeRowKind.CONTINUE_WATCHING) {
                SkeletonCards.ContinueWatching(largeFirst = isTop)
            } else {
                SkeletonCards.Covers
            }
            SkeletonShelf(stringResource(place.title), cards, opening = opens)
        }
    }
}

/** Home's rows of covers, by kind. */
private class HomeTitles(
    val recentShows: List<Cover>,
    val recentMovies: List<Cover>,
    val favouriteShows: List<Cover>,
    val favouriteMovies: List<Cover>,
    val becauseYouWatched: BecauseYouWatched?,
)

/** What each of Home's rows has in it, and the shelf it makes. */
private class HomeCards(
    val continueWatching: List<ResumeItem>,
    val titles: HomeTitles,
    val favouriteChannels: List<HomeChannel>,
) {
    fun shelf(row: HomeRowKind): HomeShelf.Row = when (row) {
        HomeRowKind.CONTINUE_WATCHING -> HomeShelf.Resume(continueWatching)
        HomeRowKind.BECAUSE_YOU_WATCHED -> HomeShelf.Covers(
            row.key,
            R.string.home_because_you_watched,
            titles.becauseYouWatched?.covers.orEmpty(),
            showsStatus = true,
            titleArg = titles.becauseYouWatched?.basedOn,
        )
        HomeRowKind.RECENT_SHOWS ->
            HomeShelf.Covers(row.key, R.string.home_recently_added_shows, titles.recentShows, showsStatus = true)
        HomeRowKind.RECENT_MOVIES ->
            HomeShelf.Covers(row.key, R.string.home_recently_added_movies, titles.recentMovies, showsStatus = true)
        // A Favourite shows only its title: where the Profile is with it is Continue Watching's job.
        HomeRowKind.FAVOURITE_SHOWS ->
            HomeShelf.Covers(row.key, R.string.home_favourite_shows, titles.favouriteShows, showsStatus = false)
        HomeRowKind.FAVOURITE_MOVIES ->
            HomeShelf.Covers(row.key, R.string.home_favourite_movies, titles.favouriteMovies, showsStatus = false)
        HomeRowKind.FAVOURITE_CHANNELS -> HomeShelf.Channels(favouriteChannels)
    }
}

/** Home's rows that have something in them: those [shown], top to bottom, and whether any are hidden. */
private class HomeShelves(val shown: List<HomeShelf>, val anyHidden: Boolean)

/**
 * Home's rows with something in them, the Live Preview first and the Home Rows in the Profile's order.
 * A row still on its way ([loading]) holds its place, so it never pushes the rows below down when it
 * lands.
 */
private fun homeShelves(
    live: LiveTile?,
    cards: HomeCards,
    arrangement: HomeArrangement,
    loading: Set<HomeRowKind>,
    liveLoading: Boolean,
): HomeShelves {
    val (shownRows, hiddenRows) = arrangement.rows.partition(arrangement::isShown)
    val rows = shownRows.mapNotNull { row ->
        val shelf = cards.shelf(row)
        when {
            shelf.row.items.isNotEmpty() -> shelf
            row in loading -> HomeShelf.Skeleton(row, shelf.title)
            else -> null
        }
    }
    val lead = when {
        !arrangement.livePreview -> null
        live != null -> HomeShelf.Live(live)
        liveLoading -> HomeShelf.LivePlace()
        else -> null
    }
    val shown = listOfNotNull(lead) + rows
    val anyHidden = (live != null && !arrangement.livePreview) ||
        hiddenRows.any { cards.shelf(it).row.items.isNotEmpty() }
    return HomeShelves(shown, anyHidden)
}

private const val LIVE_PICK = "live"

/** The Live Preview's key in Home's list, its place's and its tile's alike, so one replaces the other in place. */
private const val LIVE_KEY = "live"

// A pick is kept by the provider's ids rather than the local row id, which a catalogue refresh can
// change while the viewer is away. Movies and shows share a row, so each says which it is.
internal val VodTitle.pickKey
    get() = when (this) {
        is VodTitle.OfMovie -> "movie:${movie.sourceId}:${movie.streamId}"
        is VodTitle.OfSeries -> "series:${series.sourceId}:${series.seriesId}"
    }
private val Channel.pickKey get() = "$sourceId:$streamId"

@Composable
internal fun ScreenFrameScope.ContinueWatchingShelf(
    cards: List<ResumeItem>,
    large: Boolean,
    focus: CardFocus?,
    onResume: (ResumeItem) -> Unit,
    leave: (ResumeItem) -> LeaveScreen,
    onHighlight: (index: Int, card: ResumeItem) -> Unit = { _, _ -> },
) {
    val memory = rememberShelfMemory(remember(cards) { cards.map { it.titleKey } })
    val openOptions = LocalOpenOptions.current
    val rowState = rememberRowState(focus)
    // A card removed while highlighted (Remove from Continue Watching, Mark watched) hands the
    // Highlight to the one taking its place, not the row's first.
    val highlight = rememberRowHighlight(cards, rowState) { it.titleKey }
    Shelf(
        stringResource(R.string.vod_continue_watching),
        memory,
        state = rowState,
        // With the large first card, the cards hang from the top, right under the row's title. On a
        // shared bottom edge the smaller cards sat under an empty band the large one's extra height
        // tall, which read as something missing above them.
        verticalAlignment = if (large) Alignment.Top else Alignment.Bottom,
    ) {
        itemsIndexed(cards, key = { _, card -> card.titleKey }) { index, card ->
            ArtCard(
                title = card.title,
                artworkUrl = card.posterUrl,
                onClick = { onResume(card) },
                size = if (large && index == 0) ArtCardSize.Large else ArtCardSize.Landscape,
                modifier = Modifier
                    .takesFocus(index, focus)
                    .shelfCard(memory, card.titleKey)
                    .then(highlight.modifier(index, card.titleKey))
                    .onFocusChanged { if (it.isFocused) onHighlight(index, card) },
                status = resumeStatusText(card),
                progress = card.progress,
                badge = rememberCoverBadge(
                    StreamKind.MOVIE,
                    (card.key as? MediaKey.Movie)?.movieId ?: NOT_A_ROW,
                    card.name,
                ),
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
    val rowState = rememberRowState(focus)
    val highlight = rememberRowHighlight(covers, rowState) { it.title.pickKey }
    Shelf(title, memory, state = rowState) {
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
                modifier = Modifier
                    .takesFocus(index, focus)
                    .shelfCard(memory, cover.title.pickKey)
                    .then(highlight.modifier(index, cover.title.pickKey)),
                status = if (showsStatus) coverStatusText(cover.status) else emptyList(),
                progress = (cover.status as? CoverStatus.InProgress)?.progress?.takeIf { showsStatus },
                badge = rememberCoverBadge(cover.title.kind, cover.title.id, cover.title.name),
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

/** An id no row has: a Continue Watching episode's cover goes by its own name, not its show's Versions. */
private const val NOT_A_ROW = -1L
