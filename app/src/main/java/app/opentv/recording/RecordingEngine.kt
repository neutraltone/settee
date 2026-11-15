/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ViewerError
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.repo.ChannelLineup
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.data.repo.recordable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Turns "record this" into a running capture: it creates the [Recording] row, works out where the
 * file goes and which URL to pull, then hands off to [RecordingService] to do the actual byte
 * copy in the foreground so it survives the user leaving the player or the screen going dark.
 */
@Suppress("LongParameterList") // clock is only passed by tests, to fix the time.
class RecordingEngine(
    private val appContext: Context,
    private val repo: RecordingRepository,
    private val sources: SourceRepository,
    private val settings: AppSettings,
    private val epgRepository: EpgRepository,
    private val lineup: ChannelLineup,
    /** Wall-clock time, so a test can fix it; the graph's own clock everywhere else. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Booking checks for an existing row and then inserts one, and a rescan can overlap another or a
     * rule being set, so without this they all pass the check and one airing is booked several times.
     */
    private val bookingLock = Mutex()

    /** Start recording [channel] now. [programme], when given, names and time-bounds the capture. */
    suspend fun startChannel(
        channel: Channel,
        programme: Programme? = null,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long {
        val source = sources.byId(channel.sourceId)
        val ua = source?.userAgent ?: Source.DEFAULT_USER_AGENT
        val now = clock()
        val title = programme?.title?.takeIf { it.isNotBlank() } ?: channel.displayName
        val filename = RecordingStorage.fileNameFor(channel.displayName, title, now)
        val locator = RecordingStorage.plannedLocator(appContext, settings, filename)

        val recording = Recording(
            channelId = channel.id,
            sourceId = channel.sourceId,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = title,
            description = programme?.description,
            filePath = locator,
            // Last known only: the capture asks PlaybackSources each time it connects.
            streamUrl = channel.streamUrl,
            userAgent = ua,
            scheduledStartMillis = programme?.startUtcMillis ?: 0,
            // Keep going past the listed end by the padding, so an overrun isn't cut off.
            scheduledEndMillis = programme?.endUtcMillis
                ?.let { it + settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L } ?: 0,
            startedAtMillis = now,
            status = RecordingStatus.RECORDING,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        RecordingService.start(appContext, id)
        return id
    }

    /** Stop an in-progress recording. */
    fun stop(recordingId: Long) = RecordingService.stop(appContext, recordingId)

    /**
     * Open [recording]'s file for its capture and repoint the row at where the bytes really go: a
     * USB document's `content://` URI, or a suffixed name when the planned one was taken (#354).
     */
    suspend fun openSink(recording: Recording): RecordingStorage.Sink {
        val sink = withContext(Dispatchers.IO) { RecordingStorage.openSink(appContext, settings, recording.filePath) }
        sink.resolvedLocator?.let { repo.setFilePath(recording.id, it) }
        return sink
    }

    /**
     * Delete [recording]: always its row, and its file unless another row still points at it — a
     * recording made before names were kept apart (#354) may share one.
     */
    suspend fun delete(recording: Recording) {
        if (recording.status == RecordingStatus.RECORDING) stop(recording.id)
        repo.delete(recording.id)
        deleteFileUnlessShared(recording)
    }

    /**
     * Off the main thread, where callers like the Recordings screen are: an SMB delete there is
     * refused as network on the main thread, and runCatching would swallow that, leaving the file.
     */
    private suspend fun deleteFileUnlessShared(recording: Recording) {
        if (repo.fileUsedByOthers(recording.id, recording.filePath)) return
        withContext(Dispatchers.IO) { RecordingStorage.delete(appContext, settings, recording.filePath) }
    }

    /**
     * Record a specific programme: if it's already on, start capturing now (bounded to its end);
     * if it's in the future, book an exact alarm to start it at broadcast time.
     */
    suspend fun recordProgramme(
        channel: Channel,
        programme: Programme,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long {
        val now = clock()
        return if (programme.startUtcMillis > now + LEAD_MILLIS) {
            scheduleProgramme(channel, programme, ruleId, profileId)
        } else {
            startChannel(channel, programme, ruleId, profileId)
        }
    }

    /** Book a future programme. Creates a SCHEDULED row and arms the alarm. */
    suspend fun scheduleProgramme(
        channel: Channel,
        programme: Programme,
        ruleId: Long? = null,
        profileId: Long = settings.activeProfileId.value,
    ): Long {
        // Padding: start early and keep going past the listed end, so a late start or an overrun
        // (sport, news) isn't clipped — the Sky-Q behaviour.
        val padStart = settings.recordPadStartMinutes.value.coerceAtLeast(0) * 60_000L
        val padEnd = settings.recordPadEndMinutes.value.coerceAtLeast(0) * 60_000L
        val scheduledStart = programme.startUtcMillis - padStart
        val scheduledEnd = programme.endUtcMillis + padEnd
        // De-dup on the (padded) start, deterministic per airing: a series rule that re-scans, or a
        // double-press of Schedule, mustn't stack a second identical booking. Different quality
        // variants have different channel ids, so recording HD *and* SD of the same show is allowed.
        if (ruleId != null && repo.alreadyBooked(ruleId, channel.id, scheduledStart)) return -1L
        repo.bookingAt(channel.id, scheduledStart)?.let { return it.id }

        // Multi-provider clash fallback: if another recording already owns this window on this
        // channel's provider, record from a *different* provider that has the same channel — two
        // connections, no cut. Falls back to the original channel when there's no free alternate.
        val recordFrom = alternateProviderForClash(channel, scheduledStart, scheduledEnd)
        val source = sources.byId(recordFrom.sourceId)
        val ua = source?.userAgent ?: Source.DEFAULT_USER_AGENT
        val filename = RecordingStorage.fileNameFor(channel.displayName, programme.title, programme.startUtcMillis)
        val locator = RecordingStorage.plannedLocator(appContext, settings, filename)

        val recording = Recording(
            // channelId stays the channel the user picked (keeps de-dup/booking stable); only the
            // provider we pull bytes from may differ.
            channelId = channel.id,
            sourceId = recordFrom.sourceId,
            channelName = channel.displayName,
            logoUrl = channel.logoUrl,
            title = programme.title,
            description = programme.description,
            filePath = locator,
            streamUrl = recordFrom.streamUrl,
            userAgent = ua,
            scheduledStartMillis = scheduledStart,
            scheduledEndMillis = scheduledEnd,
            status = RecordingStatus.SCHEDULED,
            seriesRuleId = ruleId,
            profileId = profileId,
        )
        val id = repo.insert(recording)
        val armAt = scheduledStart.coerceAtLeast(clock() + 1_000L)
        RecordingScheduler.set(appContext, id, armAt)
        // Warn the viewer just before we switch their screen to the recording (single-connection
        // auto-switch); only meaningful when auto-switch is on.
        if (settings.recordAutoSwitch.value) RecordingScheduler.setWarning(appContext, id, armAt, clock())
        return id
    }

    /**
     * Re-attempt a recording the user tapped Retry on. A booking still in the future is simply
     * re-armed; one whose window is on now (or unbounded) starts capturing immediately for what is
     * left; one whose window is entirely in the past cannot be recovered — the broadcast is gone —
     * and stays failed. Returns true when a retry was actually kicked off.
     */
    suspend fun retry(recording: Recording): Boolean {
        val now = clock()
        val start = recording.scheduledStartMillis
        return when {
            start > now + LEAD_MILLIS -> {
                repo.setStatus(recording.id, RecordingStatus.SCHEDULED, null)
                RecordingScheduler.set(appContext, recording.id, start)
                true
            }
            !recording.windowEndedBy(now) -> {
                // The failed attempt's file would count as taken and the capture would move on to a
                // suffixed name, leaving it behind with no row; it used to be overwritten, so drop
                // it. A USB document is reopened and overwritten in place, so it stays.
                if (!RecordingStorage.isContent(recording.filePath)) deleteFileUnlessShared(recording)
                repo.setStatus(recording.id, RecordingStatus.SCHEDULED, null)
                RecordingService.start(appContext, recording.id)
                true
            }
            else -> false
        }
    }

    /**
     * Re-arm the exact alarm for every scheduled recording. Alarms are dropped on a reboot, an
     * update or a force-stop, so [RecordingAlarmReceiver] calls this after the first two and
     * [app.opentv.OpenTvApp] on each launch, for the force-stop no receiver hears of. A booking whose
     * start slipped past while the app was dead fires almost at once for what's left of it; one whose
     * whole window went by is marked missed instead, since starting it would only write an empty file
     * and blame the channel. Re-setting an existing alarm is idempotent, so this is safe to run every
     * time. The alarms are set through [context]: a receiver passes its own, since the process-wide
     * graph can outlive the Application it was built with (it does across Robolectric tests), and
     * alarms set through a stale one land where nothing reads them.
     */
    suspend fun rearmScheduled(context: Context = appContext) {
        val now = clock()
        val autoSwitch = settings.recordAutoSwitch.value
        for (rec in repo.scheduled()) {
            if (markMissedIfOver(rec, Missed.NOT_RUNNING)) continue
            val at = if (rec.scheduledStartMillis <= now) now + 1_000L else rec.scheduledStartMillis
            RecordingScheduler.set(context, rec.id, at)
            if (autoSwitch) RecordingScheduler.setWarning(context, rec.id, at, now)
        }
    }

    /** Whether [recording]'s window has already ended, so there's nothing left to capture. */
    fun windowEnded(recording: Recording): Boolean = recording.windowEndedBy(clock())

    /** Why a booking whose window ended was never started, which decides what the viewer is told. */
    enum class Missed(val sentence: Int, val detail: String) {
        /** Its alarm was lost: Settee wasn't running, found on launch or after a reboot. */
        NOT_RUNNING(R.string.rec_error_missed, "Missed"),

        /** Settee was running, but Android delivered the alarm after the window ended. */
        LATE(R.string.rec_error_missed_late, "MissedLate"),
    }

    /**
     * Fail [recording] as missed for [why] when its window has already ended, before anything is
     * written to storage. Returns true when it was.
     */
    suspend fun markMissedIfOver(recording: Recording, why: Missed): Boolean {
        if (!windowEnded(recording)) return false
        repo.markFinished(
            id = recording.id,
            ok = false,
            atMillis = clock(),
            bytes = 0,
            error = ViewerError(appContext.getString(why.sentence), why.detail).stored(),
        )
        return true
    }

    /** Cancel a scheduled recording: disarm the alarm (and its pre-warning) and drop the row. */
    suspend fun cancelScheduled(recordingId: Long) {
        RecordingScheduler.cancel(appContext, recordingId)
        RecordingScheduler.cancelWarning(appContext, recordingId)
        repo.delete(recordingId)
    }

    /**
     * Create (or reuse) a series-link rule for [title] on this channel, and book every matching
     * future airing already in the guide, read the way [rescanSeriesRules] reads it. Not from the
     * guide grid's programmes: those leave out the description each booking is stored with. Airings
     * the guide doesn't have yet get booked by the rescan after a later guide refresh.
     */
    suspend fun recordSeries(channel: Channel, title: String): Long {
        val key = titleKeyOf(title)
        val existing = repo.ruleForChannelTitle(channel.id, key)
        val ruleId = existing?.id ?: repo.addRule(
            SeriesRule(
                channelId = channel.id,
                source = sources.byId(channel.sourceId)?.name.orEmpty(),
                streamId = channel.streamId,
                channelName = channel.displayName,
                titleKey = key,
                title = title,
                createdAtMillis = clock(),
            ),
        )
        val guideId = lineupChannelOf(channel.id)?.let { lineup.guideId(it) }
        if (guideId != null) {
            scheduleMatching(
                channel,
                key,
                ruleId,
                epgRepository.upcoming(guideId, clock(), AIRINGS_LOOKED_UP),
            )
        }
        return ruleId
    }

    /**
     * Remove a series-link rule and cancel every still-scheduled (not-yet-started) airing it booked.
     * Recordings already running or finished are left untouched — only the rule and its future
     * bookings go, so the series simply stops recording from here on (Sky Q "delete series link").
     */
    suspend fun removeSeriesRule(ruleId: Long) {
        repo.scheduled()
            .filter { it.seriesRuleId == ruleId }
            .forEach { cancelScheduled(it.id) }
        repo.deleteRule(ruleId)
    }

    /**
     * Book every not-yet-booked airing in [programmes] whose title matches the rule, each once. Two
     * guide feeds carrying one channel list each airing twice: a booking for later de-duplicates
     * itself, but one on now starts a capture straight away, so the second listing would start another.
     */
    suspend fun scheduleMatching(channel: Channel, titleKey: String, ruleId: Long, programmes: List<Programme>) {
        val now = clock()
        bookingLock.withLock {
            programmes
                .filter { it.endUtcMillis > now && titleKeyOf(it.title) == titleKey }
                // The listing with a description first, so it's the one the booking keeps.
                .sortedBy { it.description == null }
                .distinctBy { it.startUtcMillis }
                .forEach { recordProgramme(channel, it, ruleId) }
        }
    }

    /**
     * If [channel]'s own provider is already busy recording during [startMillis]..[endMillis],
     * return a copy of the same channel on a *free* provider so the two recordings don't fight over
     * one connection. Returns [channel] unchanged when its provider is free, when there's only one
     * provider, or when no other provider has the channel.
     */
    private suspend fun alternateProviderForClash(channel: Channel, startMillis: Long, endMillis: Long): Channel {
        val busy = busyProviderIds(startMillis, endMillis)
        // Our provider is free this window, or nothing else is booked — no need to move.
        if (channel.sourceId !in busy) return channel

        // Only what plays here: a clash mustn't move a capture up to something it can't be watched in.
        val variants = lineup.channelOf(channel.id)?.let { lineup.recordable(it) }.orEmpty()
            .groupBy { it.sourceId }
            .mapNotNull { (_, chans) -> chans.maxByOrNull { it.qualityRank } }

        for (variant in variants) {
            if (variant.sourceId == channel.sourceId || variant.sourceId in busy) continue
            if (variant.streamUrl.isBlank()) continue
            return variant
        }
        return channel
    }

    /** Provider ids with a recording scheduled or running that overlaps [startMillis]..[endMillis]. */
    private suspend fun busyProviderIds(startMillis: Long, endMillis: Long): Set<Long> =
        (repo.scheduled() + repo.active())
            .filter { rec ->
                val recEnd = if (rec.scheduledEndMillis > 0) rec.scheduledEndMillis else Long.MAX_VALUE
                rec.scheduledStartMillis < endMillis && startMillis < recEnd
            }
            .map { it.sourceId }
            .toSet()

    /** Loose title match key — case- and punctuation-insensitive, so "Countryfile" == "countryfile". */
    fun titleKeyOf(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Re-scan every series-link rule against the freshly-synced guide and book any new matching
     * airings. Run after each EPG refresh, so a weekly show gets its next episode booked as soon
     * as the guide reveals it — not just the airings that happened to be in the window when you
     * first set the rule.
     */
    suspend fun rescanSeriesRules() {
        val rules = repo.enabledRules()
        if (rules.isEmpty()) return
        val now = clock()
        for (rule in rules) {
            // The guide the rule was set from: its Channel's, whichever Quality the rule is on.
            val lineupChannel = lineupChannelOf(rule.channelId) ?: continue
            val channel = lineupChannel.qualities.first { it.id == rule.channelId }
            val guideId = lineup.guideId(lineupChannel) ?: continue
            scheduleMatching(channel, rule.titleKey, rule.id, epgRepository.upcoming(guideId, now, AIRINGS_LOOKED_UP))
        }
    }

    /** The Channel a Quality belongs to. One hidden or locked since a rule was set still records, from its own rows. */
    private suspend fun lineupChannelOf(channelId: Long) =
        lineup.channelOf(channelId) ?: lineup.channelOf(channelId, manage = true)

    private companion object {
        /** A programme starting within this window counts as "on now" and records immediately. */
        const val LEAD_MILLIS = 20_000L

        /** How far into a channel's guide a series rule looks for airings to book. */
        const val AIRINGS_LOOKED_UP = 200
    }
}

/**
 * Whether this recording's window had ended by [nowMillis], leaving nothing to capture. One started on
 * the spot with no end never has. Retry, the Recordings screen's Retry action and the missed check all
 * ask this, so they can't disagree.
 */
fun Recording.windowEndedBy(nowMillis: Long): Boolean = scheduledEndMillis in 1..nowMillis
