/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.LivePlaybackDao
import app.opentv.data.db.PlaybackPositionDao
import app.opentv.data.model.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What Home's Live Preview shows: the channel playing, or a reminder of one that has gone. */
sealed interface LivePreview {
    data class Playable(val channel: Channel) : LivePreview

    /** The channel has left the catalogue since: shown by the [name] and [logoUrl] it had then. */
    data class Gone(val name: String, val logoUrl: String?) : LivePreview
}

/**
 * The live channel each Profile last settled on, on this device, and the Live Preview it makes. Part
 * of ViewerLibrary, which owns it; not synced, since a channel following the viewer to another box
 * is more surprising than useful and live TV is never resumed.
 */
class LiveWatching internal constructor(
    private val live: LivePlaybackDao,
    private val positions: PlaybackPositionDao,
    private val activeProfileId: StateFlow<Long>,
    private val clock: () -> Long,
) {
    /** Records [channel] as the live channel the active Profile last settled on. */
    suspend fun record(channel: Channel) {
        live.record(activeProfileId.value, channel.id, clock())
    }

    /**
     * The active Profile's Live Preview: only while live was the last thing it played, so a movie,
     * episode or recording played since takes Home back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun preview(): Flow<LivePreview?> = activeProfileId.flatMapLatest { pid ->
        combine(live.observe(pid), positions.observeLastPlayedAt(pid)) { played, otherAt ->
            played?.takeIf { otherAt == null || it.playedAtMillis > otherAt }
        }.distinctUntilChanged().flatMapLatest { played ->
            if (played == null) {
                flowOf(null)
            } else {
                live.observeChannel(played.source, played.streamId).map { channel ->
                    when {
                        channel == null -> LivePreview.Gone(played.name, played.logoUrl)
                        // Hidden on purpose: Home doesn't bring it back up, not even as a reminder.
                        channel.hidden -> null
                        else -> LivePreview.Playable(channel)
                    }
                }
            }
        }
    }
}
