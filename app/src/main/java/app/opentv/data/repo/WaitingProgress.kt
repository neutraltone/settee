/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import android.util.Log
import androidx.annotation.VisibleForTesting
import app.opentv.core.AppDispatchers
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import app.opentv.sync.SyncFavourite
import app.opentv.sync.SyncPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Brings in synced progress for episodes this device hasn't fetched, since episodes are only stored
 * once a series is opened, and keeps the titles from add-ons that another device kept and this one
 * hasn't, from their IMDb ids, so their progress and Favourites land here (ADR 0011). Positions
 * aren't kept: the peer's bundle still carries one that couldn't be placed, so a later sync tries it
 * again, which is why there's no table of waiting positions. All that's kept is which episodes a
 * provider said it doesn't have (see [notFound]), and which add-on titles TMDB doesn't know.
 */
class WaitingProgress(
    private val library: ViewerLibrary,
    private val catalog: CatalogRepository,
    private val db: OpenTvDatabase,
    private val settings: AppSettings,
    private val addonTitles: AddonTitles,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) {
    // Its own scope because the sync screen doesn't wait for a provider, and may be gone before it answers.
    private val background = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Stops fetching and waits for a read still running, for a test about to close its database. */
    @VisibleForTesting
    internal suspend fun close() = background.coroutineContext.job.cancelAndJoin()

    /**
     * Fetches each series in [waiting] once, then merges its positions, and keeps each add-on title in
     * [waiting] or starred in [favourites] that this device hasn't kept. Returns before any of that happens.
     */
    fun fetchFor(waiting: List<SyncPosition>, favourites: List<SyncFavourite> = emptyList()) {
        val addonFavourites = favourites.filter { !it.removed && it.source.isAddons() }
        if (waiting.isEmpty() && addonFavourites.isEmpty()) return
        background.launch {
            runCatching { fetchAndMerge(waiting, addonFavourites) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w(TAG, "Couldn't bring in synced progress", it) }
        }
    }

    private suspend fun fetchAndMerge(waiting: List<SyncPosition>, addonFavourites: List<SyncFavourite>) {
        val (fromAddons, fromProviders) = waiting.partition { it.source.isAddons() }
        fetchFromProviders(fromProviders)
        keepAddonTitles(fromAddons, addonFavourites)
    }

    private suspend fun fetchFromProviders(waiting: List<SyncPosition>) {
        if (waiting.isEmpty()) return
        val enabled = db.sources().enabled().associateBy { it.name }
        val notFound = settings.syncEpisodesNotFound()
        val series = waiting
            .filter { it.source in enabled && keyOf(it) !in notFound }
            .groupBy { it.source to it.seriesId.orEmpty() }
        val down = HashSet<String>()
        // A limit because panels rate-limit, and this can run at app launch; the rest wait for a later sync.
        for ((id, positions) in series.entries.take(MAX_SERIES_PER_RUN)) {
            val (name, seriesId) = id
            // A provider that's down would otherwise cost one timeout per series.
            if (name !in down && !fetchAndMerge(enabled.getValue(name), seriesId, positions)) down += name
        }
    }

    /**
     * Keeps the films and shows from add-ons named by [positions] and [favourites] from TMDB, a show's
     * episodes too when a position is on one, then merges the positions. A Favourite needs nothing more:
     * it's stored by source name and IMDb id already, and lists once its title is kept.
     */
    private suspend fun keepAddonTitles(positions: List<SyncPosition>, favourites: List<SyncFavourite>) {
        val episodesByShow = positions.filter { it.kind == MediaKey.EPISODE }.groupBy { it.seriesId.orEmpty() }
        val films = positions.filter { it.kind == MediaKey.MOVIE }.map { it.id } +
            favourites.filter { it.kind == StreamKind.MOVIE.name }.map { it.id }
        val shows = episodesByShow.keys + favourites.filter { it.kind == StreamKind.SERIES.name }.map { it.id }
        val notFound = settings.syncEpisodesNotFound()
        val titles = (films.map { it to true } + shows.map { it to false })
            .distinct()
            .filter { (imdbId, _) -> imdbId.isNotEmpty() && notFoundKey(Source.ADDONS_NAME, imdbId) !in notFound }
        // The same limit as a provider's: one sync naming many titles at app launch shouldn't flood TMDB.
        for ((imdbId, isMovie) in titles.take(MAX_SERIES_PER_RUN)) {
            val kept = if (isMovie) addonTitles.keepSyncedFilm(imdbId) else addonTitles.keepSyncedShow(imdbId)
            // TMDB down, or no Key: a later sync tries again, rather than one timeout per title now.
            val title = kept.getOrElse { break }
            when (title) {
                // TMDB not knowing it is remembered, or every sync would ask again, forever.
                null -> settings.addSyncEpisodesNotFound(listOf(notFoundKey(Source.ADDONS_NAME, imdbId)))
                is Series -> episodesByShow[imdbId]?.let { fetchAndMerge(title, it) }
            }
        }
        val filmPositions = positions.filter { it.kind == MediaKey.MOVIE }
        if (filmPositions.isNotEmpty()) library.syncedProgress.import(filmPositions)
    }

    /** [fetchAndMerge] for a show kept from an add-on, whose episodes TMDB lists. */
    private suspend fun fetchAndMerge(show: Series, positions: List<SyncPosition>) {
        db.sources().byId(show.sourceId)?.let { fetchAndMerge(it, show.seriesId, positions) }
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
        if (db.episodes().forSeries(source.id, seriesId).isEmpty()) return
        settings.addSyncEpisodesNotFound(missing.map(::keyOf))
    }

    private fun keyOf(position: SyncPosition) = notFoundKey(position.source, position.id)

    /** Waits for the work still running. Tests close their database straight after a sync. */
    internal suspend fun awaitBackground() = background.coroutineContext.job.children.toList().joinAll()

    private companion object {
        const val TAG = "WaitingProgress"
        const val MAX_SERIES_PER_RUN = 25
    }
}

private fun String.isAddons() = SourceDirectory.sameName(this, Source.ADDONS_NAME)

// Keyed by the provider's name, as the waiting positions are labelled, so a rename has to move them.
private fun notFoundKey(source: String, episodeId: String) = "$source\t$episodeId"

/** Moves the episodes [WaitingProgress] stopped asking the provider [from] for to its new name, [to]. */
internal fun AppSettings.carryEpisodesNotFound(from: String, to: String) {
    val prefix = notFoundKey(from, "")
    val keys = syncEpisodesNotFound()
    if (keys.none { it.startsWith(prefix) }) return
    setSyncEpisodesNotFound(keys.mapTo(HashSet()) { if (it.startsWith(prefix)) to + it.removePrefix(from) else it })
}
