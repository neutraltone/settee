/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.AddonTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecentSearchesViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.activityViewModelStoreOwner
import app.opentv.ui.clockTime
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.design.TextField
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.optionsClickable
import app.opentv.ui.options.rememberRowHighlight
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search across channels, movies and shows, with matches shown live as you type.
 *
 * The box is a plain text field so the device's own keyboard opens: on a Fire TV that keyboard
 * takes dictation from the remote's mic, which a keyboard drawn by the app never could.
 */
@Composable
fun SearchScreen(
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = viewModel(),
    vodViewModel: VodViewModel = viewModel(activityViewModelStoreOwner()),
    recentSearches: RecentSearchesViewModel = viewModel(),
) {
    val query = rememberTextFieldState()
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val addonResults by vodViewModel.addonResults.collectAsState()
    val recent by recentSearches.shown.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect {
            viewModel.setSearchQuery(it)
            vodViewModel.setVodSearchQuery(it)
        }
    }
    // The VOD model is the app's, and outlives this visit: left holding the query, its results would
    // still be there next time, ready to be taken for the next search's while it loads. Cleared as
    // the visit ends, not as the screen leaves the composition, which it also does under a title page
    // or the player: the films and shows then emptied, and came back only after the search ran again.
    viewModel { SearchVisit(vodViewModel) }
    BackHandler { onBack() }

    SearchContent(
        query = query,
        channelResults = channelResults,
        movieResults = movieResults,
        seriesResults = seriesResults,
        onPlayChannel = { channel ->
            viewModel.zapFromAll()
            onPlayChannel(channel)
        },
        onPlayMovie = onPlayMovie,
        onOpenSeries = onOpenSeries,
        onBack = onBack,
        recentSearches = recent,
        onResultOpened = recentSearches::save,
        onClearRecentSearches = recentSearches::clear,
        addonResults = addonResults,
        // Kept as it's opened, then shown as any film is: its page reads it from the database.
        onOpenAddonFilm = { film -> scope.launch { onPlayMovie(vodViewModel.keepAddonFilm(film)) } },
    )
}

/** Long enough for each kind of result to land, short enough not to feel like a wait. */
private const val RESULTS_SETTLE_MILLIS = 150L

