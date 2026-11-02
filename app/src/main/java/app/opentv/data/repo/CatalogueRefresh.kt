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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a Catalogue Refresh was asked for, which decides what it fetches. */
sealed interface RefreshTrigger {
    /** The six-hourly background job. */
    data object Periodic : RefreshTrigger

    /** The viewer asked: Home's refresh, or Refresh now in settings. */
    data object Manual : RefreshTrigger

    /** A provider was saved, on the TV or from a phone. */
    data class ProviderAdded(val source: Source) : RefreshTrigger

    /** Movies or Shows was opened: fetches only the providers whose titles are out of date. */
    data object MoviesOrShowsOpened : RefreshTrigger

    /** The app started. Guide only, and only feeds that aren't recent. */
    data object AppLaunch : RefreshTrigger

    /** The viewer asked for the guide from Guide settings. */
    data object GuideSettings : RefreshTrigger
}

/**
 * How a Catalogue Refresh went. [guide] is null when it didn't touch the guide, or hadn't finished
 * when this was returned. [failure] is why a newly added provider couldn't load.
 */
data class RefreshOutcome(
    val channels: Int = 0,
    val problems: Int = 0,
    val guide: EpgRepository.SyncSummary? = null,
    val failure: String? = null,
)

/** What a viewer can see of a Catalogue Refresh they asked for; only those show on screen. */
sealed interface RefreshStatus {
    data object Idle : RefreshStatus

    data object Refreshing : RefreshStatus

    data class Refreshed(val channels: Int, val problems: Int, val guideMatched: Int, val guideTotal: Int) :
        RefreshStatus

    data object LoadingChannels : RefreshStatus

    /** A new provider's channels are in; its guide is still on the way. */
    data class BuildingGuide(val channels: Int) : RefreshStatus

    data class ProviderReady(val channelsMatched: Int, val channelsTotal: Int) : RefreshStatus

    data class ProviderFailed(val reason: String) : RefreshStatus
}

/**
 * Fetching a provider's channels, movies, shows and guide again and merging them into what's on the
 * device. Every trigger asks here, and this decides what to fetch, in what order and whether it's
 * needed; [CatalogRepository] and [EpgRepository] do the fetching and merging.
 *
 * Downloads run in [scope] rather than the caller's, so a screen closing doesn't stop one that
 * something else is waiting on. It should outlive every screen.
 */
