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
import app.opentv.sync.SyncDismissal
import app.opentv.sync.SyncPosition

/** What [SyncedProgress.import] did: [taken] positions merged, [waiting] for their episodes to be fetched. */
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
    private val merge: suspend (PlaybackPosition) -> Boolean,
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
     * Merges progress from another device, newest wins. An episode this device hasn't fetched comes
     * back in [ProgressImport.waiting]; anything else it can't place is dropped.
     */
    suspend fun import(items: List<SyncPosition>): ProgressImport {
        val names = profiles.directory()
        val sourceIds = sources.directory()
        var taken = 0
        val waiting = mutableListOf<SyncPosition>()
        for (item in items) {
            val profileId = names.id(item.profile)
            val sourceId = sourceIds.id(item.source)
            if (profileId == null || sourceId == null) continue
            val key = localKey(item, sourceId)
            when {
                key != null -> if (merge(item.toPosition(profileId, key))) taken++
                item.kind == MediaKey.EPISODE && item.seriesId != null -> waiting += item
            }
        }
        return ProgressImport(taken, waiting)
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
        mark = mark?.let { runCatching { HandMark.valueOf(it) }.getOrNull() },
    )
}

/**
 * Series taken out of Continue Watching as they travel between devices, reached as
 * `viewerLibrary.syncedDismissals`: by Profile and source name. Only ViewerLibrary builds one.
 */
class SyncedDismissals internal constructor(
    private val dismissals: SeriesDismissalDao,
    private val sources: SourceDao,
    private val profiles: ProfileDao,
    private val merge: suspend (SeriesDismissal) -> Boolean,
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

    /** Merges another device's removals, newest wins. Returns how many were taken. */
    suspend fun import(items: List<SyncDismissal>): Int {
        val names = profiles.directory()
        val sourceIds = sources.directory()
        return items.count { item ->
            val profileId = names.id(item.profile) ?: return@count false
            val sourceId = sourceIds.id(item.source) ?: return@count false
            merge(
                SeriesDismissal(
                    profileId = profileId,
                    sourceId = sourceId,
                    seriesId = item.seriesId,
                    dismissedAtMillis = item.dismissedAtMillis,
                    lastSeason = item.lastSeason,
                    lastEpisode = item.lastEpisode,
                ),
            )
        }
    }
}
