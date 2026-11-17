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
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodQualities
import app.opentv.data.repo.WatchStatus
import app.opentv.player.CuePlacement
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.NextEpisodeCountdown.Decision
import app.opentv.player.NextEpisodeCountdown.Overlay
import app.opentv.player.PlayerController
import app.opentv.player.SkipButton
import app.opentv.player.SkipCue
import app.opentv.player.UpNextCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /**
         * Where what was last asked for has got to, for starting another Version there, or null when that
         * isn't known. Unlike [progress] it doesn't need the stream's length, and while a stream is still
         * being found it's where that stream was asked to start (#379).
         */
        fun position(): Long?

        /** Plays [request], saying it's in place of the Quality called [skipped] if there is one. */
        fun play(request: PlayerController.Request, skipped: String? = null)

        /** The lower Quality playback moved to by itself, if it has. */
        fun steppedDownTo(): MediaKey? = null

        fun pause()

        /** Jumps to [positionMillis] in what's playing. */
        fun seekTo(positionMillis: Long)

        fun stop()
    }

    /**
     * What the Next Episode card shows: [imageUrl] is the episode's thumbnail, or else the series'
     * artwork, or null for the placeholder.
     */
    data class NextEpisodeCard(val season: Int, val episode: Int, val title: String, val imageUrl: String?)

    /**
     * Whether [of] has an episode before it and one after it, for the player's Previous and Next
     * episode buttons. [of] is what they belong to, so the screen can tell a new episode's from ones
     * found late for the same episode, which it holds back while the controls show.
     */
    data class EpisodeSteps(val previous: Boolean, val next: Boolean, val of: MediaKey? = null)

    /** What the player is playing: what the route named, until moving on replaces it. */
    private val _current = MutableStateFlow<MediaKey?>(null)
    val current: StateFlow<MediaKey?> = _current.asStateFlow()

    private val countdown =
        NextEpisodeCountdown(autoPlay = { graph.settings.playNextAutomatically.value }, clock = clock)

    private val _nextEpisode = MutableStateFlow<NextEpisodeCard?>(null)

    /** The Next Episode after what's playing; null for a movie, recording or a series' last episode. */
    val nextEpisode: StateFlow<NextEpisodeCard?> = _nextEpisode.asStateFlow()

    private val _episodeSteps = MutableStateFlow(EpisodeSteps(previous = false, next = false))

    /** Whether what's playing has an episode either side: neither, for a movie, recording or catch-up. */
    val episodeSteps: StateFlow<EpisodeSteps> = _episodeSteps.asStateFlow()

    private val _overlay = MutableStateFlow<Overlay>(Overlay.None)

    /** The Next Episode card or "Are you still watching?", whichever is showing. */
    val overlay: StateFlow<Overlay> = _overlay.asStateFlow()

    private val _qualityChoices = MutableStateFlow<List<VodQualities.Choice>>(emptyList())

    /** The Quality pill's entries for what's playing: fewer than two, and there's no pill (#181). */
    val qualityChoices: StateFlow<List<VodQualities.Choice>> = _qualityChoices.asStateFlow()

    /** What [chooseVersion] keeps the choice with. */
    internal val versions: VodQualities get() = graph.vodQualities

    /** Where what's playing had got to, for starting another Version of it there, or null when that isn't known. */
    internal val playingAt: Long? get() = playback?.position() ?: lastKnown

    /** Where what's playing had got to without asking the player, which a rebuilt screen's doesn't know. */
    private val lastKnown: Long?
        get() = lastSeen?.positionMillis ?: 0L.takeIf { startedAtZero } ?: requestedFrom

    private val _canMarkWatched = MutableStateFlow(false)

    /** Whether the options list offers Mark watched: a movie or episode not already Watched. */
    val canMarkWatched: StateFlow<Boolean> = _canMarkWatched.asStateFlow()

    private val _finished = MutableStateFlow(false)

    /** What was playing reached its end, with nothing to move on to: the player has nothing left to show. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var playback: Playback? = null
    private var route: MediaKey? = null
    private var next: Episode? = null
    private var previous: Episode? = null

    /** The episodes either side of what's playing, as far as they're known, for moving to one. */
    internal val episodesAround: Pair<Episode?, Episode?> get() = previous to next

    /** Held while moving to another episode, so a second press waits for the move before it. */
    internal val moving = Mutex()
    private var fetchingNext: Job? = null
    private val timings = PlayingTimings(graph)

    /** Skip intro and Skip recap, for [controlsShowing] and [skipStretch]. */
    internal val skips = Skips(
        stretches = timings::stretches,
        progress = { playback?.progress() },
        seekTo = { playback?.seekTo(it) },
        overlaid = { _overlay.value !is Overlay.None },
    )

    /** The intro or recap whose Skip button shows, if one does (#518). */
    val skipButton: StateFlow<SkipCue.Stretch?> = skips.button
    private val lookup = NextEpisodeLookup(graph)

    /**
     * Where what's playing last was, while its length was known. Kept here because a stopped
     * player no longer knows, and a rebuilt screen must not wait on the database write of the
     * position the old one saved as it went.
     */
    private var lastSeen: NextEpisodeCountdown.Playback? = null

    /**
     * Where what's playing was asked to start in place of its resume point, until [lastSeen] knows
     * better. A Version chosen while the last was still loading has saved no resume point of its own
     * yet, so a rebuilt screen must start it here (#379). A step down keeps it: the lower Quality
     * started from the same place.
     */
    private var requestedFrom: Long? = null

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
            else -> start(current, fromMillis = lastKnown)
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
        skips.show(progress)
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
            countdown.cue = timings.cue
            timings.lookAhead(viewModelScope, episode)
            _overlay.value = countdown.overlay
            _episodeSteps.value = EpisodeSteps(previous = previous != null, next = episode != null, of = _current.value)
        }

        fetchingNext?.cancel()
        val planned = if (alreadyPlaying) null else graph.playRequestFor(opened, fromMillis)
        val key = planned?.first?.key ?: opened
        // Nothing suspends between here and play: a poll in between would see the last stream's step
        // down, take it for news and undo this. So what to play is read above, before anything moves.
        startedAtZero = fromMillis == 0L || (startedAtZero && key == _current.value)
        _current.value = key
        lastSeen = null
        skips.clear()
        planned?.let { (request, skipped) ->
            requestedFrom = fromMillis
            playback?.play(request, skipped)
        }
        _qualityChoices.value = graph.vodQualities.choicesFor(key)
        _canMarkWatched.value = graph.canMarkWatched(key)
        val episode = (key as? MediaKey.Episode)?.let { graph.catalogRepository.episode(it.episodeId) }
        val (before, after) = graph.viewerLibrary.nextEpisodes.around(episode)
        previous = before
        timings.lookUp(viewModelScope, episode, stillPlaying = { _current.value == key }) { countdown.cue = it }
        offer(after)
        if (episode != null && next == null) {
            fetchingNext = viewModelScope.launch {
                // The fetch loads the whole series, so the one before can turn up too, with no next.
                val (foundBefore, found) = lookup.fetchAround(episode)
                if (_current.value == key) {
                    // A fetch that couldn't reach the provider finds nothing, which mustn't undo what
                    // the catalogue already knew.
                    previous = foundBefore ?: previous
                    if (found != null) {
                        offer(found)
                    } else {
                        _episodeSteps.value = _episodeSteps.value.copy(previous = previous != null)
                    }
                }
            }
        }
    }
}