class CatalogueRefresh(
    private val catalog: CatalogRepository,
    guide: EpgRepository,
    private val rescanSeriesRules: suspend () -> Unit,
    private val settings: AppSettings,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {

    private val _status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)

    /**
     * Held here rather than by the screen, because a manual refresh runs as background work that
     * outlives whichever screen asked for it.
     */
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    private val providers = ProviderDownloads(catalog, settings, clock, scope)
    private val guides = GuideDownloads(guide, clock, scope)

    /** Rescans book recordings: two at once could both book one airing. */
    private val rescanLock = Mutex()

    suspend fun refresh(trigger: RefreshTrigger): RefreshOutcome = when (trigger) {
        RefreshTrigger.Periodic -> everything(forceGuide = false)
        // In our scope, not the caller's: the status they set must reach its end even if WorkManager
        // stops the worker or the screen closes, or the viewer's spinner would never clear.
        RefreshTrigger.Manual -> scope.async { manual() }.await()
        is RefreshTrigger.ProviderAdded -> scope.async { providerAdded(trigger.source) }.await()
        RefreshTrigger.MoviesOrShowsOpened -> staleVod()
        RefreshTrigger.AppLaunch -> guideOnly(force = false)
        RefreshTrigger.GuideSettings -> guideOnly(force = true)
    }

    /** Whether opening Movies or Shows has anything to download, so it only says so when it does. */
    suspend fun vodIsStale(): Boolean = staleProviders().isNotEmpty()

    private suspend fun manual(): RefreshOutcome {
        _status.value = RefreshStatus.Refreshing
        val outcome = everything(forceGuide = true)
        _status.value = RefreshStatus.Refreshed(
            channels = outcome.channels,
            problems = outcome.problems,
            guideMatched = outcome.guide?.channelsMatched ?: 0,
            guideTotal = outcome.guide?.channelsTotal ?: 0,
        )
        return outcome
    }

    private suspend fun everything(forceGuide: Boolean): RefreshOutcome {
        val sources = catalog.enabledSources()
        // A free guide left switched on is a large download with no channels to show it against.
        if (sources.isEmpty()) return RefreshOutcome()
        var channels = 0
        var problems = 0
        // One provider at a time, so a big one's download doesn't compete with the next's.
        for (source in sources) {
            val result = providers.fetch(source, partsSwitchedOn(source), EpisodeDetection.WAIT)
            channels += result.channels
            problems += result.problems
        }
        val summary = guides.fetch(forceGuide)
        rescan()
        return RefreshOutcome(channels, problems + summary.feedsFailed, summary)
    }

    /**
     * Channels now, whatever the Live switch says, so the viewer can see straight away whether the
     * login works. Movies and shows wait until they're opened: a 40,000-title list must not hold up
     * channels they can already watch.
     */
    private suspend fun providerAdded(source: Source): RefreshOutcome {
        _status.value = RefreshStatus.LoadingChannels
        val result = providers.fetch(source, setOf(Part.LIVE), EpisodeDetection.LAUNCH)
        // The saved details may be a different account, whose titles were never fetched. Not until
        // now, so that a download still finishing with the old details can't record them as fresh.
        settings.setVodFetchedAtMillis(source.id, 0L)
        if (result.failure != null) {
            _status.value = RefreshStatus.ProviderFailed(result.failure)
            return RefreshOutcome(problems = result.problems, failure = result.failure)
        }
        _status.value = RefreshStatus.BuildingGuide(result.channels)
        scope.launch {
            // A pass already running started before this provider had a feed to download.
            val summary = guides.fetch(force = false, needsLatestFeeds = true)
            _status.value = RefreshStatus.ProviderReady(summary.channelsMatched, summary.channelsTotal)
            rescan()
        }
        return RefreshOutcome(channels = result.channels)
    }

    private suspend fun staleVod(): RefreshOutcome {
        staleProviders().forEach { providers.fetch(it, setOf(Part.VOD), EpisodeDetection.LAUNCH) }
        return RefreshOutcome()
    }

    private suspend fun staleProviders(): List<Source> {
        val now = clock()
        return catalog.enabledSources().filter {
            Part.VOD in partsSwitchedOn(it) && now - settings.vodFetchedAtMillis(it.id) >= VOD_TTL_MILLIS
        }
    }

    private suspend fun guideOnly(force: Boolean): RefreshOutcome {
        // The viewer may have just turned a feed on, which a pass already running wouldn't include.
        val summary = guides.fetch(force, needsLatestFeeds = force)
        return RefreshOutcome(problems = summary.feedsFailed, guide = summary)
    }

    private fun partsSwitchedOn(source: Source): Set<Part> = buildSet {
        if (settings.liveEnabled.value) add(Part.LIVE)
        // Only Xtream has VOD to fetch separately.
        val anyVod = settings.moviesEnabled.value || settings.seriesEnabled.value
        if (anyVod && source.kind == SourceKind.XTREAM) add(Part.VOD)
    }

    private suspend fun rescan() {
        runCatching { rescanLock.withLock { rescanSeriesRules() } }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "Series rule rescan failed", it) }
    }

    private companion object {
        const val VOD_TTL_MILLIS = 12L * 60 * 60 * 1000
    }
}

private const val TAG = "CatalogueRefresh"

private enum class Part { LIVE, VOD }

/** New Episode detection: waited for by refreshes nobody is watching; launched when a viewer is. */
private enum class EpisodeDetection { WAIT, LAUNCH }

/**
 * At most one download per key. A request made while one is running gets that one, with the
 * [Shared.request] it was started for, so the caller can decide whether it's enough.
 */
private class SharedDownloads<K : Any, Req, Res>(private val scope: CoroutineScope) {
    data class Shared<Req, Res>(val request: Req, val result: Deferred<Res>, val startedHere: Boolean)

    /** Guarded by itself. */
    private val running = HashMap<K, Shared<Req, Res>>()

    fun joinOrStart(key: K, request: Req, download: suspend (Req) -> Res): Shared<Req, Res> = synchronized(running) {
        running[key]?.copy(startedHere = false) ?: Shared(
            request,
            scope.async {
                try {
                    download(request)
                } finally {
                    synchronized(running) { running.remove(key) }
                }
            },
            startedHere = true,
        ).also { running[key] = it }
    }
}

/** One pass over the guide feeds at a time. */
private class GuideDownloads(private val guide: EpgRepository, private val clock: () -> Long, scope: CoroutineScope) {
    /** The request is whether the pass was forced. */
    private val downloads = SharedDownloads<Unit, Boolean, EpgRepository.SyncSummary>(scope)

