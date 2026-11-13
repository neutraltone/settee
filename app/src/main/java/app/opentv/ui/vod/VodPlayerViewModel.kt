/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Episode
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodQualities
import app.opentv.data.repo.WatchStatus
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.NextEpisodeCountdown.Decision
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the movie and episode player is playing, and moving on to the Next Episode.
 *
 * The episode playing is held here rather than taken from the route each time, because once the
 * player has moved on the route still names the first one: a rebuilt screen must carry on with
 * the episode it was showing. The screen keeps the [Playback] (its PlayerController) and hands it
 * over in [attach], so moving on swaps the media item in the same player instead of navigating.
 */
class VodPlayerViewModel(
    app: Application,
    private val graph: ServiceLocator.Graph = ServiceLocator.get(app),
    clock: () -> Long = System::currentTimeMillis,
    private val savedState: SavedStateHandle? = null,
) : AndroidViewModel(app) {

    /** The signature the default view model factory looks for to hand over the saved state. */
    constructor(app: Application, savedState: SavedStateHandle) :
        this(app, ServiceLocator.get(app), savedState = savedState)

    /** The screen's player, as far as moving between episodes needs it. */
    interface Playback {
        fun progress(): NextEpisodeCountdown.Playback

        /** Plays [request], saying it's in place of the Quality called [skipped] if there is one. */
        fun play(request: PlayerController.Request, skipped: String? = null)

        /** The lower Quality playback moved to by itself, if it has. */
        fun steppedDownTo(): MediaKey? = null

        fun pause()

        fun stop()
    }

    /**
     * What the Next Episode card shows: [imageUrl] is the episode's thumbnail, or else the series'
     * artwork, or null for the placeholder.
     */
    data class NextEpisodeCard(val season: Int, val episode: Int, val title: String, val imageUrl: String?)

    /** What the player is playing: what the route named, until moving on replaces it. */
    private val _current = MutableStateFlow<MediaKey?>(null)
    val current: StateFlow<MediaKey?> = _current.asStateFlow()

    private val countdown =
        NextEpisodeCountdown(autoPlay = { graph.settings.playNextAutomatically.value }, clock = clock)

    private val _nextEpisode = MutableStateFlow<NextEpisodeCard?>(null)

    /** The Next Episode after what's playing; null for a movie, recording or a series' last episode. */
    val nextEpisode: StateFlow<NextEpisodeCard?> = _nextEpisode.asStateFlow()

    private val _overlay = MutableStateFlow<Overlay>(Overlay.None)

    /** The Next Episode card or "Are you still watching?", whichever is showing. */
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    private val _qualityChoices = MutableStateFlow<List<VodQualities.Choice>>(emptyList())

    /** The Quality pill's entries for what's playing: fewer than two, and there's no pill (#181). */
    val qualityChoices: StateFlow<List<VodQualities.Choice>> = _qualityChoices.asStateFlow()

    /** What [chooseVersion] keeps the choice with. */
    internal val versions: VodQualities get() = graph.vodQualities

    /** Where what's playing had got to, for starting another Version of it there. */
    internal val playingAt: Long? get() = playback?.progress()?.positionMillis ?: lastSeen?.positionMillis

    private val _canMarkWatched = MutableStateFlow(false)

    /** Whether the options list offers Mark watched: a movie or episode not already Watched. */
    val canMarkWatched: StateFlow<Boolean> = _canMarkWatched.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /** What was playing reached its end, with nothing to move on to: the player has nothing left to show. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var playback: Playback? = null
    private var route: MediaKey? = null
    private var next: Episode? = null
    private var fetchingNext: Job? = null
    private val lookup = NextEpisodeLookup(graph)

    /**
     * Where what's playing last was, while its length was known. Kept here because a stopped
     * player no longer knows, and a rebuilt screen must not wait on the database write of the
     * position the old one saved as it went.
     */
    private var lastSeen: NextEpisodeCountdown.Playback? = null

    /** What was marked Watched from the options list, which saving progress must leave alone. */
    private var marked: MediaKey? = null

    /**
     * Whether what's playing was started at 0 and hasn't saved a position yet. Until it does its
     * old resume point is still the saved one, so a rebuilt screen mustn't look there.
     */
    private var startedAtZero = false

    /**
     * Starts [playback] on what is playing, or on [route] when the screen was opened on something
     * new. While "Are you still watching?" is up nothing plays until it is answered. [fromStart]
     * says the route's first start is at 0 rather than its resume point.
     */
    suspend fun attach(route: MediaKey, playback: Playback, fromStart: Boolean = false) {
        this.playback = playback
        val current = _current.value
        when {
            current == null || route != this.route -> {
                this.route = route
                marked = null
                start(route, fromMillis = if (fromStart && savedState.spendFromStart()) 0 else null)
            }
            countdown.overlay is Overlay.StillWatching -> Unit
            else -> start(current, fromMillis = lastSeen?.positionMillis ?: 0L.takeIf { startedAtZero })
        }
    }

    /** Polled while the screen shows: moves on when the countdown says so. */
    suspend fun poll() {
        // Progress and the Next Episode now belong to the row playback moved to.
        playback?.steppedDownTo()?.takeIf { it != _current.value }?.let { start(it, alreadyPlaying = true) }
        val progress = playback?.progress() ?: return
        if (progress.durationMillis > 0) lastSeen = progress
        val decision = countdown.update(progress)
        act(decision)
        // Not while the card, the question or the search for a Next Episode could still move on from here.
        _finished.value = decision == null &&
            progress.ended &&
            _overlay.value is Overlay.None &&
            fetchingNext?.isActive != true
    }

    /** Records how far through what's playing is, for resuming it and for its Watched status. */
    suspend fun saveProgress() {
        val key = _current.value
        val progress = playback?.progress()
        // recordProgress clears a hand mark, and the player saves again as it closes.
        if (key == null || progress == null || key == marked) return
        if (progress.durationMillis > 0) lastSeen = progress
        if (graph.viewerLibrary.recordProgress(key.stored, progress.positionMillis, progress.durationMillis)) {
            _canMarkWatched.value = graph.canMarkWatched(key)
            startedAtZero = false
        }
    }

    /** OK on the Next Episode card. */
    suspend fun ok() = act(countdown.ok())

    /** Cancel ("Watch credits") or Back on the Next Episode card. */
    fun cancel() {
        countdown.cancel()
        _overlay.value = countdown.overlay
    }

    /**
     * Mark watched in the options list: moves on to the Next Episode, or returns true when there
     * isn't one known and the player should close.
     */
    suspend fun markWatched(): Boolean {
        val key = _current.value ?: return true
        marked = key
        // Must survive the screen closing: saving progress already leaves this title alone.
        withContext(NonCancellable) { graph.viewerLibrary.marks.markWatched(key.stored) }
        val following = next
        if (following != null) start(MediaKey.Episode(following.id)) else _canMarkWatched.value = false
        return following == null
    }

    /** Continue watching on "Are you still watching?". */
    suspend fun continueWatching() = act(countdown.continueWatching())

    /** A key press or touch while the player shows. */
    fun input() = countdown.input()

    private suspend fun act(decision: Decision?) {
        _overlay.value = countdown.overlay
        when (decision) {
            Decision.PlayNext -> next?.let { next ->
                _current.value?.let {
                    graph.viewerLibrary.nextEpisodes.movedOnFrom(it.stored, lastSeen?.durationMillis ?: 0)
                }
                start(MediaKey.Episode(next.id))
            }
            Decision.Pause -> playback?.pause()
            Decision.StopStream -> playback?.stop()
            null -> Unit
        }
    }

    /**
     * Plays the Version of [opened] its Plan picks, from [fromMillis] or else that Version's own resume
     * point, and works out its Next Episode. [alreadyPlaying] leaves the player be. Internal for
     * [chooseVersion], which starts another Version where this one was.
     */
    internal suspend fun start(opened: MediaKey, fromMillis: Long? = null, alreadyPlaying: Boolean = false) {
        suspend fun offer(episode: Episode?) {
            next = episode
            _nextEpisode.value = episode?.let { lookup.card(it) }
            countdown.newEpisode(hasNext = episode != null)
            _overlay.value = countdown.overlay
        }

        fetchingNext?.cancel()
        val planned = if (alreadyPlaying) null else graph.playRequestFor(opened, fromMillis)
        val key = planned?.first?.key ?: opened
        // Nothing suspends between here and play: a poll in between would see the last stream's step
        // down, take it for news and undo this. So what to play is read above, before anything moves.
        startedAtZero = fromMillis == 0L || (startedAtZero && key == _current.value)
        _current.value = key
        lastSeen = null
        planned?.let { (request, skipped) -> playback?.play(request, skipped) }
        _qualityChoices.value = graph.vodQualities.choicesFor(key)
        _canMarkWatched.value = graph.canMarkWatched(key)
        val episode = (key as? MediaKey.Episode)?.let { graph.catalogRepository.episode(it.episodeId) }
        offer(episode?.let { graph.viewerLibrary.nextEpisodes.after(it) })
        if (episode != null && next == null) {
            fetchingNext = viewModelScope.launch {
                lookup.fetchNext(episode)?.let { found -> if (_current.value == key) offer(found) }
            }
        }
    }
}

