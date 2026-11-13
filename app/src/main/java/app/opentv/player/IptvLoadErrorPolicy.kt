/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Retry policy tuned for IPTV rather than for CDNs.
 *
 * A provider under load returns 403, 429 or 500 for a few seconds and then serves the
 * stream perfectly well. ExoPlayer's default is to give up almost immediately on those,
 * which surfaces to the user as a channel that "doesn't work" but plays fine on the
 * second try. We retry them, and reads that fail or drop, with backoff instead. 401, 404
 * and 410 are not retried — those genuinely will not fix themselves.
 *
 * Whether anything else is retried is ExoPlayer's call, because its "don't retry" is deliberate:
 * a provider answering an expired account with HTTP 200 and an HTML page fails to parse, and no
 * number of retries turns that page into video. Backing those off too kept the spinner up for
 * about 15 seconds per attempt before the viewer saw why (#255). Asking it whether, rather than
 * listing exception types here, keeps the NAS and recordings, whose sources aren't HTTP, on the
 * same backoff as a provider's stream.
 */
@OptIn(UnstableApi::class)
internal class IptvLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val statusCode = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when {
            statusCode == null -> if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) {
                C.TIME_UNSET
            } else {
                backoffFor(info.errorCount)
            }
            statusCode in NOT_RETRIED -> C.TIME_UNSET
            statusCode in RETRIED -> backoffFor(info.errorCount)
            else -> super.getRetryDelayMsFor(info)
        }
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = MAX_LOAD_RETRIES

    private fun backoffFor(errorCount: Int): Long = if (errorCount > MAX_LOAD_RETRIES) {
        C.TIME_UNSET
    } else {
        minOf(INITIAL_BACKOFF_MILLIS * (1L shl (errorCount - 1)), MAX_BACKOFF_MILLIS)
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8_000L
        const val MAX_LOAD_RETRIES = 5
        val NOT_RETRIED = setOf(401, 404, 410)
        val RETRIED = setOf(403, 405, 429, 500, 502, 503, 504)
    }
}
