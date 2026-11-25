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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.StreamLink
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.keptFromAddons
import app.opentv.data.repo.playsThroughAddons
import app.opentv.player.PlayerController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * The add-on stream picker over the player, while it waits on a title kept from an add-on
 * ([VodPlayerViewModel.addonPick]): opened from Continue Watching, the Next Episode card or anything else
 * that goes straight to the player, such a title has no stream of its own (ADR 0011). The stream picked
 * plays from the title's resume point. Back leaves the player, as nothing else in it can play.
 */
@Composable
internal fun PlayerAddonPick(viewModel: VodPlayerViewModel, onBack: () -> Unit) {
    val pick by viewModel.addonPick.collectAsState()
    val title = pick
    val picker = rememberAddonPickerState(title)
    val find: () -> Flow<AddonSearch> = remember(title) { { title?.let { viewModel.addonStreams(it) } ?: emptyFlow() } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(title) { if (title != null) picker.ask(find) }
    AddonStreamPicker(picker, find, onDismiss = onBack) { ranked ->
        // Only links are offered so far: torrents wait for the engine that will play them.
        (ranked.stream.link as? StreamLink.Direct)?.let { scope.launch { viewModel.playAddonStream(it) } }
    }
}

/** The stream the viewer picked for the title waiting in [VodPlayerViewModel.addonPick], from its resume point. */
suspend fun VodPlayerViewModel.playAddonStream(link: StreamLink.Direct) {
    val title = addonPick.value ?: return
    start(MediaKey.AddonStream(title, link.url, link.headers), fromMillis = pickFrom)
}

/** What the add-ons offer for [title], for the player's picker while [VodPlayerViewModel.addonPick] names it. */
fun VodPlayerViewModel.addonStreams(title: MediaKey.Title): Flow<AddonSearch> = addonSearches.forTitle(title)

/**
 * The title [planned] would play, when it was kept from an add-on: it has no stream of its own, so the
 * player waits on the viewer picking one of its add-on streams instead. Null for anything else, and when
 * nothing is to be played anew.
 */
internal suspend fun ServiceLocator.Graph.addonPickFor(
    planned: Pair<PlayerController.Request, String?>?,
): MediaKey.Title? = (planned?.first?.key as? MediaKey.Title)?.takeIf { playsThroughAddons(it) }

/**
 * Plays [planned], or, while the player [waits] on an add-on stream being picked, stops whatever was
 * playing: the last episode's stream, if there was one, has nothing more to give behind the picker.
 */
internal fun VodPlayerViewModel.Playback.playOrWait(
    planned: Pair<PlayerController.Request, String?>?,
    waits: Boolean,
) {
    if (waits) stop() else planned?.let { (request, skipped) -> play(request, skipped) }
}

/** Whether [title] was kept from an add-on, so has no stream of its own and plays only through the picker. */
private suspend fun ServiceLocator.Graph.playsThroughAddons(title: MediaKey.Title): Boolean = when (title) {
    is MediaKey.Movie -> catalogRepository.movie(title.movieId)?.let { database.sources().keptFromAddons(it) } == true
    is MediaKey.Episode -> catalogRepository.episode(title.episodeId)?.playsThroughAddons == true
}
