/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Programme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/**
 * How often, at most, an open guide rebuilds while a guide sync is writing. The sync writes its
 * programmes in batches of 500, each re-running the guide's window query, so a big feed re-fired it
 * hundreds of times, back to back, alongside the XMLTV parser on a 1 GB Fire TV Stick. Two seconds is
 * a few batches' worth of parsing: long enough that the parser keeps most of the CPU, short enough
 * that a first download's listings visibly fill in rather than appearing all at once at the end.
 */
internal const val GUIDE_SETTLE_MILLIS = 2_000L

/**
 * The programmes in each guide window, grouped by EPG channel id: the expensive step the guide's rows
 * are built from. A new window (opening the guide, the next half hour, another day) shows its first
 * listings at once; after that, rewrites are taken at most once every [settleMillis], always ending
 * on the latest, and an index equal to the last one isn't sent again.
 *
 * A plain debounce was rejected because it would hold back the first listings too, slowing start-up
 * and the half-hour refresh, and would show nothing new for the whole of a long first download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Pair<Long, Long>>.guideIndex(
    programmesIn: (fromUtcMillis: Long, toUtcMillis: Long) -> Flow<List<Programme>>,
    settleMillis: Long = GUIDE_SETTLE_MILLIS,
): Flow<Map<String, List<Programme>>> = flatMapLatest { (from, to) ->
    // Throttled inside flatMapLatest, so a new window starts afresh rather than waiting out
    // the old window's pause.
    programmesIn(from, to).atMostEvery(settleMillis).map { programmes -> programmes.groupBy { it.epgChannelId } }
}.distinctUntilChanged()

/**
 * Sends the first value straight away, then the latest at most once every [periodMillis]: conflate
 * keeps only the newest value while the pause runs, so the last of a burst is always sent.
 */
private fun <T> Flow<T>.atMostEvery(periodMillis: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMillis)
}
