/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Episode
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.NextEpisodeCountdown.Decision
import app.opentv.player.NextEpisodeCountdown.Overlay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the movie and episode player is playing, and moving on to the Next Episode.
 *
 * The episode playing is held here rather than taken from the route each time, because once the
 * player has moved on the route still names the first one: a rebuilt screen must carry on with
 * the episode it was showing. The screen keeps the [Playback] (its PlayerController) and hands it
 * over in [attach], so moving on swaps the media item in the same player instead of navigating.
 */
class VodPlayerViewModel @JvmOverloads constructor(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
    clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {

    /** The screen's player, as far as moving between episodes needs it. */
    interface Playback {
        fun progress(): NextEpisodeCountdown.Playback

        fun play(item: Item, startPositionMillis: Long)

        fun pause()

        fun stop()
    }

    /** One stream the player plays: what the route carries, and what moving on replaces. */
    data class Item(val mediaKey: String, val streamUrl: String, val title: String, val userAgent: String)

    /**
     * What the Next Episode card shows: [imageUrl] is the episode's thumbnail, or else the series'
     * artwork, or null for the placeholder.
     */
    data class NextEpisodeCard(val season: Int, val episode: Int, val title: String, val imageUrl: String?)

    private val _current = MutableStateFlow<Item?>(null)
    val current: StateFlow<Item?> = _current.asStateFlow()

    private val countdown =
        NextEpisodeCountdown(autoPlay = { graph.settings.playNextAutomatically.value }, clock = clock)

    private val _nextEpisode = MutableStateFlow<NextEpisodeCard?>(null)

    /** The Next Episode after what's playing; null for a movie, recording or a series' last episode. */
    val nextEpisode: StateFlow<NextEpisodeCard?> = _nextEpisode.asStateFlow()

    private val _overlay = MutableStateFlow<Overlay>(Overlay.None)

    /** The Next Episode card or "Are you still watching?", whichever is showing. */
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    private var playback: Playback? = null
    private var route: Item? = null
    private var next: Episode? = null
    private var fetchingNext: Job? = null
    private val lookup = NextEpisodeLookup(graph)

    /**
     * Where what's playing last was, while its length was known. Kept here because a stopped
     * player no longer knows, and a rebuilt screen must not wait on the database write of the
     * position the old one saved as it went.
     */
    private var lastSeen: NextEpisodeCountdown.Playback? = null

    /**
     * Starts [playback] on what is playing, or on [route] when the screen was opened on something
     * new. While "Are you still watching?" is up nothing plays until it is answered.
     */
    suspend fun attach(route: Item, playback: Playback) {
        this.playback = playback
        val current = _current.value
        when {
            current == null || route != this.route -> {
                this.route = route
                start(route)
            }
            countdown.overlay is Overlay.StillWatching -> Unit
            else -> start(current, fromMillis = lastSeen?.positionMillis)
        }
    }

    /** Polled while the screen shows: moves on when the countdown says so. */
    suspend fun poll() {
        val progress = playback?.progress() ?: return
        if (progress.durationMillis > 0) lastSeen = progress
        act(countdown.update(progress))
    }

    /** Records how far through what's playing is, for resuming it and for its Watched status. */
    suspend fun saveProgress() {
        val item = _current.value ?: return
        val progress = playback?.progress() ?: return
        if (progress.durationMillis > 0) lastSeen = progress
        // The first seconds say nothing about wanting to come back to it.
        if (progress.durationMillis > 0 && progress.positionMillis > MIN_SAVED_MILLIS) {
            graph.viewerLibrary.recordProgress(item.mediaKey, progress.positionMillis, progress.durationMillis)
        }
    }

    /** OK on the Next Episode card. */
    suspend fun ok() = act(countdown.ok())

    /** Cancel ("Watch credits") or Back on the Next Episode card. */
    fun cancel() {
        countdown.cancel()
        _overlay.value = countdown.overlay
    }

    /** Continue watching on "Are you still watching?". */
    suspend fun continueWatching() = act(countdown.continueWatching())

    /** A key press or touch while the player shows. */
    fun input() = countdown.input()

    private suspend fun act(decision: Decision?) {
        _overlay.value = countdown.overlay
        when (decision) {
            Decision.PlayNext -> next?.let { next ->
                val finishing = _current.value
                val duration = lastSeen?.durationMillis ?: 0
                // Moving on from the card counts as finishing: on a short episode the last 15
                // seconds can fall short of 93% through, so it is recorded at its whole length.
                if (finishing != null && duration > 0) {
                    graph.viewerLibrary.recordProgress(finishing.mediaKey, duration, durationMillis = duration)
                }
                start(
                    Item("$EPISODE_PREFIX${next.id}", next.streamUrl, next.playerTitle, finishing?.userAgent.orEmpty()),
                )
            }
            Decision.Pause -> playback?.pause()
            Decision.StopStream -> playback?.stop()
            null -> Unit
        }
    }

    /** Plays [item] from [fromMillis], or from its resume point, and works out its Next Episode. */
    private suspend fun start(item: Item, fromMillis: Long? = null) {
        fetchingNext?.cancel()
        _current.value = item
        lastSeen = null
        playback?.play(item, fromMillis ?: graph.viewerLibrary.resumeFrom(item.mediaKey))
        val episode = item.mediaKey.takeIf { it.startsWith(EPISODE_PREFIX) }
            ?.removePrefix(EPISODE_PREFIX)?.toLongOrNull()
            ?.let { graph.catalogRepository.episode(it) }
        offer(episode?.let { graph.viewerLibrary.nextEpisodes.after(it) })
        if (episode != null && next == null) {
            fetchingNext = viewModelScope.launch {
                lookup.fetchNext(episode)?.let { found -> if (_current.value == item) offer(found) }
            }
        }
    }

    private suspend fun offer(episode: Episode?) {
        next = episode
        _nextEpisode.value = episode?.let { lookup.card(it) }
        countdown.newEpisode(hasNext = episode != null)
        _overlay.value = countdown.overlay
    }

    private companion object {
        const val EPISODE_PREFIX = "ep:"
        const val MIN_SAVED_MILLIS = 5_000L
    }
}

/** Finds a Next Episode the catalogue doesn't hold yet, and what its card shows. */
private class NextEpisodeLookup(private val graph: ServiceLocator.Graph) {
    // A series' episodes are fetched only when its page is opened, and Continue Watching can start
    // one without that, or the provider may have added one since. The fetch runs beside playback,
    // not before polling, so a slow provider can't hold up the card's countdown or saving progress.
    suspend fun fetchNext(episode: Episode): Episode? {
        val source = graph.sourceRepository.byId(episode.sourceId) ?: return null
        graph.catalogRepository.ensureEpisodes(source, episode.seriesId)
        return graph.viewerLibrary.nextEpisodes.after(episode)
    }

    suspend fun card(episode: Episode): VodPlayerViewModel.NextEpisodeCard {
        val series = graph.catalogRepository.seriesOf(episode)
        // The card is landscape, like an episode still: the wide backdrop fits it best.
        val image = episode.stillUrl ?: series?.backdropUrl ?: series?.posterUrl
        return VodPlayerViewModel.NextEpisodeCard(episode.season, episode.episodeNumber, episode.title, image)
    }
}
