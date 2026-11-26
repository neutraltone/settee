/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.MediaKey
import app.opentv.player.torrent.toRequest
import app.opentv.player.torrent.uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The stream [this] is, as the player's key for [title]: a link with its headers, or a torrent as its magnet
 * link, which the player plays through the torrent engine (#670); null for a torrent whose info hash isn't
 * one. It names its add-on and its bingeGroup, so the Next Episode can carry on with the same add-on's
 * stream of the same kind ([AddonCarryOn]).
 */
internal fun StremioStream.keyFor(title: MediaKey.Title): MediaKey.AddonStream? = when (val link = link) {
    is StreamLink.Direct -> MediaKey.AddonStream(title, link.url, link.headers, addonName, bingeGroup)
    is StreamLink.Torrent ->
        link.toRequest()?.let { MediaKey.AddonStream(title, it.uri, addonName = addonName, bingeGroup = bingeGroup) }
}

/**
 * Carrying on after an add-on's stream of an episode to the next one, kept from an add-on (ADR 0011): its
 * add-ons are asked for it, and the stream from the same add-on with the same bingeGroup plays as soon as
 * it's offered, as Stremio does, a torrent as well as a link. The player shows the add-on picker over itself
 * meanwhile, fed by the same search ([streamsFor]), so the add-ons are asked once; with no such stream the
 * picker stays for the viewer.
 */
internal class AddonCarryOn(private val searches: AddonSearches, private val scope: CoroutineScope) {
    private class Carrying(
        val title: MediaKey.Title,
        val from: MediaKey.AddonStream,
        val search: Flow<AddonSearch>,
        val scope: CoroutineScope,
    )

    private var carrying: Carrying? = null

    /** What the add-ons offer for [title]: the search carrying on asked, when it's for [title]. */
    fun streamsFor(title: MediaKey.Title): Flow<AddonSearch> =
        carrying?.takeIf { it.title == title }?.search ?: searches.forTitle(title)

    /**
     * Readies carrying on from [after] to [title], before the player opens on it, so the picker it may
     * show shares this search. Nothing is asked until the picker or [carryOn] wants it, so a title that
     * plays a Provider's copy instead asks nothing. Nothing for a stream with no bingeGroup to match.
     */
    fun ready(after: MediaKey?, title: MediaKey.Title) {
        stop()
        val from = after as? MediaKey.AddonStream ?: return
        if (from.bingeGroup == null || from.addonName == null) return
        val own = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
        // Lazily: shared by the picker and the match, and started by whichever asks first.
        val search = searches.forTitle(title).shareIn(own, SharingStarted.Lazily, replay = 1)
        carrying = Carrying(title, from, search, own)
    }

    /** Plays [play] the matching stream of the title [ready] readied, once one is offered. */
    fun carryOn(title: MediaKey.Title, play: suspend (StremioStream) -> Unit) {
        val now = carrying?.takeIf { it.title == title } ?: return
        now.scope.launch { now.firstMatch()?.let { play(it) } }
    }

    /** Stops carrying on: a stream was picked, or the player moved on to something else. */
    fun stop() {
        carrying?.scope?.cancel()
        carrying = null
    }

    // Until every add-on has answered: a slow one may be the one that sent the last episode.
    private suspend fun Carrying.firstMatch(): StremioStream? = search.transformWhile { found ->
        val results = found as? AddonSearch.Results
        val match = results?.streams?.firstOrNull { it.stream.continues(from) }?.stream
        if (match != null) emit(match)
        match == null && results != null && !results.finished
    }.firstOrNull()

    private fun StremioStream.continues(from: MediaKey.AddonStream) =
        addonName == from.addonName && bingeGroup == from.bingeGroup
}
