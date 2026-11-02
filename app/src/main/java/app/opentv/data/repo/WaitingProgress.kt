/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import app.opentv.core.AppSettings
import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.sync.SyncPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Brings in synced progress for episodes this device hasn't fetched, since episodes are only stored
 * once a series is opened. Positions aren't kept: the peer's bundle still carries one that couldn't
 * be placed, so a later sync tries it again, which is why there's no table of waiting positions.
 * All that's kept is which episodes a provider said it doesn't have (see [notFound]).
 */
class WaitingProgress(
    private val library: ViewerLibrary,
    private val catalog: CatalogRepository,
    private val sources: SourceDao,
    private val episodes: EpisodeDao,
    private val settings: AppSettings,
) {
    // Its own scope because the sync screen doesn't wait for a provider, and may be gone before it answers.
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fetches each series in [waiting] once, then merges its positions. Returns before any of that happens. */
    fun fetchFor(waiting: List<SyncPosition>) {
        if (waiting.isEmpty()) return
        background.launch {
            runCatching { fetchAndMerge(waiting) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Couldn't bring in synced progress", it) }
        }
    }

    private suspend fun fetchAndMerge(waiting: List<SyncPosition>) {
        val enabled = sources.enabled().associateBy { it.name }
        val notFound = settings.syncEpisodesNotFound()
        val series = waiting
            .filter { it.source in enabled && notFoundKey(it) !in notFound }
            .groupBy { it.source to it.seriesId.orEmpty() }
        val down = HashSet<String>()
        // A limit because panels rate-limit, and this can run at app launch; the rest wait for a later sync.
        for ((id, positions) in series.entries.take(MAX_SERIES_PER_RUN)) {
            val (name, seriesId) = id
            // A provider that's down would otherwise cost one timeout per series.
            if (name !in down && !fetchAndMerge(enabled.getValue(name), seriesId, positions)) down += name
        }
    }

    /** Returns false when the provider couldn't be reached. */
    private suspend fun fetchAndMerge(source: Source, seriesId: String, positions: List<SyncPosition>): Boolean {
        // Dated back to the position, not now: "first seen after the Profile Watched the one before" is
        // what makes a New Episode, and these episodes are only new to this device, not to the viewer.
        val seenBy = positions.minOf { it.updatedAtMillis }.coerceAtMost(System.currentTimeMillis())
        if (!catalog.ensureEpisodes(source, seriesId, seenBy)) return false
        val missing = library.syncedProgress.import(positions).waiting
        if (missing.isNotEmpty()) notFound(source, seriesId, missing)
        return true
    }

    /**
     * Remembers episodes the provider's answer left out, or every sync would ask it again, forever: the
     * peer keeps naming them. Safe to remember, since opening the series stores an episode that later
     * appears, and then it's placed without coming here. An empty answer proves nothing: panels
     * send one when they're struggling, so that series is asked again.
     */
    private suspend fun notFound(source: Source, seriesId: String, missing: List<SyncPosition>) {
        if (episodes.forSeries(source.id, seriesId).isEmpty()) return
        settings.addSyncEpisodesNotFound(missing.map(::notFoundKey))
    }

    private fun notFoundKey(position: SyncPosition) = "${position.source}\t${position.id}"

    /** Waits for the work still running. Tests close their database straight after a sync. */
    internal suspend fun awaitBackground() = background.coroutineContext.job.children.toList().joinAll()

    private companion object {
        const val TAG = "WaitingProgress"
        const val MAX_SERIES_PER_RUN = 25
    }
}
