/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.StremioStream
import app.opentv.data.parser.displayTitle
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.ui.VodViewModel
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.PillButton
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * A film's page. Opening it enriches the row from the provider (backdrop, genre and so on) if those
 * fields are still bare, so a title that was plain in a grid fills out here.
 */
@Composable
fun MovieDetailScreen(
    movieId: Long,
    onPlay: (Movie) -> Unit,
    onPlayAddonStream: (MediaKey.AddonStream) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var movie by remember(movieId) { mutableStateOf<Movie?>(null) }
    var resumes by remember(movieId) { mutableStateOf(false) }
    val hasAddons by viewModel.hasAddons.collectAsState()

    LaunchedEffect(movieId) {
        val loaded = viewModel.movieDetail(movieId)
        movie = loaded
        if (loaded != null) resumes = viewModel.resumesPartway(loaded)
    }

    val m = movie
    val status by remember(m) { m?.let { viewModel.movieStatus(it) } ?: flowOf(null) }.collectAsState(initial = null)
    // Null until the Profile's Favourites have loaded: the star stays put and ignores a press till then.
    val favouriteMovies by viewModel.favouriteMovies.collectAsState()
    if (m == null) {
        LoadingDetail()
        return
    }

    MovieDetailContent(
        movie = m,
        resumes = resumes,
        status = status,
        favourite = favouriteMovies?.any { it.id == m.id },
        findAddonStreams = if (hasAddons) ({ viewModel.addonStreams(m) }) else null,
        onPlay = { onPlay(m) },
        onPlayAddonStream = { onPlayAddonStream(MediaKey.AddonStream(m.id, it.url)) },
        onSetFavourite = { viewModel.setMovieFavourite(m, it) },
    )
}

/**
 * A film's page once the film is known: the hero and nothing beneath it. The main button resumes
 * when [resumes], else plays. [findAddonStreams] is null unless add-ons are set up, and then adds an
 * Add-on sources button, last, whose picker plays the stream chosen. [favourite] is null until the
 * Profile's Favourites have loaded, and the toggle does nothing till then.
 */
@Composable
internal fun MovieDetailContent(
    movie: Movie,
    resumes: Boolean,
    status: CoverStatus?,
    favourite: Boolean?,
    findAddonStreams: (suspend () -> List<StremioStream>)?,
    onPlay: () -> Unit,
    onPlayAddonStream: (StremioStream) -> Unit,
    onSetFavourite: (Boolean) -> Unit,
) {
    val playFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Null while the picker is closed; Loading while the add-ons are asked.
    var picker by remember(movie.id) { mutableStateOf<AddonPicker?>(null) }
    // Closing the picker cancels its lookup, so an answer arriving later can't open it again.
    var lookup by remember(movie.id) { mutableStateOf<Job?>(null) }

    LaunchedEffect(movie.id) {
        delay(60)
        runCatching { playFocus.requestFocus() }
    }

    ScreenFrame(backdrop = { HeroBackdrop(movie.backdropUrl, movie.posterUrl) }) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Text(movie.displayTitle, style = SetteeType.Hero, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            HeroMetaLine(
                heroMeta(
                    firstGenre(movie.genre),
                    movie.year?.toString(),
                    movie.durationSeconds?.takeIf { it > 0 }?.let { runtimeText(it) },
                    heroStatusText(status),
                ),
            )
            movie.plot?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = SetteeType.Synopsis, maxLines = 4, modifier = Modifier.fillMaxWidth(0.6f))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap)) {
                PillButton(
                    label = stringResource(if (resumes) R.string.vod_resume else R.string.vod_play),
                    icon = Icons.Filled.PlayArrow,
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocus),
                )
                FavouriteButton(favourite, onSetFavourite)
                if (findAddonStreams != null) {
                    PillButton(
                        label = stringResource(R.string.vod_addon_sources),
                        icon = Icons.Filled.Extension,
                        onClick = {
                            picker = AddonPicker.Loading
                            lookup?.cancel()
                            lookup = scope.launch { picker = AddonPicker.Found(findAddonStreams()) }
                        },
                    )
                }
            }
        }
    }

    picker?.let { shown ->
        AddonStreamPicker(
            picker = shown,
            onPick = { stream ->
                lookup?.cancel()
                picker = null
                onPlayAddonStream(stream)
            },
            onDismiss = {
                lookup?.cancel()
                picker = null
            },
        )
    }
}

/** What the add-on picker shows. */
private sealed interface AddonPicker {
    data object Loading : AddonPicker

    /** The streams the add-ons returned, already only those that play directly. */
    data class Found(val streams: List<StremioStream>) : AddonPicker
}

/** The streams a film's add-ons offer: a line while they're asked, then one row per stream. */
@Composable
private fun AddonStreamPicker(picker: AddonPicker, onPick: (StremioStream) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(560.dp)
                .background(SetteeColors.Surface, SetteeShapes.Card)
                .padding(24.dp),
        ) {
            Text(stringResource(R.string.vod_addon_pick_title), style = SetteeType.Section)
            Spacer(Modifier.height(12.dp))
            when {
                picker is AddonPicker.Loading -> Text(
                    stringResource(R.string.vod_addon_loading),
                    style = SetteeType.Body,
                    color = SetteeColors.QuietText,
                )
                picker is AddonPicker.Found && picker.streams.isEmpty() -> Text(
                    stringResource(R.string.vod_addon_none),
                    style = SetteeType.Body,
                    color = SetteeColors.QuietText,
                )
                picker is AddonPicker.Found -> StreamList(picker.streams, onPick)
            }
        }
    }
}

@Composable
private fun StreamList(streams: List<StremioStream>, onPick: (StremioStream) -> Unit) {
    val first = remember { FocusRequester() }
    // Asked from inside the dialog: its window composes after the page's, and a FocusRequester asked
    // before it is attached throws.
    LaunchedEffect(streams) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.heightIn(max = 360.dp)) {
        // Not keyed by URL: two add-ons can offer the same link, and a repeated key would crash the list.
        itemsIndexed(streams) { index, stream ->
            HighlightSurface(
                onClick = { onPick(stream) },
                style = HighlightStyle.Row,
                modifier = Modifier.fillMaxWidth().then(
                    if (stream ==
                        streams.first()
                    ) {
                        Modifier.focusRequester(first)
                    } else {
                        Modifier
                    },
                ),
            ) { highlighted ->
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        stream.title,
                        style = SetteeType.RowTitle,
                        color = if (highlighted) SetteeColors.OnAccent else SetteeColors.Text,
                        maxLines = 2,
                    )
                    Text(
                        stream.addonName,
                        style = SetteeType.Body,
                        color = if (highlighted) SetteeColors.OnAccent else SetteeColors.QuietText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
