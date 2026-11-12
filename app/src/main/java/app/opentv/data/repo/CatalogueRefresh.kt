/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every Catalogue Refresh goes through here, whatever asked for it. Callers say which [Trigger] they
 * are; what to fetch, in what order and how fresh is fresh enough is decided here, so the triggers
 * can't drift apart. [CatalogRepository] still does each provider's fetching and merging.
 */
class CatalogueRefresh(
    private val catalog: CatalogRepository,
    private val guide: EpgRepository,
    private val settings: AppSettings,
    private val rescanSeriesRules: suspend () -> Unit,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    sealed interface Trigger {
        /** Everything, for every enabled provider. */
        sealed interface Catalogue : Trigger

        data object Periodic : Catalogue

        /** Refresh now in Settings and Home's refresh: the viewer asked, so the guide is forced. */
        data object Manual : Catalogue

        /** On the TV or from a phone: channels now, to show the login works, the guide afterwards. */
        data class ProviderAdded(val source: Source) : Trigger

        /**
         * The first provider, saved while none was: [ProviderAdded], with movies and shows fetched
         * straight after the guide and each step's progress in [State.setup].
         */
        data class Setup(val source: Source) : Trigger

        data object OpenedMoviesOrShows : Trigger

        sealed interface GuideOnly : Trigger

        data object AppLaunch : GuideOnly

        data object GuideSettings : GuideOnly
    }

    sealed interface Outcome {
        data class Refreshed(val channels: Int, val problems: Int, val guide: EpgRepository.SyncSummary) : Outcome

        sealed interface Added : Outcome

        data class ChannelsLoaded(val channels: Int) : Added

        data class AddFailed(val reason: String) : Added

        /** The background half of adding a provider. */
        data class GuideLoaded(val guide: EpgRepository.SyncSummary) : Outcome

        data class GuideRefreshed(val guide: EpgRepository.SyncSummary) : Outcome

        /** How many providers were downloaded; the others kept what's stored. */
        data class VodChecked(val fetched: Int) : Outcome
    }

    /**
     * What screens show. [refreshing] covers the periodic and manual refreshes, which run as
     * background work the screen can't otherwise see; [last] is the latest of those or of adding a
     * provider, not the guide-only and Movies-and-Shows checks, which their callers answer themselves.
     */
    data class State(
        val refreshing: Boolean = false,
        val loadingVod: Boolean = false,
        val buildingGuide: Boolean = false,
        val last: Outcome? = null,
        /** The running or latest [Trigger.Setup]; null until one starts. */
        val setup: Setup? = null,
    )

    /** Where a [Trigger.Setup] has got to. [steps] are the ones that apply, in the order they run. */
    data class Setup(
        val steps: List<SetupStep>,
        val running: SetupStep? = steps.first(),
        val finished: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val refreshes = AtomicInteger()
    private val vodChecks = AtomicInteger()
    private val guideBuilds = AtomicInteger()

    private val providerTurns = ConcurrentHashMap<Long, Mutex>()
    private val liveDownloads = OneAtATime<CatalogRepository.SyncResult>()
    private val vodDownloads = OneAtATime<Job?>()

    /** Serialises the guide: feeds are shared by every provider, so overlapping passes download twice. */
    private val guideLock = Mutex()

    /** When the guide pass in progress started; 0 when there isn't one. */
    private val guidePassStarted = AtomicLong()

    suspend fun refresh(trigger: Trigger.Catalogue): Outcome.Refreshed {
        var outcome: Outcome.Refreshed? = null
        // Finishing and the result are one state, so a result equal to the last still reaches screens.
        return marking(refreshes, { copy(refreshing = it, last = outcome ?: last) }) {
            var channels = 0
            var problems = 0
            for (source in catalog.enabledSources()) {
                // Already-fetched rows are left untouched by a switch that's off: turning the type
                // back on and refreshing restores it.
                val live = if (settings.liveEnabled.value) {
                    live(
                        source,
                    )
                } else {
                    CatalogRepository.SyncResult.Success(0, 0, 0)
                }
                when (live) {
                    is CatalogRepository.SyncResult.Failed -> problems++
                    is CatalogRepository.SyncResult.Success -> {
                        channels += live.channelCount
                        // Nobody is watching this one, and waiting lets providers take turns.
                        vod(source, waitForNewEpisodes = true)
                    }
                }
            }
            val summary = syncGuide(force = trigger == Trigger.Manual)
            rescan()
            Outcome.Refreshed(channels, problems + summary.feedsFailed, summary).also { outcome = it }
        }
    }

    // In the module's scope: whoever asked leaving must not cost the provider its guide and rescan.
    suspend fun refresh(trigger: Trigger.ProviderAdded): Outcome.Added = scope.async {
        // Before anything else, so a login that fails doesn't leave another account's record behind.
        settings.setVodFetchedAtMillis(trigger.source.id, 0L)
        val outcome = when (val result = live(trigger.source)) {
            is CatalogRepository.SyncResult.Failed -> Outcome.AddFailed(result.reason)
            is CatalogRepository.SyncResult.Success -> Outcome.ChannelsLoaded(result.channelCount)
        }
        _state.update { it.copy(last = outcome) }
        if (outcome is Outcome.ChannelsLoaded) {
            scope.launch {
                marking(guideBuilds, { copy(buildingGuide = it) }) {
                    runCatching { syncGuide(force = false) }
                        .onSuccess { summary -> _state.update { it.copy(last = Outcome.GuideLoaded(summary)) } }
                        .onFailure { Log.w(TAG, "Guide failed to load for a new provider", it) }
                }
                rescan()
            }
        }
        outcome
    }.await()

    suspend fun refresh(trigger: Trigger.Setup): Outcome.Added = scope.async {
        val source = trigger.source
        settings.setVodFetchedAtMillis(source.id, 0L)
        // The channels always run: they're what proves the login works.
        val steps = buildList {
            add(SetupStep.CHANNELS)
            // The guide is only for live channels.
            if (settings.liveEnabled.value) add(SetupStep.GUIDE)
            // Only Xtream has movies and shows.
            if (source.kind == SourceKind.XTREAM) {
                if (settings.moviesEnabled.value) add(SetupStep.MOVIES)
                if (settings.seriesEnabled.value) add(SetupStep.SHOWS)
            }
        }
        _state.update { it.copy(setup = Setup(steps)) }
        val outcome = when (val result = live(source)) {
            is CatalogRepository.SyncResult.Failed -> Outcome.AddFailed(result.reason)
            is CatalogRepository.SyncResult.Success -> Outcome.ChannelsLoaded(result.channelCount)
        }
        _state.update { it.copy(last = outcome) }
        if (outcome is Outcome.ChannelsLoaded) {
            scope.launch {
                // One step after another, never two at once: two big downloads compete for a
                // Stick's 1 GB and its network.
                if (SetupStep.GUIDE in steps) {
                    setup { copy(running = SetupStep.GUIDE) }
                    runCatching { syncGuide(force = false) }
                }
                vod(source, waitForNewEpisodes = false, setupSteps = steps)
                setup { copy(running = null, finished = true) }
                rescan()
            }
        }
        outcome
    }.await()

    private fun setup(change: Setup.() -> Setup) = _state.update { it.copy(setup = it.setup?.change()) }

    @Suppress("UnusedParameter") // The trigger is how a caller says what it is, as with the others.
    suspend fun refresh(trigger: Trigger.OpenedMoviesOrShows): Outcome.VodChecked {
        val wanted = settings.moviesEnabled.value || settings.seriesEnabled.value
        // Whether or not the list is due a fetch: tied to the fetch alone, it ran at most twice a day.
        if (settings.moviesEnabled.value) catalog.fillEveryProvidersGenres()
        // Only Xtream has movies and shows; the others would flash "Loading" over nothing.
        val stale = catalog.enabledSources().filter { wanted && it.kind == SourceKind.XTREAM && vodIsStale(it) }
        if (stale.isEmpty()) return Outcome.VodChecked(0)
        return marking(vodChecks, { copy(loadingVod = it) }) {
            // Checked again each time: a big provider's list takes minutes, and another refresh
            // may have fetched the next one by the time its turn comes.
            val fetching = stale.filter { source ->
                vodIsStale(source).also {
                    // The viewer is looking at a spinner, so New Episode detection doesn't hold it up.
                    if (it) vod(source, waitForNewEpisodes = false)
                }
            }
            Outcome.VodChecked(fetching.size)
        }
    }

    suspend fun refresh(trigger: Trigger.GuideOnly): Outcome.GuideRefreshed {
        if (trigger == Trigger.AppLaunch && settings.moviesEnabled.value) catalog.fillEveryProvidersGenres()
        return Outcome.GuideRefreshed(syncGuide(force = trigger == Trigger.GuideSettings))
    }

    /**
     * Marks something as running in [state] while [block] runs. The count is changed outside
     * `update`, whose lambda runs again whenever it loses a race, and read inside it, so the
     * last to write always saw the latest count.
     */
    private suspend fun <T> marking(count: AtomicInteger, mark: State.(Boolean) -> State, block: suspend () -> T): T {
        count.incrementAndGet()
        _state.update { it.mark(count.get() > 0) }
        try {
            return block()
        } finally {
            count.decrementAndGet()
            _state.update { it.mark(count.get() > 0) }
        }
    }

    private fun vodIsStale(source: Source) = clock() - settings.vodFetchedAtMillis(source.id) >= VOD_FRESH_MILLIS

    private suspend fun live(source: Source): CatalogRepository.SyncResult =
        liveDownloads.share(source) { catalog.fetchLive(source, clock()) }

    /** With [setupSteps], movies and shows are fetched as Setup's steps, those in the list only. */
    private suspend fun vod(source: Source, waitForNewEpisodes: Boolean, setupSteps: List<SetupStep>? = null) {
        val detecting = vodDownloads.share(source) {
            val now = clock()
            val vod = runCatching {
                // A refresh's list of providers can be minutes old by the time this one's turn comes,
                // and the old account's titles would overwrite the new one's.
                if (!catalog.holdsAccountOf(source)) return@share null
                if (setupSteps == null) catalog.fetchVod(source, now) else setupVod(source, setupSteps)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Movies and shows failed for source ${source.id}", e)
                return@share null
            }
            // Recorded by whichever refresh fetched, so opening Movies straight after doesn't repeat it.
            // Failing to must not cost the shows below their one chance at New Episode detection.
            runCatching {
                if (vod.complete) {
                    settings.setVodFetchedAtMillis(source.id, now)
                    // After the write, not before: saving a provider then clears this record, so
                    // whichever order the two land in, a new account's titles never look fresh.
                    if (!catalog.holdsAccountOf(source)) settings.setVodFetchedAtMillis(source.id, 0L)
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "Couldn't record the movies and shows for source ${source.id}", e)
            }
            if (vod.updated.isEmpty()) return@share null
            scope.launch {
                // Its panel requests take the provider's turn like any other download.
                providerTurns.getValue(source.id).withLock {
                    runCatching { catalog.detectNewEpisodes(source, vod.updated, now) }
                        .onFailure { Log.w(TAG, "New Episode detection failed for source ${source.id}", it) }
                }
            }
        }
        // Each caller's own rule, whoever started the download they're sharing.
        if (waitForNewEpisodes) detecting?.join()
    }

    private suspend fun setupVod(source: Source, steps: List<SetupStep>): CatalogRepository.VodFetch {
        val movies = if (SetupStep.MOVIES in steps) {
            setup { copy(running = SetupStep.MOVIES) }
            catalog.fetchMovies(source)
        } else {
            CatalogRepository.ListFetch.SKIPPED
        }
        val shows = if (SetupStep.SHOWS in steps) {
            setup { copy(running = SetupStep.SHOWS) }
            catalog.fetchShows(source)
        } else {
            CatalogRepository.ListFetch.SKIPPED
        }
        return CatalogRepository.VodFetch(
            shows.updated,
            movies.complete && shows.complete && (movies.count > 0 || shows.count > 0),
        )
    }

    /**
     * Guide passes take turns. One forced while another runs leaves out the feeds that pass has just
     * downloaded, since they're as fresh as the viewer could ask, but still downloads any it skipped.
     */
    private suspend fun syncGuide(force: Boolean): EpgRepository.SyncSummary {
        val runningSince = guidePassStarted.get()
        return guideLock.withLock {
            val now = clock()
            guidePassStarted.set(now)
            try {
                guide.syncAll(now, force, skipFetchedSince = runningSince.takeIf { force && it > 0 })
            } finally {
                guidePassStarted.set(0)
            }
        }
    }

    /** A rule that can't be booked must not fail the refresh that revealed it. */
    private suspend fun rescan() {
        runCatching { rescanSeriesRules() }.onFailure { Log.w(TAG, "Series rule rescan failed", it) }
    }

    /**
     * One download of a part of a provider at a time: a second request awaits the running one and gets
     * its result, and a provider's parts take turns. It runs in [scope], not the caller's, so whoever
     * asked first leaving the screen doesn't take the download away from the rest.
     */
    private inner class OneAtATime<T> {
        private val running = HashMap<Source, Deferred<T>>()

        suspend fun share(source: Source, fetch: suspend () -> T): T {
            // Changed login details need their own download.
            val key = source.details()
            val turns = providerTurns.getOrPut(source.id) { Mutex() }
            val download = synchronized(running) {
                running.getOrPut(key) {
                    scope.async {
                        try {
                            turns.withLock { fetch() }
                        } finally {
                            synchronized(running) { running.remove(key) }
                        }
                    }
                }
            }
            return download.await()
        }
    }

    private companion object {
        const val TAG = "CatalogueRefresh"
        const val VOD_FRESH_MILLIS = 12L * 60 * 60 * 1000
    }
}

/** Only what reaches the provider: the name and the sync time moving on change nothing there. */
private fun Source.details() = copy(name = "", enabled = true, lastCatalogSyncMillis = 0L)

/** Whether [source] is still enabled with the login it was read with; a new live format is the same account. */
private suspend fun CatalogRepository.holdsAccountOf(source: Source) = enabledSources().any {
    it.id == source.id &&
        it.url == source.url &&
        it.username == source.username &&
        it.password == source.password &&
        it.macAddress == source.macAddress
}
