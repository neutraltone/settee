/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.os.SystemClock
import app.opentv.data.model.SourceKind
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException

/**
 * Times the journeys that matter (ADR 0010, Slow Journeys): when each starts and ends, and how long the
 * network kept it waiting meanwhile, which the HTTP client marks through [network]. Settee's own time is
 * the total less that wait, so a slow Provider or connection never makes a journey count as slow. When
 * Settee's own time passes the journey's threshold, [slow] hears of it, once per journey per process.
 *
 * Starting, ending and the network marks are a clock read and a few array writes under a lock: nothing is
 * allocated until a journey has been slow. Knows nothing of Sentry: [CrashReports] decides what's sent.
 *
 * The network wait is the time any call was in flight, not only the journey's own calls: telling them
 * apart would mean tagging every call with its journey. So a journey that overlaps another's downloads,
 * or a live stream playing, counts that time as waiting too, which can only make it report less.
 */
class JourneyTimer(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val slow: (SlowJourney) -> Unit,
) {
    /**
     * The journeys timed, by the [tag] events name them with, each with its threshold for Settee's own
     * time: about 2–3× the stick's median in docs/PERFORMANCE.md. ADR 0010's table lists the same
     * numbers, and changes with them.
     */
    enum class Journey(val tag: String, val thresholdMillis: Long) {
        /** Process start to Home's first row drawn with its cards (stick median 2.2 s). */
        COLD_START("cold-start", 6_000),

        /** Movies selected to its first row drawn. Provisional: no median measured from selecting the tab. */
        OPEN_MOVIES("open-movies", 5_000),

        /** Shows selected to its first row drawn. Provisional, as for Movies. */
        OPEN_SHOWS("open-shows", 5_000),

        /** A refresh of every provider started to its catalogue written. Provisional: none measured. */
        CATALOGUE_REFRESH("catalogue-refresh", 60_000),

        /** Asked to play to the first frame drawn. Provisional: none measured. */
        PLAYBACK_START("playback-start", 4_000),
    }

    /**
     * A journey that was too slow: Settee's own time [ownMillis] and [networkMillis] waiting, between
     * [startedAt] and [endedAt] on the timer's clock, for a Source of [source]'s kind ([sourceOf]) or null
     * when it isn't one Source's.
     */
    class SlowJourney(
        val journey: Journey,
        val ownMillis: Long,
        val networkMillis: Long,
        val source: String?,
        val startedAt: Long,
        val endedAt: Long,
    )

    private val lock = Any()
    private val journeys = Journey.entries
    private val startedAt = LongArray(journeys.size) { NOT_RUNNING }
    private val waitedAtStart = LongArray(journeys.size)
    private val reported = BooleanArray(journeys.size)
    private var inFlight = 0
    private var inFlightSince = 0L
    private var waitedBefore = 0L

    /** Starts timing [journey], [at] a time on this timer's clock (now unless it began earlier); again if it's running. */
    fun start(journey: Journey, at: Long = now()) = synchronized(lock) {
        startedAt[journey.ordinal] = at
        waitedAtStart[journey.ordinal] = waited(now())
    }

    /** Stops timing [journey] without an end, as when the viewer leaves before it's done. */
    fun cancel(journey: Journey) = synchronized(lock) {
        startedAt[journey.ordinal] = NOT_RUNNING
    }

    /** Whether [journey] is being timed. */
    fun running(journey: Journey): Boolean = synchronized(lock) { startedAt[journey.ordinal] != NOT_RUNNING }

    /**
     * [journey] has reached its end, for a Source of [source]'s kind. Does nothing unless it's running.
     * Tells [slow] if Settee's own part of it passed the threshold, the first time this process.
     */
    fun end(journey: Journey, source: String? = null) {
        val slowOne = synchronized(lock) {
            val i = journey.ordinal
            val started = startedAt[i]
            if (started == NOT_RUNNING) return
            startedAt[i] = NOT_RUNNING
            val ended = now()
            val network = waited(ended) - waitedAtStart[i]
            val own = (ended - started - network).coerceAtLeast(0)
            if (own <= journey.thresholdMillis || reported[i]) return
            reported[i] = true
            SlowJourney(journey, own, network, source, started, ended)
        }
        slow(slowOne)
    }

    /** Marks the network waits: one call in flight or more is waiting. Given to every HTTP client. */
    val network: EventListener = object : EventListener() {
        override fun callStart(call: Call) = synchronized(lock) {
            if (inFlight++ == 0) inFlightSince = now()
        }

        override fun callEnd(call: Call) = ended()

        override fun callFailed(call: Call, ioe: IOException) = ended()
    }

    private fun ended() = synchronized(lock) {
        if (inFlight == 0) return
        if (--inFlight == 0) waitedBefore += now() - inFlightSince
    }

    /** How long, all told, a call has been in flight up to [at]. */
    private fun waited(at: Long): Long = waitedBefore + if (inFlight > 0) at - inFlightSince else 0

    companion object {
        private const val NOT_RUNNING = Long.MIN_VALUE

        /** What a Source of [kind] is called in a report: `xtream`, `m3u` or `stalker`. */
        fun sourceOf(kind: SourceKind?): String? = kind?.name?.lowercase()

        /** A Stremio add-on, which is no [SourceKind] but is where a stream can come from. */
        const val STREMIO = "stremio"
    }
}
