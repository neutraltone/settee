/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.RecordingDao
import app.opentv.data.db.SeriesRuleDao
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule
import app.opentv.recording.SmbClient
import app.opentv.sync.SyncRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

/** Reads and writes over the recordings + series-rule tables. Pure data access, no capture logic. */
class RecordingRepository(
    private val dao: RecordingDao,
    private val ruleDao: SeriesRuleDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // One query shared by every screen that shows what's recording: Live TV, Home and the player
    // ask for this while composing, and a new Room flow each time restarted the query on every
    // redraw. Nothing is replayed once the last viewer has gone, so a later first() reads afresh.
    private val active: Flow<List<Recording>> by lazy {
        dao.observeActive().shareIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            replay = 1,
        )
    }

    fun observeAll(): Flow<List<Recording>> = dao.observeAll()
    fun observeActive(): Flow<List<Recording>> = active
    fun observeRules(): Flow<List<SeriesRule>> = ruleDao.observeAll()

    suspend fun byId(id: Long): Recording? = dao.byId(id)
    suspend fun all(): List<Recording> = dao.all()
    suspend fun active(): List<Recording> = dao.active()
    suspend fun scheduled(): List<Recording> = dao.scheduled()

    suspend fun insert(recording: Recording): Long = dao.insert(recording)

    /**
     * The recordings another device can play: finished ones on the shared NAS. A device-local capture
     * can't be opened from another box, so sharing it would make a row that fails to play everywhere else.
     */
    suspend fun export(): List<SyncRecording> = dao.all()
        .filter { it.status == RecordingStatus.COMPLETED && SmbClient.isSmb(it.filePath) }
        .map { r ->
            SyncRecording(
                channelName = r.channelName,
                title = r.title,
                description = r.description,
                filePath = r.filePath,
                userAgent = r.userAgent,
                scheduledStartMillis = r.scheduledStartMillis,
                scheduledEndMillis = r.scheduledEndMillis,
                startedAtMillis = r.startedAtMillis,
                endedAtMillis = r.endedAtMillis,
                sizeBytes = r.sizeBytes,
            )
        }

    /**
     * Adds NAS recordings this device doesn't have, by their locator, and never touches an existing
     * row: a peer's copy and ours are the same file. Returns how many were new here.
     */
    suspend fun import(items: List<SyncRecording>): Int = items.count { rec ->
        if (!SmbClient.isSmb(rec.filePath) || dao.byFilePath(rec.filePath) != null) return@count false
        runCatching {
            dao.insert(
                Recording(
                    // Ids from the source device mean nothing here; the row is self-contained.
                    channelId = 0,
                    sourceId = 0,
                    channelName = rec.channelName,
                    title = rec.title,
                    description = rec.description,
                    filePath = rec.filePath,
                    streamUrl = "",
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
    }
    suspend fun update(recording: Recording) = dao.update(recording)

    /** The Profile is being removed: its recordings stay, no longer for anyone in particular. */
    suspend fun forgetProfile(profileId: Long) = dao.clearProfile(profileId)

    /** Profile [from] is merging into [into]: its recordings follow. */
    suspend fun moveProfile(from: Long, into: Long) = dao.moveProfile(from, into)

    suspend fun markStarted(id: Long, atMillis: Long) = dao.markStarted(id, RecordingStatus.RECORDING, atMillis)

    suspend fun setSize(id: Long, bytes: Long) = dao.setSize(id, bytes)

    /** Repoint a recording at its real locator (a USB capture's content:// URI, once created). */
    suspend fun setFilePath(id: Long, filePath: String) = dao.setFilePath(id, filePath)

    suspend fun markFinished(id: Long, ok: Boolean, atMillis: Long, bytes: Long, error: String?) = dao.markFinished(
        id = id,
        status = if (ok) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
        endedAt = atMillis,
        bytes = bytes,
        error = error,
    )

    suspend fun setStatus(id: Long, status: RecordingStatus, error: String? = null) = dao.setStatus(id, status, error)

    suspend fun delete(id: Long) = dao.delete(id)

    /** Cold-start reconciliation: anything left "recording" was killed mid-capture. */
    suspend fun failInterrupted() = dao.failInterrupted()

    // ---- Series-link rules -------------------------------------------------------------------

    suspend fun addRule(rule: SeriesRule): Long = ruleDao.insert(rule)
    suspend fun deleteRule(id: Long) = ruleDao.delete(id)
    suspend fun enabledRules(): List<SeriesRule> = ruleDao.enabled()
    suspend fun ruleForChannelTitle(channelId: Long, titleKey: String): SeriesRule? =
        ruleDao.forChannelTitle(channelId, titleKey)

    suspend fun alreadyBooked(ruleId: Long, channelId: Long, startMillis: Long): Boolean =
        dao.countForRuleAt(ruleId, channelId, startMillis) > 0

    /** An existing live/scheduled booking for this exact airing (channel + start), or null. */
    suspend fun bookingAt(channelId: Long, startMillis: Long): Recording? = dao.bookingAt(channelId, startMillis)
}