/** Whether the player's controls are up, which bring back a Skip button that has gone. */
fun VodPlayerViewModel.controlsShowing(showing: Boolean) = skips.controlsShowing(showing)

/** Skip intro or Skip recap: jumps to the end of what the button skips. */
fun VodPlayerViewModel.skipStretch() = skips.skip()

/** Back on Skip intro or Skip recap: puts it away for the rest of the stretch. */
fun VodPlayerViewModel.dismissSkip() = skips.dismiss()

/**
 * The Skip button's state (#518), on the [stretches] the playing episode's timings offer: what shows
 * at each [progress], jumping with [seekTo], and nothing while the Next Episode card or "Are you still
 * watching?" is [overlaid], since each wants OK for itself.
 */
internal class Skips(
    private val stretches: (streamMillis: Long) -> List<SkipCue.Stretch>,
    private val progress: () -> NextEpisodeCountdown.Playback?,
    private val seekTo: (Long) -> Unit,
    private val overlaid: () -> Boolean,
) {
    private val shown = MutableStateFlow<SkipCue.Stretch?>(null)
    val button: StateFlow<SkipCue.Stretch?> = shown.asStateFlow()

    private val skip = SkipButton()
    private var controlsShowing = false

    fun show(at: NextEpisodeCountdown.Playback) {
        val now = stretches(at.durationMillis)
        if (now != skip.stretches) skip.stretches = now
        shown.value = skip.update(at.positionMillis, controlsShowing).takeIf { !overlaid() }
    }

    fun controlsShowing(showing: Boolean) {
        controlsShowing = showing
        progress()?.let(::show)
    }

    fun skip() {
        val stretch = shown.value ?: return
        seekTo(stretch.toMillis)
        shown.value = null
    }

    fun dismiss() {
        skip.dismiss()
        shown.value = null
    }

    fun clear() {
        shown.value = null
    }
}

