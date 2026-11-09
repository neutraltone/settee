/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Calls [onLate] when a video decoder has started and shown nothing after [FIRST_FRAME_LIMIT_MILLIS].
 * A software decoder can take a 10-bit 4K stream and never produce a frame: the buffer fills, loading
 * stops, and nothing ever reaches the player as an error. A full buffer doesn't end the wait early,
 * because a slow first frame on a stream that's merely starting looks the same at that moment.
 *
 * One per controller, unlike [DecoderLog.listener], which every player shares. A format arriving doesn't
 * start the clock, though it's the obvious sign of a stream starting: Media3 reports one with no surface
 * and no decoder, and nothing can show a frame then. Changing stream does start a decoder again, since
 * stopping the player releases the last one.
 */
@OptIn(UnstableApi::class)
internal class FirstFrameWatch(private val scope: CoroutineScope, private val onLate: (Format?) -> Unit) :
    AnalyticsListener {

    private var clock: Job? = null
    private var shown = false
    private var format: Format? = null

    /** Forgets the last stream, for the one about to start. */
    fun newStream() {
        clock?.cancel()
        clock = null
        shown = false
        format = null
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        this.format = format
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        if (shown || clock != null) return
        clock = scope.launch {
            delay(FIRST_FRAME_LIMIT_MILLIS)
            onLate(format)
        }
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        // With no decoder started, it's the last stream's frame arriving late, and says nothing of this one.
        val running = clock ?: return
        shown = true
        running.cancel()
    }

    private companion object {
        const val FIRST_FRAME_LIMIT_MILLIS = 15_000L
    }
}
