/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import app.opentv.core.ServiceLocator
import app.opentv.data.model.Favourite
import app.opentv.data.model.HandMark
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.ProfileSetting
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.StreamKind
import app.opentv.recording.SmbClient
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Reads local state into a [SyncBundle] and applies a received one. The single place that knows
 * how OpenTV's tables map onto the portable, cross-device bundle, so the LAN server/client and the
 * NAS sync all agree on what travels and how it merges.
 *
 * Merge policy (see [SyncBundle]): watch positions are two-way newest-wins; curation (profiles,
 * favourites, hidden channels, hidden categories) and NAS recordings are applied **additively** —
 * we spread what a peer has without deleting anything locally. That keeps a sync from ever wiping a
 * favourite you set on one box, un-hiding the structural separator rows the importer hides, or
 * dropping a recording that only one device knows about.
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

    suspend fun gather(): SyncBundle {
        val profiles = graph.profiles.all()
        val names = profiles.associate { it.id to it.name }
        val positions = gatherPositions(names)
        // Only finished recordings that live on the shared NAS travel — a device-local (file://)
        // capture can't be opened from another box, so sharing its metadata would just create a
        // library row that fails to play everywhere but here.
        val recordings = graph.recordingRepository.all()
            .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
            .map { r ->
                SyncRecording(
                    channelName = r.channelName,
                    logoUrl = r.logoUrl,
                    title = r.title,
                    description = r.description,
                    filePath = r.filePath,
                    streamUrl = r.streamUrl,
                    userAgent = r.userAgent,
                    scheduledStartMillis = r.scheduledStartMillis,
                    scheduledEndMillis = r.scheduledEndMillis,
                    startedAtMillis = r.startedAtMillis,
                    endedAtMillis = r.endedAtMillis,
                    sizeBytes = r.sizeBytes,
                )
            }
        val favourites = graph.viewerLibrary.favourites.everyProfiles().mapNotNull { fav ->
            val profileName = names[fav.profileId] ?: return@mapNotNull null
            SyncFavourite(profile = profileName, kind = fav.kind.name, source = fav.source, id = fav.itemId)
        }
        val profileSettings = graph.profileSettings.everyProfiles().mapNotNull { setting ->
            val profileName = names[setting.profileId] ?: return@mapNotNull null
            SyncProfileSetting(profileName, setting.name, setting.value, setting.updatedAtMillis)
        }
        return SyncBundle(
            generatedAtMillis = System.currentTimeMillis(),
            profiles = profiles.map { it.name },
            positions = positions,
            dismissals = gatherDismissals(names),
            favourites = favourites,
            hiddenChannels = graph.catalogRepository.hiddenChannelUrls(),
            hiddenCategories = graph.settings.hiddenCategories.value.toList(),
            recordings = recordings,
            profileSettings = profileSettings,
        )
    }

    /** Each Profile's progress, by Profile name and the title's stream URL. */
    private suspend fun gatherPositions(profileNames: Map<Long, String>): List<SyncPosition> =
        graph.viewerLibrary.everyProfilesProgress().mapNotNull { pos ->
            val profileName = profileNames[pos.profileId] ?: return@mapNotNull null
            val parts = pos.mediaKey.split(":", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            val streamUrl = when (parts[0]) {
                "movie" -> graph.catalogRepository.movie(id)?.streamUrl
                "ep" -> graph.catalogRepository.episode(id)?.streamUrl
                else -> null
            } ?: return@mapNotNull null
            SyncPosition(
                profile = profileName,
                streamUrl = streamUrl,
                kind = parts[0],
                positionMillis = pos.positionMillis,
                durationMillis = pos.durationMillis,
                updatedAtMillis = pos.updatedAtMillis,
                mark = pos.mark?.name,
            )
        }

    /** Each Profile's series removed from Continue Watching, by Profile and source name. */
    private suspend fun gatherDismissals(profileNames: Map<Long, String>): List<SyncDismissal> {
        val sourceNames = graph.sourceRepository.observeAll().first().associate { it.id to it.name }
        return graph.viewerLibrary.marks.everyProfiles().mapNotNull { d ->
            SyncDismissal(
                profile = profileNames[d.profileId] ?: return@mapNotNull null,
                source = sourceNames[d.sourceId] ?: return@mapNotNull null,
                seriesId = d.seriesId,
                dismissedAtMillis = d.dismissedAtMillis,
                lastSeason = d.lastSeason,
                lastEpisode = d.lastEpisode,
            )
        }
    }

    fun encode(bundle: SyncBundle): String = json.encodeToString(SyncBundle.serializer(), bundle)

    fun decode(bundleJson: String): SyncBundle = json.decodeFromString(SyncBundle.serializer(), bundleJson)

    /** Applies a received bundle. Returns what changed, so callers can report "Synced N items". */
    suspend fun apply(bundle: SyncBundle): MergeResult {
        // Profiles — add any the peer has that we're missing. Never remove (that would delete a
        // local profile's watch history).
        for (name in bundle.profiles) {
            if (name.isNotBlank() && graph.profiles.byName(name) == null) {
                runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }
            }
        }

        // Curation — additive union, keyed by stable identifiers. Count only genuinely new keys
        // (ones we hadn't already curated) so the "Synced N" line means "new to this device".
        val localHiddenChannels = graph.catalogRepository.hiddenChannelUrls().toHashSet()

        var favourites = 0
        var hidden = 0
        for (item in bundle.favourites) {
            val kind = runCatching { StreamKind.valueOf(item.kind) }.getOrNull() ?: continue
            val profileId = profileIdFor(item.profile) ?: continue
            if (graph.viewerLibrary.favourites.mergeSynced(
                    Favourite(profileId, kind, item.source, item.id),
                )
            ) {
                favourites++
            }
        }
        for (url in bundle.hiddenChannels) {
            graph.catalogRepository.markChannelHiddenByUrl(url)
            if (localHiddenChannels.add(url)) hidden++
        }
        if (bundle.hiddenCategories.isNotEmpty()) {
            graph.settings.setHiddenCategories(
                graph.settings.hiddenCategories.value + bundle.hiddenCategories,
            )
        }

        // NAS recordings — insert any smb:// recording we don't already have, keyed by its locator.
        // Never touch an existing row; a peer's copy and ours are the same file on the same NAS.
        var recordings = 0
        for (rec in bundle.recordings) {
            if (!SmbClient.isSmb(rec.filePath)) continue
            if (graph.recordingRepository.byFilePath(rec.filePath) != null) continue
            val inserted = runCatching {
                graph.recordingRepository.insert(
                    Recording(
                        // Ids from the source device mean nothing here; the row is self-contained.
                        channelId = 0,
                        sourceId = 0,
                        channelName = rec.channelName,
                        logoUrl = rec.logoUrl,
                        title = rec.title,
                        description = rec.description,
                        filePath = rec.filePath,
                        streamUrl = rec.streamUrl,
                        userAgent = rec.userAgent,
                        scheduledStartMillis = rec.scheduledStartMillis,
                        scheduledEndMillis = rec.scheduledEndMillis,
                        startedAtMillis = rec.startedAtMillis,
                        endedAtMillis = rec.endedAtMillis,
                        status = RecordingStatus.COMPLETED,
                        sizeBytes = rec.sizeBytes,
                    ),
                )
            }.isSuccess
            if (inserted) recordings++
        }

        // Watch positions — two-way, newest wins.
        var positions = 0
        for (item in bundle.positions) {
            val profileId = profileIdFor(item.profile) ?: continue
            val localId = when (item.kind) {
                "movie" -> graph.catalogRepository.movieByStreamUrl(item.streamUrl)?.id
                "ep" -> graph.catalogRepository.episodeByStreamUrl(item.streamUrl)?.id
                else -> null
            } ?: continue
            val mediaKey = "${item.kind}:$localId"
            val taken = graph.viewerLibrary.mergeSynced(
                PlaybackPosition(
                    profileId = profileId,
                    mediaKey = mediaKey,
                    positionMillis = item.positionMillis,
                    durationMillis = item.durationMillis,
                    updatedAtMillis = item.updatedAtMillis,
                    mark = item.mark?.let { runCatching { HandMark.valueOf(it) }.getOrNull() },
                ),
            )
            if (taken) positions++
        }
        // Counted with positions: both are where a Profile is with its titles.
        val sourceIds = graph.sourceRepository.observeAll().first().associate { it.name to it.id }
        for (item in bundle.dismissals) {
            val profileId = profileIdFor(item.profile) ?: continue
            val sourceId = sourceIds[item.source] ?: continue
            val taken = graph.viewerLibrary.marks.mergeSynced(
                SeriesDismissal(
                    profileId = profileId,
                    sourceId = sourceId,
                    seriesId = item.seriesId,
                    dismissedAtMillis = item.dismissedAtMillis,
                    lastSeason = item.lastSeason,
                    lastEpisode = item.lastEpisode,
                ),
            )
            if (taken) positions++
        }

        // Profile settings — newest wins per Profile and setting.
        var settings = 0
        for (item in bundle.profileSettings) {
            val profileId = profileIdFor(item.profile) ?: continue
            val taken = graph.profileSettings.mergeSynced(
                ProfileSetting(profileId, item.name, item.value, item.updatedAtMillis),
            )
            if (taken) settings++
        }
        return MergeResult(
            positions = positions,
            favourites = favourites,
            hidden = hidden,
            recordings = recordings,
            settings = settings,
        )
    }

    private suspend fun profileIdFor(name: String): Long? {
        graph.profiles.byName(name)?.let { return it.id }
        return runCatching { graph.profiles.insert(Profile(name = name, createdAtMillis = 0)) }.getOrNull()
    }
}