/** Next episode on the player's controls, or the remote's next key. */
suspend fun VodPlayerViewModel.toNextEpisode() = moveTo { it.second }

/** Previous episode on the player's controls, or the remote's previous key. */
suspend fun VodPlayerViewModel.toPreviousEpisode() = moveTo { it.first }

// Unlike moving on at the card, this doesn't count as finishing the episode: it keeps the progress
// it had, saved first so the seconds since the last save aren't lost. A press while a move is under
// way waits for it, then reads the episodes either side of where that move landed: read before, it
// would start the same episode again, and dropped, a quick second press would go unheard.
private suspend fun VodPlayerViewModel.moveTo(which: (Pair<Episode?, Episode?>) -> Episode?) = moving.withLock {
    which(episodesAround)?.let { episode ->
        saveProgress()
        start(MediaKey.Episode(episode.id))
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
    suspend fun fetchAround(episode: Episode): Pair<Episode?, Episode?> {
        val source = graph.sourceRepository.byId(episode.sourceId) ?: return null to null
        graph.catalogRepository.ensureEpisodes(source, episode.seriesId)
        return graph.viewerLibrary.nextEpisodes.around(episode)
    }

    suspend fun card(episode: Episode): VodPlayerViewModel.NextEpisodeCard {
        val series = graph.catalogRepository.seriesOf(episode)
        // The card is landscape, like an episode still: the wide backdrop fits it best.
        val image = episode.stillUrl ?: series?.backdropUrl ?: series?.posterUrl
        return VodPlayerViewModel.NextEpisodeCard(episode.season, episode.episodeNumber, episode.title, image)
    }
}

/**
 * Where the Next Episode card comes up in what's playing (#517), and what Skip intro and Skip recap
 * offer (#518): 30 seconds before the end and nothing to skip until the episode's timings arrive.
 * Held here as well as in the countdown, which forgets it on every newEpisode, because a Next Episode
 * found late is offered again for the same episode.
 */
private class PlayingTimings(private val graph: ServiceLocator.Graph) {
    var cue: CuePlacement = UpNextCue::fallback
        private set

    /** What's playing's found timings, for its Skip intro and Skip recap; null until they arrive. */
    @Volatile private var episodeTimings: EpisodeTimings? = null
    private var job: Job? = null

    /** The intros and recaps to offer in a stream [streamMillis] long. */
    fun stretches(streamMillis: Long): List<SkipCue.Stretch> = SkipCue.place(episodeTimings, streamMillis)

    private val wanted: Boolean get() = graph.settings.skipIntrosAndCredits.value

    /**
     * Starts again at the fallback for [episode], then looks up its timings beside playback, not before
     * it, so a slow or missing network only leaves the card at 30 seconds before the end. [placed] gets
     * the cue they give, while the episode is [stillPlaying].
     */
    fun lookUp(scope: CoroutineScope, episode: Episode?, stillPlaying: () -> Boolean, placed: (CuePlacement) -> Unit) {
        job?.cancel()
        cue = UpNextCue::fallback
        episodeTimings = null
        if (episode == null || !wanted) return
        job = scope.launch {
            // Timings only ever improve on the fallback: nothing about them is worth an error on screen.
            val found = runCatching { graph.episodeTimings.timingsFor(episode) }.getOrNull()
            if (found == null || !stillPlaying()) return@launch
            cue = { streamMillis -> UpNextCue.place(found.timings, found.restOfShow, streamMillis) }
            episodeTimings = found.timings
            placed(cue)
        }
    }

    /** Looks up the Next Episode's timings, whenever it's found, so they're there when it starts. */
    fun lookAhead(scope: CoroutineScope, next: Episode?) {
        if (next == null || !wanted) return
        scope.launch { runCatching { graph.episodeTimings.timingsFor(next) } }
    }
}