/** The search screen's body, apart from its view models, so it can be shown in a test. */
@Composable
internal fun SearchContent(
    query: TextFieldState,
    channelResults: List<ChannelsViewModel.Row>,
    movieResults: List<Movie>,
    seriesResults: List<Series>,
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    recentSearches: List<String>? = emptyList(),
    onResultOpened: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
    addonResults: List<AddonTitle> = emptyList(),
    onOpenAddonFilm: (AddonTitle) -> Unit = {},
) {
    val anyResults = listOf(channelResults, movieResults, seriesResults, addonResults).any { it.isNotEmpty() }
    val searchBox = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val confirmLive = LocalConfirmLive.current
    val typed = query.text.toString()
    // A chosen Recent Search whose results haven't arrived. They take a moment (the view models
    // debounce), so the highlight waits in the box, and stays there if the viewer does anything else.
    var awaitingResultsOf by remember { mutableStateOf<String?>(null) }
    // Search is built again on Back from a result it opened (a title page or the player covered it),
    // so the result is saved by its list key, and the highlight goes back to it, not to the box (#431).
    var openedResult by rememberSaveable { mutableStateOf<String?>(null) }
    // Decided as Search is built, from the results it comes back to, and used once: the results
    // give way to a hint as the search is cut to one letter, and retyping it mustn't pull the
    // highlight out of the box.
    var returnTo by remember {
        mutableStateOf(
            openedResult?.takeIf { itemIndexOf(it, channelResults, movieResults, seriesResults, addonResults) != null },
        )
    }

    LaunchedEffect(Unit) { if (returnTo == null) runCatching { searchBox.requestFocus() } }
    // Only while the Recent Searches show: built again over results, the box mustn't take the highlight.
    LaunchedEffect(recentSearches?.isEmpty()) {
        if (recentSearches?.isEmpty() == true && typed.isBlank()) runCatching { searchBox.requestFocus() }
    }
    LaunchedEffect(typed, channelResults, movieResults, seriesResults, addonResults) {
        val awaited = awaitingResultsOf ?: return@LaunchedEffect
        if (typed != awaited) {
            awaitingResultsOf = null
        } else if (anyResults) {
            // Channels, movies and shows arrive separately: moving on the first to land could leave
            // the highlight on a movie with channels about to appear above it.
            delay(RESULTS_SETTLE_MILLIS)
            awaitingResultsOf = null
            focusManager.moveFocus(FocusDirection.Down)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_search), style = SetteeType.ScreenTitle)
            Spacer(Modifier.width(20.dp))
            TextField(
                state = query,
                placeholder = stringResource(R.string.search_type_name),
                maxLength = MAX_SEARCH_LENGTH,
                imeAction = ImeAction.Search,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchBox)
                    .onFocusChanged { if (!it.hasFocus) awaitingResultsOf = null }
                    .onPreviewKeyEvent {
                        awaitingResultsOf = null
                        false
                    },
            )
            Spacer(Modifier.width(20.dp))
            PillButton(stringResource(R.string.common_done), onClick = onBack)
        }

        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxSize()) {
            when {
                typed.isBlank() -> EmptySearchBox(
                    recentSearches = recentSearches,
                    onChoose = { text ->
                        query.setTextAndPlaceCursorAtEnd(text)
                        runCatching { searchBox.requestFocus() }
                        awaitingResultsOf = text
                    },
                    onClear = onClearRecentSearches,
                )
                typed.trim().length < 2 -> SearchHint(stringResource(R.string.common_keep_typing))
                !anyResults -> SearchHint(stringResource(R.string.search_no_results, typed))
                else -> SearchResults(
                    channelResults = channelResults,
                    movieResults = movieResults,
                    seriesResults = seriesResults,
                    addonResults = addonResults,
                    returnTo = returnTo,
                    returnFallback = searchBox,
                    onReturned = { returnTo = null },
                    // A channel counts as opened once its play is confirmed: kept, it's left alone.
                    onPlayChannel = { row ->
                        confirmLive {
                            openedResult = row.resultKey
                            onResultOpened(typed)
                            onPlayChannel(row.primary)
                        }
                    },
                    onPlayMovie = {
                        openedResult = it.resultKey
                        onResultOpened(typed)
                        onPlayMovie(it)
                    },
                    onOpenSeries = {
                        openedResult = it.resultKey
                        onResultOpened(typed)
                        onOpenSeries(it)
                    },
                    onOpenAddonFilm = {
                        openedResult = it.resultKey
                        onResultOpened(typed)
                        onOpenAddonFilm(it)
                    },
                    onLeave = { key, go ->
                        openedResult = key
                        onResultOpened(typed)
                        go()
                    },
                )
            }
        }
    }
}

// Each result's key in SearchResults' list, and what Search saves of the one it opened.
private val ChannelsViewModel.Row.resultKey get() = "c$key"
private val Movie.resultKey get() = "m$id"
private val Series.resultKey get() = "s$id"
private val AddonTitle.resultKey get() = "a$imdbId"

/**
 * Where the result keyed [key] stands in [SearchResults]' list, headers counted; null if it isn't
 * there. Kept in step with that list's sections, each a header and then its rows.
 */
private fun itemIndexOf(
    key: String,
    channelResults: List<ChannelsViewModel.Row>,
    movieResults: List<Movie>,
    seriesResults: List<Series>,
    addonResults: List<AddonTitle>,
): Int? {
    val sections = listOf(
        channelResults.map { it.resultKey },
        movieResults.map { it.resultKey },
        seriesResults.map { it.resultKey },
        addonResults.map { it.resultKey },
    )
    var start = 0
    for (section in sections) {
        if (section.isEmpty()) continue
        val row = section.indexOf(key)
        if (row >= 0) return start + 1 + row
        start += 1 + section.size
    }
    return null
}

/**
 * The results, the highlight going to the one keyed [returnTo] as they're first shown, scrolled to
 * if it's out of sight, or to [returnFallback] if it can't; then [onReturned] is told. [onLeave] is
 * told the key of the result whose options leave Search.
 */
