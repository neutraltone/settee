/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import app.opentv.ui.options.optionsClickable
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    recentSearches: List<String> = emptyList(),
    onResultOpened: (String) -> Unit = {},
    onClearRecentSearches: () -> Unit = {},
) {
    val anyResults = channelResults.isNotEmpty() || movieResults.isNotEmpty() || seriesResults.isNotEmpty()
    val searchBox = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val typed = query.text.toString()
    // A chosen Recent Search whose results haven't arrived. They take a moment (the view models
    // debounce), so the highlight waits in the box; the row it was on has gone by then.
    var awaitingResultsOf by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { runCatching { searchBox.requestFocus() } }
    LaunchedEffect(recentSearches.isEmpty()) {
        if (recentSearches.isEmpty()) runCatching { searchBox.requestFocus() }
    }
    LaunchedEffect(typed, anyResults) {
        val awaited = awaitingResultsOf ?: return@LaunchedEffect
        if (typed != awaited) {
            awaitingResultsOf = null
        } else if (anyResults) {
            awaitingResultsOf = null
            withFrameNanos {}
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
            DeviceTextField(
                state = query,
                placeholder = stringResource(R.string.search_type_name),
                maxLength = MAX_SEARCH_LENGTH,
                imeAction = ImeAction.Search,
                modifier = Modifier.weight(1f).focusRequester(searchBox),
            )
            Spacer(Modifier.width(20.dp))
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
        }

        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxSize()) {
            when {
                typed.isBlank() && recentSearches.isNotEmpty() -> RecentSearchList(
                    recentSearches = recentSearches,
                    onChoose = { text ->
                        query.setTextAndPlaceCursorAtEnd(text)
                        awaitingResultsOf = text
                        runCatching { searchBox.requestFocus() }
                    },
                    onClear = onClearRecentSearches,
                )
                typed.isBlank() -> Hint(stringResource(R.string.search_start_hint))
                typed.trim().length < 2 -> Hint(stringResource(R.string.common_keep_typing))
                !anyResults -> Hint(stringResource(R.string.search_no_results, typed))
                else -> SearchResults(
                    channelResults = channelResults,
                    movieResults = movieResults,
                    seriesResults = seriesResults,
                    onPlayChannel = {
                        onResultOpened(typed)
                        onPlayChannel(it)
                    },
                    onPlayMovie = {
                        onResultOpened(typed)
                        onPlayMovie(it)
                    },
                    onOpenSeries = {
                        onResultOpened(typed)
                        onOpenSeries(it)
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
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (channelResults.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.common_channels)) }
            items(channelResults, key = { "c${it.key}" }) { row ->
                SearchResultRow(row = row, onClick = { onPlayChannel(row.primary) })
            }
        }
        if (movieResults.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.nav_movies)) }
            items(movieResults, key = { "m${it.id}" }) { movie ->
                VodResultRow(movie.displayTitle, movie.posterUrl, movie.year?.toString()) {
                    onPlayMovie(movie)
                }
            }
        }
        if (seriesResults.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.nav_shows)) }
            items(seriesResults, key = { "s${it.id}" }) { show ->
                VodResultRow(show.displayTitle, show.posterUrl, show.year?.toString()) {
                    onOpenSeries(show)
                }
            }
        }
    }
}

/**
 * The Profile's Recent Searches, for an empty box. A plain column, not a lazy one: there are ten at
 * most, and the highlight has to be able to land on any of them when a neighbour is removed.
 */
@Composable
private fun RecentSearchList(recentSearches: List<String>, onChoose: (String) -> Unit, onClear: () -> Unit) {
    val rows = remember(recentSearches.size) { List(recentSearches.size) { FocusRequester() } }
    var highlighted by remember { mutableStateOf<Int?>(null) }
    var previousSize by remember { mutableIntStateOf(recentSearches.size) }

    // Removing the highlighted row would otherwise drop the highlight, leaving the remote nothing to move.
    LaunchedEffect(recentSearches.size) {
        val removed = recentSearches.size < previousSize
        previousSize = recentSearches.size
        val at = highlighted
        if (removed && at != null) runCatching { rows[at.coerceAtMost(rows.lastIndex)].requestFocus() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader(stringResource(R.string.search_recent_title))
        recentSearches.forEachIndexed { i, text ->
            key(text) {
                RecentSearchRow(
                    text = text,
                    onClick = { onChoose(text) },
                    modifier = Modifier
                        .focusRequester(rows[i])
                        .onFocusChanged { if (it.isFocused) highlighted = i },
                )
            }
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.search_clear_recent))
        }
    }
}

@Composable
private fun RecentSearchRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
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
            .optionsClickable(onClick = onClick, onOptions = openOptionsOn(OptionsSubject.OfRecentSearch(text)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun VodResultRow(name: String, posterUrl: String?, subtitle: String?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
private fun SearchResultRow(row: ChannelsViewModel.Row, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
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