    /**
     * Joins a pass already running unless it won't do: it wasn't forced and this needs to be, or
     * [needsLatestFeeds] says feeds may have changed since it read them. Then this waits for it to
     * finish and runs its own.
     */
    suspend fun fetch(force: Boolean, needsLatestFeeds: Boolean = false): EpgRepository.SyncSummary {
        var mayJoin = !needsLatestFeeds
        while (true) {
            val shared = downloads.joinOrStart(Unit, force, ::download)
            val summary = shared.result.await()
            val covers = shared.request || !force
            if (shared.startedHere || (mayJoin && covers)) return summary
            // Anything running from here on started after this was asked for.
            mayJoin = true
        }
    }

    /** In a shared download, so a failure must reach whoever's waiting as a result, not a crash. */
    private suspend fun download(force: Boolean): EpgRepository.SyncSummary =
        runCatching { guide.syncAll(clock(), force) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "Guide refresh failed", it) }
            .getOrDefault(EpgRepository.SyncSummary(0, 1, 0, 0, 0))
}

private data class ProviderResult(val channels: Int = 0, val problems: Int = 0, val failure: String? = null)

/** One download at a time per provider, however many refreshes want it. */
private class ProviderDownloads(
    private val catalog: CatalogRepository,
    private val settings: AppSettings,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private data class Request(val source: Source, val parts: Set<Part>)

    /** [episodes] is the New Episode detection it started, for whoever wants to wait for it. */
    private data class Downloaded(val result: ProviderResult, val episodes: Job? = null)

    private val downloads = SharedDownloads<Long, Request, Downloaded>(scope)

    /**
     * Gets [wanted] for [source], joining whatever's already downloading it instead of starting
     * another. What the running one doesn't cover is fetched after it, one at a time, and so is all
     * of it if the running one has the provider's old details: what that finds says nothing about
     * the new ones.
     */
    suspend fun fetch(source: Source, wanted: Set<Part>, detection: EpisodeDetection): ProviderResult {
        var missing = wanted
        var total = ProviderResult()
        while (missing.isNotEmpty()) {
            val shared = downloads.joinOrStart(source.id, Request(source, missing), ::download)
            val downloaded = shared.result.await()
            // Each caller's own rule, whoever started the download.
            if (detection == EpisodeDetection.WAIT) downloaded.episodes?.join()
            if (sameDetails(shared.request.source, source)) {
                val result = downloaded.result
                total = ProviderResult(
                    channels = total.channels + result.channels,
                    problems = total.problems + result.problems,
                    failure = total.failure ?: result.failure,
                )
                missing = missing - shared.request.parts
            }
        }
        return total
    }

    /** A refresh moves the stored last-refreshed time, which doesn't make it a different provider. */
    private fun sameDetails(a: Source, b: Source) =
        a.copy(lastCatalogSyncMillis = 0) == b.copy(lastCatalogSyncMillis = 0)

    private suspend fun download(request: Request): Downloaded {
        val (source, parts) = request
        val now = clock()
        val live = if (Part.LIVE in parts) catalog.fetchLive(source, now) else null
        // Movies and shows on a login that just failed would only fail too, more slowly.
        if (live is CatalogRepository.SyncResult.Failed) {
            return Downloaded(ProviderResult(problems = 1, failure = live.reason))
        }
        val channels = (live as? CatalogRepository.SyncResult.Success)?.channelCount ?: 0
        val episodes = if (Part.VOD in parts) downloadVod(source, now) else null
        return Downloaded(ProviderResult(channels), episodes)
    }

    /** A failure isn't a problem to report: plenty of accounts have no VOD, and that's normal. */
    private suspend fun downloadVod(source: Source, now: Long): Job {
        val vod = catalog.fetchVod(
            source,
            now,
            movies = settings.moviesEnabled.value,
            shows = settings.seriesEnabled.value,
        )
        // Only a real success counts as fresh, so a failure tries again the next time it's opened.
        if (vod.succeeded) settings.setVodFetchedAtMillis(source.id, now)
        return scope.launch { detectNewEpisodes(source, vod, now) }
    }

    private suspend fun detectNewEpisodes(source: Source, vod: CatalogRepository.VodFetch, now: Long) {
        runCatching { catalog.detectNewEpisodes(source, vod.updatedSeries, now) }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "New Episode detection failed for source ${source.id}", it) }
    }
}
