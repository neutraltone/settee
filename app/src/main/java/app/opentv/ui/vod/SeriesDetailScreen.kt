/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.ShowEpisodes
import app.opentv.ui.VodViewModel
import kotlinx.coroutines.launch

/**
 * A show's page. Episodes are fetched when it opens rather than during catalogue sync: pulling every
 * episode of every series up front is what makes a first sync take twenty minutes on a large
 * provider, and most of it is never looked at.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: Long,
    onPlayEpisode: (MediaKey) -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: VodViewModel = viewModel(),
) {
    var series by remember(seriesId) { mutableStateOf<Series?>(null) }
    var fetched by remember(seriesId) { mutableStateOf(false) }

    LaunchedEffect(seriesId) {
        viewModel.openShow(seriesId).collect {
            series = it.series
            fetched = it.fetched
        }
    }

    val s = series
    if (s == null) LoadingDetail() else ShowPage(s, fetched, onPlayEpisode, onOpenPerson, viewModel)
}

/** [series]' page once its row is read, [fetched] once its episodes have been asked for. */
@Composable
private fun ShowPage(
    series: Series,
    fetched: Boolean,
    onPlayEpisode: (MediaKey) -> Unit,
    onOpenPerson: (String) -> Unit,
    viewModel: VodViewModel,
) {
    val scope = rememberCoroutineScope()
    var versions by remember(series.id) { mutableStateOf<Pair<MediaKey, PageVersions>?>(null) }
    // One row per episode across the show's Versions, and each Version's for what's been watched (#181).
    // Not collectAsState: the series is shown again once TMDB has filled it in, and a fresh read
    // would start from nothing, emptying the episode list for a moment under the highlight.
    val read = produceState<ShowEpisodes?>(null, series) { viewModel.showEpisodes(series).collect { value = it } }.value
    val show = read ?: ShowEpisodes()
    val played by viewModel.episodeStatuses.collectAsState(initial = EpisodeStatuses())
    val statuses = remember(show, played) { show.statusesFrom(played) }
    val action by remember(show) { viewModel.seriesMainAction(show.all) }.collectAsState(initial = null)
    val mainAction = remember(action, show) { action?.let(show::shownAs) }
    val status = remember(show) { viewModel.seriesStatus(show.all).loaded() }.collectAsState(initial = null).value
    val favourites = viewModel.favouriteSeries.collectAsState().value
    // The whole show's Versions, as its main episode has them.
    val versionKey = (mainAction?.episode ?: show.shown.firstOrNull())?.let { MediaKey.Episode(it.id) }
    LaunchedEffect(versionKey) { versionKey?.let { versions = it to viewModel.pageVersions(it) } }

    // Only the first showing waits: a choice or a finished episode then updates the page in place.
    var ready by remember(series.id) { mutableStateOf(false) }
    if (!ready && status != null) {
        ready = showButtonsKnown(read, fetched, action != null, versions?.first, versionKey)
    }
    if (favourites == null || !ready) {
        LoadingDetail()
        return
    }

    SeriesDetailContent(
        series = series,
        episodes = show.shown,
        statuses = statuses,
        mainAction = mainAction,
        status = status?.value,
        favourite = viewModel.isFavourite(series, favourites),
        onPlayEpisode = onPlayEpisode,
        onSetFavourite = { viewModel.setSeriesFavourite(series, it) },
        onOpenPerson = onOpenPerson,
        versions = versions?.second ?: PageVersions(),
        onChooseVersion = { chosen ->
            scope.launch {
                viewModel.chooseVersion(chosen)
                versionKey?.let { versions = it to viewModel.pageVersions(it) }
            }
        },
    )
}

/**
 * Whether a show page's buttons are all known, so they're there when it shows rather than arriving one by
 * one: its episodes [read], its main episode [hasAction], and the Versions read for [versionKey] ([versionsFor]).
 * Stored episodes are enough, as the fetch on every open rarely changes them; only a show opened for the first
 * time waits for that fetch ([fetched]), and shows without a Play button if it finds none.
 */
private fun showButtonsKnown(
    read: ShowEpisodes?,
    fetched: Boolean,
    hasAction: Boolean,
    versionsFor: MediaKey?,
    versionKey: MediaKey?,
): Boolean = when {
    read == null -> false
    read.all.isEmpty() -> fetched
    else -> hasAction && versionsFor == versionKey
}