@Composable
private fun SearchResults(
    channelResults: List<ChannelsViewModel.Row>,
    movieResults: List<Movie>,
    seriesResults: List<Series>,
    addonResults: List<AddonTitle>,
    returnTo: String?,
    returnFallback: FocusRequester,
    onReturned: () -> Unit,
    onPlayChannel: (ChannelsViewModel.Row) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onOpenAddonFilm: (AddonTitle) -> Unit,
    onLeave: (key: String, go: () -> Unit) -> Unit,
) {
    val listState = rememberLazyListState()
    // The rows start after the section's header.
    val highlight = rememberRowHighlight(channelResults, listState, itemIndex = { 1 + it }) { it.key }
    val returnRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        val index = returnTo?.let { itemIndexOf(it, channelResults, movieResults, seriesResults, addonResults) }
            ?: return@LaunchedEffect
        // A frame for the list to be laid out where its saved scroll left it.
        withFrameNanos {}
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem(index)
            // As RowHighlight waits: a frame resumes this before it recomposes, so the row wears the
            // requester a frame later.
            repeat(2) { withFrameNanos {} }
        }
        // Failing that, the fallback (the box): the highlight must land somewhere.
        runCatching { returnRequester.requestFocus() }
            .onFailure { runCatching { returnFallback.requestFocus() } }
        onReturned()
    }
    fun Modifier.returning(key: String) = if (key == returnTo) focusRequester(returnRequester) else this
    fun leave(key: String): LeaveScreen = { go -> onLeave(key, go) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (channelResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.common_channels)) }
            itemsIndexed(
                channelResults,
                key = { _, row -> row.resultKey },
                contentType = { _, _ -> "channel" },
            ) { index, row ->
                SearchResultRow(
                    row = row,
                    onClick = { onPlayChannel(row) },
                    modifier = highlight.modifier(index, row.key).returning(row.resultKey),
                )
            }
        }
        if (movieResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.nav_movies)) }
            items(movieResults, key = { it.resultKey }, contentType = { "title" }) { movie ->
                VodResultRow(
                    movie.displayTitle,
                    movie.posterUrl,
                    movie.year?.toString(),
                    onOptions = openOptionsOn(OptionsSubject.OfMovie(movie), leave(movie.resultKey)),
                    modifier = Modifier.returning(movie.resultKey),
                ) {
                    onPlayMovie(movie)
                }
            }
        }
        if (seriesResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.nav_shows)) }
            items(seriesResults, key = { it.resultKey }, contentType = { "title" }) { show ->
                VodResultRow(
                    show.displayTitle,
                    show.posterUrl,
                    show.year?.toString(),
                    onOptions = openOptionsOn(OptionsSubject.OfSeries(show), leave(show.resultKey)),
                    modifier = Modifier.returning(show.resultKey),
                ) {
                    onOpenSeries(show)
                }
            }
        }
        // Last, after everything the Provider has: only there when an add-on is set up. No options:
        // nothing is kept of these till one is opened.
        if (addonResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.search_not_from_provider)) }
            items(addonResults, key = { it.resultKey }, contentType = { "title" }) { film ->
                VodResultRow(
                    film.name,
                    film.posterUrl,
                    film.year?.toString(),
                    onOptions = null,
                    modifier = Modifier.returning(film.resultKey),
                ) {
                    onOpenAddonFilm(film)
                }
            }
        }
    }
}

/** One row of the search screen, with the same highlight whatever it holds. */
@Composable
internal fun SearchRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOptions: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(SetteeShapes.Card)
            .background(if (focused) SetteeColors.Raised else SetteeColors.Surface)
            .then(
                if (focused) {
                    Modifier.border(2.dp, SetteeColors.Accent, SetteeShapes.Card)
                } else {
                    Modifier
                },
            )
            .optionsClickable(onClick = onClick, onOptions = onOptions)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun SearchHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = SetteeType.RowTitle, color = SetteeColors.QuietText)
    }
}

@Composable
internal fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = SetteeType.RowTitle,
        color = SetteeColors.Accent,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(
    name: String,
    posterUrl: String?,
    subtitle: String?,
    onOptions: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SearchRow(onClick = onClick, modifier = modifier, onOptions = onOptions) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(SetteeShapes.Thumbnail),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = SetteeType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = SetteeType.Body,
                    color = SetteeColors.QuietText,
                )
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SearchRow(
        onClick = onClick,
        modifier = modifier,
        onOptions = openOptionsOn(OptionsSubject.OfChannel(row.channel, onWatch = onClick)),
    ) {
        AsyncImage(
            model = row.primary.logoUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(SetteeShapes.Thumbnail),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = SetteeType.RowTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${clockTime(it.startUtcMillis)}  ${it.title}" }
                    ?: stringResource(R.string.guide_no_info),
                style = SetteeType.Body,
                color = SetteeColors.QuietText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The longest search either search box takes, the Search screen's or the Channel manager's. */
internal const val MAX_SEARCH_LENGTH = 40

/**
 * One visit to Search: kept by its back stack entry, so it lasts while a title page or the player
 * covers Search and ends only when Search is left, which empties the shared VOD search.
 */
private class SearchVisit(private val vod: VodViewModel) : ViewModel() {
    private val visit = vod.searchVisitStarted()

    override fun onCleared() = vod.searchVisitEnded(visit)
}
