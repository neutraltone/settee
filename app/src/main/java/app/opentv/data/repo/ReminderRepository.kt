/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.ReminderDao
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Channel
import app.opentv.data.model.GuideProgramme
import app.opentv.data.model.Reminder
import kotlinx.coroutines.flow.Flow

/** Reads and writes over the reminders table. Pure data access — the alarm wiring lives elsewhere. */
class ReminderRepository(private val dao: ReminderDao, private val sources: SourceDao) {
    fun observeAll(): Flow<List<Reminder>> = dao.observeAll()
    fun observeStartsForChannel(channelId: Long): Flow<List<Long>> = dao.observeStartsForChannel(channelId)

    suspend fun byId(id: Long): Reminder? = dao.byId(id)
    suspend fun upcoming(nowMillis: Long): List<Reminder> = dao.upcoming(nowMillis)
    suspend fun forProgramme(channelId: Long, startMillis: Long): Reminder? = dao.forProgramme(channelId, startMillis)

    /**
     * Saves a reminder for [programme] on [channel], returning its id, or null when that programme
     * already has one. It records the channel by its source's name and stream id too, so it still finds
     * the channel after a refresh gives it a new row (#333).
     */
    suspend fun remind(channel: Channel, programme: GuideProgramme, autoTune: Boolean): Long? {
        if (dao.forProgramme(channel.id, programme.startUtcMillis) != null) return null
        return dao.insert(
            Reminder(
                channelId = channel.id,
                source = sources.byId(channel.sourceId)?.name.orEmpty(),
                streamId = channel.streamId,
                channelName = channel.displayName,
                logoUrl = channel.logoUrl,
                title = programme.title,
                startUtcMillis = programme.startUtcMillis,
                endUtcMillis = programme.endUtcMillis,
                autoTune = autoTune,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
    }
    suspend fun markFired(id: Long) = dao.markFired(id)
    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun deleteEndedBefore(beforeMillis: Long) = dao.deleteEndedBefore(beforeMillis)
}
