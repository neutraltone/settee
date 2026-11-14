/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transform

/**
 * How often, at most, the stored guide is read again while a guide sync is writing. The sync writes
 * its programmes in batches of 500, and each batch re-runs every Room query on them, so a big feed
 * re-ran an open guide's query, and rebuilt its rows, hundreds of times, back to back, alongside the
 * XMLTV parser on a 1 GB Fire TV Stick (#257). Two seconds is a few batches' worth of parsing: long
 * enough that the parser keeps most of the CPU, short enough that a first download's listings
 * visibly fill in rather than appearing all at once at the end.
 */
internal const val GUIDE_SETTLE_MILLIS = 2_000L

/**
 * [read]'s listings as a guide sync rewrites them, read again only when this flow of changes says the
 * store changed: the first straight away, then the latest at most once every [settleMillis] (conflate
 * keeps only the newest change while the pause runs, so the last of a burst always gets a read), and
 * never the same listings twice in a row.
 *
 * Not a plain debounce: that would hold back the first listings too, slowing the guide's start-up
 * and its half-hour refresh, and show nothing new for the whole of a long first download. Equal
 * listings are dropped here, before anyone groups them, because since #253 every refresh rewrites
 * programmes that are mostly unchanged. And not a Room query's flow throttled afterwards, as it was:
 * conflating that flow still collected it, so Room ran the sorted query for every batch a sync wrote
 * and the throttle threw the answers away (#328). Throttling the change signal instead means the
 * query runs only for listings that are sent.
 */
internal fun <T> Flow<*>.settledReads(
    settleMillis: Long = GUIDE_SETTLE_MILLIS,
    read: suspend () -> List<T>,
): Flow<List<T>> = conflate()
    .transform {
        emit(read())
        delay(settleMillis)
    }
    .distinctUntilChanged()
