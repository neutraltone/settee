/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import app.opentv.data.db.ChannelDao
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.repo.RecordingRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [RoomEstimate] for the recordings here as they are: each channel's quality from the catalogue, and
 * what's free on each storage a booking is on, from [freeSpace] (a [FreeSpace]).
 */
class BookingSpace(
    private val recordings: RecordingRepository,
    private val channels: ChannelDao,
    private val freeSpace: (RecordingStore) -> Long?,
    private val clock: () -> Long,
) {
    /** What each storage last said it had free, so a note stays up while its storage is asked again. */
    private val lastFree = ConcurrentHashMap<RecordingStore, Long>()

    /**
     * For each booking in [all] (every recording here) that may not fit, the minutes of room it has:
     * at once from what each storage last said, then again as each says now. They're asked all at
     * once, each answer sent as it comes, as a NAS can take as long as the network does or never
     * answer, and that mustn't hold up the notes for bookings anywhere else.
     */
    fun shortOfRoom(all: List<Recording>): Flow<Map<Long, Long>> = channelFlow {
        val stores = all.filter { it.status == RecordingStatus.SCHEDULED }
            .map { RecordingStore.of(it.filePath) }
            .toSet()
        val qualities = qualitiesOf(all)
        val known = ConcurrentHashMap(lastFree.filterKeys { it in stores })
        // One at a time, so an estimate made before another storage answered can't be sent after it.
        val sending = Mutex()
        suspend fun estimate() = sending.withLock {
            send(RoomEstimate(all, qualities, clock(), known.toMap()).shortOfRoom())
        }
        estimate()
        for (store in stores) {
            launch {
                val free = read(store)
                if (free == null) known.remove(store) else known[store] = free
                estimate()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The minutes of room booking [id] has when it may not fit, or null when it fits or that can't be
     * told. Asks only the booking's own storage: the others' bookings don't take its room.
     */
    suspend fun roomFor(id: Long): Long? {
        val all = recordings.all()
        val store = all.firstOrNull { it.id == id }?.let { RecordingStore.of(it.filePath) }
        val free = store?.let { read(it) }
        return if (store == null || free == null) {
            null
        } else {
            RoomEstimate(all, qualitiesOf(all), clock(), mapOf(store to free)).shortOfRoom()[id]
        }
    }

    /**
     * What [store] has free now, remembered for next time. Interrupted, not waited for, once nobody
     * wants it: a blocked NAS read would otherwise hold up whatever comes next.
     */
    private suspend fun read(store: RecordingStore): Long? = runInterruptible(Dispatchers.IO) { freeSpace(store) }
        .also { if (it == null) lastFree.remove(store) else lastFree[store] = it }

    /** Each channel's quality, by id. A channel gone from the catalogue since is of unknown quality. */
    private suspend fun qualitiesOf(all: List<Recording>): Map<Long, Int> = all.map { it.channelId }.distinct()
        .mapNotNull { id -> channels.byId(id)?.let { id to it.qualityRank } }
        .toMap()
}