/**
 * The viewer chose [version] in the player's Quality pill: it's the Profile's Version of the title from
 * now on, and plays from where the one playing had got to (#181).
 */
suspend fun VodPlayerViewModel.chooseVersion(version: MediaKey) {
    versions.choose(version)
    start(version, fromMillis = playingAt)
}

/**
 * What to play for [opened], and what the Quality it's in place of is called, if it is. Each Quality starts
 * from [fromMillis] or else its own resume point.
 */
private suspend fun ServiceLocator.Graph.playRequestFor(
    opened: MediaKey,
    fromMillis: Long?,
): Pair<PlayerController.Request, String?> {
    val plan = vodQualities.planFor(opened)
    val request = PlayerController.Request(
        key = plan.start.key,
        startPositionMillis = fromMillis ?: viewerLibrary.resumeFrom(plan.start.key.stored),
        label = plan.start.label,
        lower = plan.lower.map {
            PlayerController.Quality(it.key, it.label, fromMillis ?: viewerLibrary.resumeFrom(it.key.stored))
        },
        stepsForSound = true,
    )
    return request to plan.skipped
}

/** Recordings and catch-up have no watch state to mark. */
private suspend fun ServiceLocator.Graph.canMarkWatched(key: MediaKey): Boolean {
    val hasWatchState = key is MediaKey.Movie || key is MediaKey.AddonStream || key is MediaKey.Episode
    return hasWatchState && viewerLibrary.marks.statusOf(key.stored) != WatchStatus.Watched
}

private const val FROM_START_SPENT = "fromStartSpent"

// The route keeps saying from the start for as long as it's on the back stack, including after
// Android restores the app. Only saved state outlives the process, to tell that from the first
// start, and restarting at 0 then would lose the progress made since.
private fun SavedStateHandle?.spendFromStart(): Boolean {
    val unspent = this?.get<Boolean>(FROM_START_SPENT) != true
    this?.set(FROM_START_SPENT, true)
    return unspent
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
