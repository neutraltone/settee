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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.shownName
import app.opentv.data.parser.displayTitle
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.RecentSearchesViewModel
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.TextField
import app.opentv.ui.options.LeaveScreen
import app.opentv.ui.options.LocalConfirmLive
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.optionsClickable
import app.opentv.ui.options.rememberRowHighlight
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    vodViewModel: VodViewModel = viewModel(),
    recentSearches: RecentSearchesViewModel = viewModel(),
) {
    val query = rememberTextFieldState()
    val channelResults by viewModel.searchResults.collectAsState()
    val movieResults by vodViewModel.movieResults.collectAsState()
    val seriesResults by vodViewModel.seriesResults.collectAsState()
    val recent by recentSearches.shown.collectAsState()

    LaunchedEffect(query) {
        snapshotFlow { query.text.toString() }.collect {
            viewModel.setSearchQuery(it)
            vodViewModel.setVodSearchQuery(it)
        }
    }
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
) {
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()
    val searchBox = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val confirmLive = LocalConfirmLive.current
    val typed = query.text.toString()
    // A chosen Recent Search whose results haven't arrived. They take a moment (the view models
    // debounce), so the highlight waits in the box, and stays there if the viewer does anything else.
    var awaitingResultsOf by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { searchBox.requestFocus() } }
    LaunchedEffect(recentSearches?.isEmpty()) {
        if (recentSearches?.isEmpty() == true) runCatching { searchBox.requestFocus() }
    }
    LaunchedEffect(typed, channelResults, movieResults, seriesResults) {
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
            Text(stringResource(R.string.nav_search), style = MaterialTheme.typography.headlineMedium)
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
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
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
                    onPlayChannel = {
                        confirmLive {
                            onResultOpened(typed)
                            onPlayChannel(it)
                        }
                    },
                    onPlayMovie = {
                        onResultOpened(typed)
                        onPlayMovie(it)
                    },
                    onOpenSeries = {
                        onResultOpened(typed)
                        onOpenSeries(it)
                    },
                    onLeave = { go ->
                        onResultOpened(typed)
                        go()
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    channelResults: List<ChannelsViewModel.Row>,
    movieResults: List<Movie>,
    seriesResults: List<Series>,
    onPlayChannel: (Channel) -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onLeave: LeaveScreen,
) {
    val listState = rememberLazyListState()
    // The rows start after the section's header.
    val highlight = rememberRowHighlight(channelResults, listState, firstRowItem = 1) { it.key }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (channelResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.common_channels)) }
            itemsIndexed(
                channelResults,
                key = { _, row -> "c${row.key}" },
                contentType = { _, _ -> "channel" },
            ) { index, row ->
                SearchResultRow(
                    row = row,
                    onClick = { onPlayChannel(row.primary) },
                    modifier = highlight.modifier(index, row.key),
                )
            }
        }
        if (movieResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.nav_movies)) }
            items(movieResults, key = { "m${it.id}" }, contentType = { "title" }) { movie ->
                VodResultRow(
                    movie.displayTitle,
                    movie.posterUrl,
                    movie.year?.toString(),
                    onOptions = openOptionsOn(OptionsSubject.OfMovie(movie), onLeave),
                ) {
                    onPlayMovie(movie)
                }
            }
        }
        if (seriesResults.isNotEmpty()) {
            item(contentType = "header") { SearchSectionHeader(stringResource(R.string.nav_shows)) }
            items(seriesResults, key = { "s${it.id}" }, contentType = { "title" }) { show ->
                VodResultRow(
                    show.displayTitle,
                    show.posterUrl,
                    show.year?.toString(),
                    onOptions = openOptionsOn(OptionsSubject.OfSeries(show), onLeave),
                ) {
                    onOpenSeries(show)
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
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
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
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(
    name: String,
    posterUrl: String?,
    subtitle: String?,
    onOptions: (() -> Unit)?,
    onClick: () -> Unit,
) {
    SearchRow(onClick = onClick, onOptions = onOptions) {
        AsyncImage(
            model = posterUrl,
            contentDescription = null,
            modifier = Modifier.size(width = 34.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.primary.shownName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.now?.let { "${searchTime(it.startUtcMillis)}  ${it.title}" }
                    ?: stringResource(R.string.guide_no_info),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val searchTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun searchTime(utcMillis: Long): String = searchTimeFormat.format(Date(utcMillis))

/** The longest search either search box takes, the Search screen's or the Channel manager's. */
internal const val MAX_SEARCH_LENGTH = 40
