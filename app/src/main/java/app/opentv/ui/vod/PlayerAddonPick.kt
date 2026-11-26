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
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.MediaKey
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
        scope.launch { viewModel.playAddonStream(ranked.stream) }
    }
}

/**
 * The stream picked for the title waiting in [VodPlayerViewModel.addonPick], by the viewer or by carrying
 * on, from its resume point. Nothing once the title's no longer waiting, and nothing for a torrent yet.
 */
suspend fun VodPlayerViewModel.playAddonStream(stream: StremioStream) {
    val key = addonPick.value?.let { stream.keyFor(it) } ?: return
    start(key, fromMillis = pickFrom)
}

/** What the add-ons offer for [title], for the player's picker while [VodPlayerViewModel.addonPick] names it. */
fun VodPlayerViewModel.addonStreams(title: MediaKey.Title): Flow<AddonSearch> = carryOn.streamsFor(title)

/**
 * Plays [planned], or, while the player [waits] on an add-on stream being picked, stops whatever was
 * playing: the last episode's stream, if there was one, has nothing more to give behind the picker.
 */
internal fun VodPlayerViewModel.Playback.playOrWait(planned: PlannedPlay?, waits: Boolean) {
    if (waits) stop() else planned?.let { play(it.request, it.skipped) }
}

/**
 * Where a stream picked for the title is to start: [fromMillis], else where the title as a whole had got
 * to, whichever of its Versions got it there.
 */
internal fun PlannedPlay?.pickStart(fromMillis: Long?): Long? = fromMillis ?: this?.request?.startPositionMillis
