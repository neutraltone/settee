/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.opentv.core.ViewerError
import app.opentv.data.repo.MediaKey
import app.opentv.diagnostics.OpenKind
import app.opentv.diagnostics.PlaybackDiagnostics

/**
 * Times a film or episode from being asked for to its first frame, or its failure, for Diagnostics.
 *
 * A first frame isn't taken at its word: the last stream's can land after the next is asked for, and
 * even after the next is on the player, which would make a slow open look fast. So it only counts once
 * this stream is on the player and a decoder has started for it, as stopping the player releases the
 * last one (the same rule [FirstFrameWatch] keeps).
 */
@OptIn(UnstableApi::class)
internal class OpenTimer(private val diagnostics: PlaybackDiagnostics?) : AnalyticsListener {

    private class Pending(val kind: OpenKind, val startedAt: Long) {
        var onPlayer = false
        var decoding = false
    }

    private var pending: Pending? = null

    /** [key] was asked for. A restart of the controller's own isn't an open, so it starts nothing. */
    fun asked(key: MediaKey, restart: Boolean) {
        if (restart || diagnostics == null) return
        pending = kindOf(key)?.let { Pending(it, SystemClock.elapsedRealtime()) }
    }

    /** The stream asked for is on the player now; clearing the last one leaves no item, which isn't. */
    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) {
        if (mediaItem != null) pending?.onPlayer = true
    }

    /** The open asked for failed, with [error] on the screen. */
    fun failed(error: ViewerError) {
        val open = pending ?: return
        pending = null
        diagnostics?.opened(open.kind, SystemClock.elapsedRealtime() - open.startedAt, error)
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        pending?.takeIf { it.onPlayer }?.decoding = true
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        val open = pending?.takeIf { it.decoding } ?: return
        pending = null
        diagnostics?.opened(open.kind, SystemClock.elapsedRealtime() - open.startedAt, failure = null)
    }

    private fun kindOf(key: MediaKey): OpenKind? = when (key) {
        is MediaKey.Movie -> OpenKind.Film
        is MediaKey.Episode -> OpenKind.Episode
        is MediaKey.AddonStream -> OpenKind.AddonStream
        is MediaKey.Live, is MediaKey.Catchup, is MediaKey.Recording -> null
    }
}
