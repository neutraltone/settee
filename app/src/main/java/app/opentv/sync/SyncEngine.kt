/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.data.repo.ArrivalCeiling
import app.opentv.data.repo.NamelessCategory
import app.opentv.data.repo.SourceDirectory
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
        providerRenames = graph.sourceRepository.renames(),
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

    private suspend fun applyOne(received: SyncBundle, waiting: MutableList<SyncPosition>): MergeResult {
        // One per bundle, handed to every store that decides newest-wins; this merges nothing itself.
        val ceiling = ArrivalCeiling.NONE
        // First: everything else lands on a Profile by name, and these decide what the names are now.
        graph.profiles.importChanges(received.profileRenames, received.profileAdds)
        // Then the providers' names, so each item below carries the exact name its provider has here.
        graph.sourceRepository.importRenames(received.providerRenames)
        val bundle = received.labelledFor(graph.sourceRepository.directory())
        val history = graph.profiles.history
        graph.profiles.import(bundle.profiles, bundle.generatedAtMillis)
        graph.settings.importCategoryLocks(
            bundle.categoryLocks + bundle.hiddenCategories.map { SyncCategoryLock(it, 0) },
            ceiling,
        )
        val progress = graph.viewerLibrary.syncedProgress.import(bundle.progress, history, ceiling)
        waiting += progress.waiting
        return MergeResult(
            // Counted with positions: both are where a Profile is with its titles.
            positions = progress.taken +
                graph.viewerLibrary.syncedDismissals.import(bundle.dismissals, history, ceiling),
            favourites = graph.viewerLibrary.favourites.import(bundle.favourites, history, ceiling),
            hidden = graph.catalogRepository.importHiddenChannels(bundle.hiddenStreams, ceiling),
            recordings = graph.recordingRepository.import(bundle.recordings),
            settings = graph.profileSettings.import(bundle.profileSettings, history, ceiling),
        )
    }
}

/**
 * This bundle with every item's provider named as this device names it: "Sky " or an old name from
 * before a rename become this device's "Sky". The stores then match names exactly, and a Favourite is
 * kept under the name its provider's rows are joined by.
 */
private fun SyncBundle.labelledFor(sources: SourceDirectory): SyncBundle {
    val names = mutableMapOf<String, String>()
    fun name(label: String) = names.getOrPut(label) { sources.currentName(label) }
    return copy(
        progress = progress.map { it.copy(source = name(it.source)) },
        dismissals = dismissals.map { it.copy(source = name(it.source)) },
        favourites = favourites.map { it.copy(source = name(it.source)) },
        hiddenStreams = hiddenStreams.map { it.copy(source = name(it.source)) },
        // A nameless category's lock is keyed by its provider's name too (#338).
        hiddenCategories = hiddenCategories.map { NamelessCategory.relabelled(it, ::name) },
        categoryLocks = categoryLocks.map { it.copy(key = NamelessCategory.relabelled(it.key, ::name)) },
    )
}
