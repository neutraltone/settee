/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.VodTitle
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.Message
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.options.OptionsSubject
import app.opentv.ui.options.openOptionsOn
import kotlinx.coroutines.delay

/** A person's page, opened from a title's Cast & crew: everything in the library with [name] in it. */
@Composable
fun PersonScreen(
    name: String,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var titles by remember(name) { mutableStateOf<List<VodTitle>?>(null) }
    LaunchedEffect(name) { titles = viewModel.titlesWithPerson(name) }
    PersonContent(name, titles, onOpenMovie, onOpenSeries)
}

/**
 * [name]'s page once known: their name, then [titles] (films they're in or directed, then shows
 * they're in) as covers, the Highlight starting on the first. [titles] is null while they load,
 * which shows just the name: the lookup is quick, and a loading line would only flash.
 *
 * Back from a title opened here builds the page again, so the title last highlighted is saved, and
 * the Highlight goes back to it rather than to the first (#384); to the first when it has gone.
 */
@Composable
internal fun PersonContent(
    name: String,
    titles: List<VodTitle>?,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
) {
    val grid = rememberLazyGridState()
    var last by rememberSaveable(name) { mutableStateOf<String?>(null) }
    val start = remember { FocusRequester() }
    val startKey = remember(titles) { (titles?.firstOrNull { it.key == last } ?: titles?.firstOrNull())?.key }
    LaunchedEffect(titles) {
        if (titles.isNullOrEmpty()) return@LaunchedEffect
        delay(FIRST_HIGHLIGHT_DELAY_MILLIS)
        val index = titles.indexOfFirst { it.key == startKey }.coerceAtLeast(0)
        if (grid.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            grid.scrollToItem(index)
            // The cover is composed with the requester on the frame after it's scrolled to.
            withFrameNanos {}
        }
        runCatching { start.requestFocus() }
    }
    ScreenFrame {
        Column(Modifier.fillMaxSize()) {
            Text(name, style = SetteeType.Hero, maxLines = 1)
            when {
                titles == null -> Unit
                titles.isEmpty() -> Message(stringResource(R.string.vod_person_empty, name))
                else -> LazyVerticalGrid(
                    state = grid,
                    // Columns as wide as a cover, as Movies' and Shows' grids have, so covers match.
                    columns = GridCells.FixedSize(ArtCardSize.Portrait.width),
                    contentPadding = PaddingValues(vertical = GRID_TOP),
                    horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                    verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(titles, key = { it.key }) { title ->
                        PersonCover(
                            title,
                            onOpenMovie,
                            onOpenSeries,
                            (if (title.key == startKey) Modifier.focusRequester(start) else Modifier)
                                .onFocusChanged { if (it.isFocused) last = title.key },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCover(
    title: VodTitle,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    modifier: Modifier,
) = when (title) {
    is VodTitle.OfMovie -> ArtCard(
        title = title.movie.displayTitle,
        artworkUrl = title.movie.posterUrl,
        onClick = { onOpenMovie(title.movie) },
        size = ArtCardSize.Portrait,
        modifier = modifier,
        onOptions = openOptionsOn(OptionsSubject.OfMovie(title.movie)),
    )
    is VodTitle.OfSeries -> ArtCard(
        title = title.series.displayTitle,
        artworkUrl = title.series.posterUrl,
        onClick = { onOpenSeries(title.series) },
        size = ArtCardSize.Portrait,
        modifier = modifier,
        onOptions = openOptionsOn(OptionsSubject.OfSeries(title.series)),
    )
}

/** A film and a show can share a local id, so the grid keys them by kind too. */
private val VodTitle.key: String
    get() = when (this) {
        is VodTitle.OfMovie -> "movie:${movie.id}"
        is VodTitle.OfSeries -> "series:${series.id}"
    }

/** Room above the first row for a highlighted cover to grow into. */
private val GRID_TOP = 24.dp

/** As the detail pages wait: long enough for the grid to be laid out. */
private const val FIRST_HIGHLIGHT_DELAY_MILLIS = 60L
