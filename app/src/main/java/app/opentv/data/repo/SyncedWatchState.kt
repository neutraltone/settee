/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.EpisodeDao
import app.opentv.data.db.MovieDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.db.ProfileDao
import app.opentv.data.db.SeriesDismissalDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.HandMark
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.Source
import app.opentv.sync.SyncDismissal
import app.opentv.sync.SyncPosition

/** What [SyncedProgress.import] did: [taken] positions merged, [waiting] for their titles to be fetched. */
data class ProgressImport(val taken: Int, val waiting: List<SyncPosition>)

/**
 * Progress as it travels between devices, reached as `viewerLibrary.syncedProgress`: by Profile name,
 * source name and the provider's id (ADR 0004). Only ViewerLibrary builds one.
 */
class SyncedProgress internal constructor(
    private val positions: PlaybackPositionDao,
    private val movies: MovieDao,
    private val episodes: EpisodeDao,
    private val sources: SourceDao,
    private val profiles: ProfileDao,
    private val merge: suspend (List<PlaybackPosition>) -> Int,
) {
    /** Every Profile's progress on this device. */
    suspend fun export(): List<SyncPosition> {
        val names = profiles.directory()
        val sourceNames = sources.directory()
        return positions.all().mapNotNull { pos ->
            names.name(pos.profileId)?.let { portable(pos, it, sourceNames) }
        }
    }

    /**
     * Merges progress from another device, newest wins, each placed by [arrival]. An episode this
     * device hasn't fetched, or a film kept from an add-on that it hasn't kept, comes back in
     * [ProgressImport.waiting], labelled with the Profile's name and the provider's name here now;
     * anything else it can't place is dropped, as is a position with a mark this version doesn't know.
     */
    suspend fun import(items: List<SyncPosition>, arrival: SyncArrival = SyncArrival.NONE): ProgressImport {
        val names = profiles.directory()
        val sourceIds = arrival.sources(sources)
        val placed = mutableListOf<PlaybackPosition>()
        val waiting = mutableListOf<SyncPosition>()
        // A mark a newer version added can't be shown here, and storing it as no mark would pass
        // that on with the original time, where it ties with, and beats, the real mark (#340).
        val readable = items.filter { it.mark == null || it.handMark() != null }
        for (arrived in readable) {
            val (profile, updatedAt) = arrival.placed(arrived.profile, arrived.updatedAtMillis)
            val item = arrived.copy(profile = profile, updatedAtMillis = updatedAt)
            val profileId = names.id(profile)
            val sourceId = sourceIds.id(item.source)
            // A title kept from an add-on is kept here too, from its IMDb id, even before this device
            // has an Add-ons source of its own (ADR 0011).
            val fromAddons = SourceDirectory.sameName(item.source, Source.ADDONS_NAME)
            if (profileId == null || (sourceId == null && !fromAddons)) continue
            val key = sourceId?.let { localKey(item, it) }
            when {
                key != null -> placed += item.toPosition(profileId, key)
                fromAddons && item.kind == MediaKey.MOVIE -> waiting += item.copy(source = Source.ADDONS_NAME)
                item.kind == MediaKey.EPISODE && item.seriesId != null ->
                    waiting += item.copy(source = sourceIds.currentName(item.source))
            }
        }
        return ProgressImport(merge(placed), waiting)
    }

    private suspend fun portable(pos: PlaybackPosition, profile: String, sources: SourceDirectory): SyncPosition? =
        when (val key = MediaKey.parse(pos.mediaKey)) {
            is MediaKey.Movie -> movies.byId(key.movieId)?.let {
                pos.portable(profile, sources.name(it.sourceId), MediaKey.MOVIE, it.streamId)
            }
            is MediaKey.Episode -> episodes.byId(key.episodeId)?.let {
                pos.portable(profile, sources.name(it.sourceId), MediaKey.EPISODE, it.episodeId, it.seriesId)
            }
            else -> null
        }

    private fun PlaybackPosition.portable(
        profile: String,
        source: String?,
        kind: String,
        id: String,
        seriesId: String? = null,
    ): SyncPosition? = source?.let {
        SyncPosition(profile, it, kind, id, seriesId, positionMillis, durationMillis, updatedAtMillis, mark?.name)
    }

    private suspend fun localKey(item: SyncPosition, sourceId: Long): MediaKey? = when (item.kind) {
        MediaKey.MOVIE -> movies.byProviderId(sourceId, item.id)?.let { MediaKey.Movie(it.id) }
        MediaKey.EPISODE -> episodes.byProviderId(sourceId, item.id)?.let { MediaKey.Episode(it.id) }
        else -> null
    }

    private fun SyncPosition.toPosition(profileId: Long, key: MediaKey) = PlaybackPosition(
        profileId = profileId,
        mediaKey = key.stored,
        positionMillis = positionMillis,
        durationMillis = durationMillis,
        updatedAtMillis = updatedAtMillis,
        mark = handMark(),
    )

    private fun SyncPosition.handMark(): HandMark? = HandMark.entries.firstOrNull { it.name == mark }
}

/**
 * Series taken out of Continue Watching as they travel between devices, reached as
 * `viewerLibrary.syncedDismissals`: by Profile and source name. Only ViewerLibrary builds one.
 */
class SyncedDismissals internal constructor(
    private val dismissals: SeriesDismissalDao,
    private val sources: SourceDao,
    private val profiles: ProfileDao,
    private val merge: suspend (List<SeriesDismissal>) -> Int,
) {
    /** Every Profile's series removed from Continue Watching on this device. */
    suspend fun export(): List<SyncDismissal> {
        val names = profiles.directory()
        val sourceNames = sources.directory()
        return dismissals.all().mapNotNull { d ->
            SyncDismissal(
                profile = names.name(d.profileId) ?: return@mapNotNull null,
                source = sourceNames.name(d.sourceId) ?: return@mapNotNull null,
                seriesId = d.seriesId,
                dismissedAtMillis = d.dismissedAtMillis,
                lastSeason = d.lastSeason,
                lastEpisode = d.lastEpisode,
            )
        }
    }

    /** Merges another device's removals, newest wins, each placed by [arrival]. Returns how many were taken. */
    suspend fun import(items: List<SyncDismissal>, arrival: SyncArrival = SyncArrival.NONE): Int {
        val names = profiles.directory()
        val sourceIds = arrival.sources(sources)
        val placed = items.mapNotNull { item ->
            val (profile, dismissedAt) = arrival.placed(item.profile, item.dismissedAtMillis)
            val profileId = names.id(profile) ?: return@mapNotNull null
            val sourceId = sourceIds.id(item.source) ?: return@mapNotNull null
            SeriesDismissal(
                profileId = profileId,
                sourceId = sourceId,
                seriesId = item.seriesId,
                dismissedAtMillis = dismissedAt,
                lastSeason = item.lastSeason,
                lastEpisode = item.lastEpisode,
            )
        }
        return merge(placed)
    }
}
