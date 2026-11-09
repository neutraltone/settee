/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import kotlinx.serialization.json.Json

/**
 * Collects each store's portable items into a [SyncBundle], encodes it, and hands a received one
 * back to the stores, counting what they took. The stores own identity and merge rules; this is
 * what the LAN server/client and the NAS sync share, so they agree on what travels.
 */
class SyncEngine(private val graph: ServiceLocator.Graph) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** What a single [apply] actually changed — summed across peers for the NAS "Synced N" line. */
    data class MergeResult(
        val positions: Int = 0,
        val favourites: Int = 0,
        val hidden: Int = 0,
        val recordings: Int = 0,
        val settings: Int = 0,
    ) {
        val total: Int get() = positions + favourites + hidden + recordings + settings

        operator fun plus(other: MergeResult): MergeResult = MergeResult(
            positions = positions + other.positions,
            favourites = favourites + other.favourites,
            hidden = hidden + other.hidden,
            recordings = recordings + other.recordings,
            settings = settings + other.settings,
        )
    }

    suspend fun gather(): SyncBundle = SyncBundle(
        generatedAtMillis = graph.clock(),
        // First: asking for them is what seeds a fresh install's Profile, which the rest belongs to.
        profiles = graph.profiles.export(),
        profileRenames = graph.profiles.history.renames,
        profileAdds = graph.profiles.history.adds,
        progress = graph.viewerLibrary.syncedProgress.export(),
        dismissals = graph.viewerLibrary.syncedDismissals.export(),
        favourites = graph.viewerLibrary.favourites.export(),
        hiddenStreams = graph.catalogRepository.exportHiddenChannels(),
        hiddenCategories = graph.settings.hiddenCategories.value.toList(),
        categoryLocks = graph.settings.exportCategoryLocks(),
        recordings = graph.recordingRepository.export(),
        profileSettings = graph.profileSettings.export(),
    )

    fun encode(bundle: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), bundle)

    fun decode(bundleJson: String): SyncBundle = json.decodeFromString(SyncBundle.serializer(), bundleJson)

    /**
     * Applies received bundles as one sync run. Returns what changed, so callers can report "Synced N
     * items". Episodes this device hasn't fetched follow in the background, after this returns.
     */
    suspend fun apply(vararg bundles: SyncBundle): MergeResult {
        val waiting = mutableListOf<SyncPosition>()
        val merged = bundles.fold(MergeResult()) { merged, bundle -> merged + applyOne(bundle, waiting) }
        graph.waitingProgress.fetchFor(waiting)
        return merged
    }

    private suspend fun applyOne(bundle: SyncBundle, waiting: MutableList<SyncPosition>): MergeResult {
        // First: everything else lands on a Profile by name, and these decide what the names are now.
        graph.profiles.importChanges(bundle.profileRenames, bundle.profileAdds)
        val history = graph.profiles.history
        graph.profiles.import(bundle.profiles, bundle.generatedAtMillis)
        graph.settings.importCategoryLocks(
            bundle.categoryLocks + bundle.hiddenCategories.map { SyncCategoryLock(it, 0) },
        )
        val progress = graph.viewerLibrary.syncedProgress.import(bundle.progress, history)
        waiting += progress.waiting
        return MergeResult(
            // Counted with positions: both are where a Profile is with its titles.
            positions = progress.taken + graph.viewerLibrary.syncedDismissals.import(bundle.dismissals, history),
            favourites = graph.viewerLibrary.favourites.import(bundle.favourites, history),
            hidden = graph.catalogRepository.importHiddenChannels(bundle.hiddenStreams),
            recordings = graph.recordingRepository.import(bundle.recordings),
            settings = graph.profileSettings.import(bundle.profileSettings, history),
        )
    }
}
