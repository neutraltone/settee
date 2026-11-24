/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.os.SystemClock
import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener

/**
 * Times the journeys that matter (ADR 0010, Slow Journeys): when each starts and ends, and how long the
 * network kept it waiting meanwhile, which the HTTP clients mark through [network] and [streaming].
 * Settee's own time is the total less that wait, so a slow Provider or connection never makes a journey
 * count as slow. When Settee's own time passes the journey's threshold, [slow] hears of it, once per
 * journey per process.
 *
 * A journey's wait is only the calls that started while it ran: one already going when it started, such
 * as a guide download or a recording, isn't the journey's. And a stream, which the players and
 * recordings read through [streaming] and which can stay open for hours, is only the wait of the start
 * of playback that asked for it: a Live Preview playing on Home never hides a slow Movies.
 *
 * Starting, ending and the network marks are a clock read and a few array writes under a lock. Nothing
 * is allocated until a journey has been slow, but for the one small listener per HTTP call that
 * remembers when the call started (OkHttp makes one per call however it's given). Knows nothing of
 * Sentry: [CrashReports] decides what's sent.
 */
class JourneyTimer(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val slow: (SlowJourney) -> Unit,
) {
    /**
     * The journeys timed, by the [tag] events name them with, each with its threshold for Settee's own
     * time: about 2–3× the stick's median in docs/PERFORMANCE.md, where the stick has one. The ones
     * marked provisional are cautious guesses until the stick measures them (`tools/perf/stick_baseline.py`,
     * which measures only cold start so far: the others need it to learn to), when each becomes 2–3× that
     * median. ADR 0010's table lists the same numbers, and changes with them. [waitsOnStream]: whether a
     * stream's calls are this journey's wait.
     */
    enum class Journey(val tag: String, val thresholdMillis: Long, val waitsOnStream: Boolean = false) {
        /** Process start to Home's first row drawn with its cards (stick median 2.2 s, measured). */
        COLD_START("cold-start", thresholdMillis = 6_000),

        /** Movies selected to its first row drawn. Provisional: no median measured from selecting the tab. */
        OPEN_MOVIES("open-movies", thresholdMillis = 5_000),

        /** Shows selected to its first row drawn. Provisional, as for Movies. */
        OPEN_SHOWS("open-shows", thresholdMillis = 5_000),

        /** A refresh of every provider started to its catalogue written. Provisional: none measured. */
        CATALOGUE_REFRESH("catalogue-refresh", thresholdMillis = 60_000),

        /** Asked to play to the first frame drawn, which waits on the stream. Provisional: none measured. */
        PLAYBACK_START("playback-start", thresholdMillis = 4_000, waitsOnStream = true),
    }

    /**
     * A journey that was too slow: Settee's own time [ownMillis] and [networkMillis] waiting, between
     * [startedAt] and [endedAt] on the timer's clock, for a Source of [source]'s kind, or null when it
     * isn't one Source's. Reported as it is, with no error at all.
     */
    class SlowJourney(
        val journey: Journey,
        val ownMillis: Long,
        val networkMillis: Long,
        val source: ReportedSource?,
        val startedAt: Long,
        val endedAt: Long,
    ) : HandledReport

    private val lock = Any()
    private val journeys = Journey.entries
    private val startedAt = LongArray(journeys.size) { NOT_RUNNING }
    private val reported = BooleanArray(journeys.size)

    // Each start and each call gets the next number, so a call can tell whether it started after a
    // journey did, even within one tick of the clock, and after the journey's latest start.
    private var counter = 0L
    private val startedAs = LongArray(journeys.size)

    // Each journey's own calls in flight, since when, and how long they kept it waiting before that.
    private val inFlight = IntArray(journeys.size)
    private val inFlightSince = LongArray(journeys.size)
    private val waited = LongArray(journeys.size)

    /** Starts timing [journey], again if it's running, [at] a time on this timer's clock: now, unless earlier. */
    fun start(journey: Journey, at: Long = now()) = synchronized(lock) {
        val i = journey.ordinal
        startedAt[i] = at
        startedAs[i] = ++counter
        inFlight[i] = 0
        waited[i] = 0
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
    fun end(journey: Journey, source: ReportedSource? = null) {
        val slowOne = synchronized(lock) {
            val i = journey.ordinal
            val started = startedAt[i]
            if (started == NOT_RUNNING) return
            startedAt[i] = NOT_RUNNING
            val ended = now()
            val network = waited[i] + if (inFlight[i] > 0) ended - inFlightSince[i] else 0
            val own = (ended - started - network).coerceAtLeast(0)
            if (own <= journey.thresholdMillis || reported[i]) return
            reported[i] = true
            SlowJourney(journey, own, network, source, started, ended)
        }
        slow(slowOne)
    }

    /** Marks the network waits of every call but a stream's. Given to every HTTP client. */
    val network: EventListener.Factory = EventListener.Factory { CallWait(streaming = false) }

    /** Marks a stream's calls, the players' and recordings', which only a start of playback waits on. */
    val streaming: EventListener.Factory = EventListener.Factory { CallWait(streaming = true) }

    private fun waitsOn(i: Int, streaming: Boolean) =
        startedAt[i] != NOT_RUNNING && (!streaming || journeys[i].waitsOnStream)

    /** One call: the journeys running when it started are waiting on it until it ends. */
    private inner class CallWait(private val streaming: Boolean) : EventListener() {
        private var startedAs = NOT_STARTED

        override fun callStart(call: Call) = synchronized(lock) {
            startedAs = ++counter
            val at = now()
            for (i in journeys.indices) {
                if (waitsOn(i, streaming) && inFlight[i]++ == 0) inFlightSince[i] = at
            }
        }

        override fun callEnd(call: Call) = ended()

        override fun callFailed(call: Call, ioe: IOException) = ended()

        private fun ended() = synchronized(lock) {
            if (startedAs == NOT_STARTED) return
            val at = now()
            for (i in journeys.indices) {
                // Only a journey it was counted for: still the same run, started before this call.
                val counted = waitsOn(i, streaming) && this@JourneyTimer.startedAs[i] < startedAs && inFlight[i] > 0
                if (counted && --inFlight[i] == 0) waited[i] += at - inFlightSince[i]
            }
            startedAs = NOT_STARTED
        }
    }

    companion object {
        private const val NOT_RUNNING = Long.MIN_VALUE
        private const val NOT_STARTED = Long.MIN_VALUE
    }
}
