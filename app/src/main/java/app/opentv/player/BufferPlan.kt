/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * How much a player buffers, ahead and behind, for each kind of player. Every plan carries a byte
 * limit, and [loadControl] is the only place a plan becomes a load control, so no kind of player
 * can leave the memory limit out (#182).
 */
@OptIn(UnstableApi::class)
internal data class BufferPlan(
    val minMillis: Int,
    val maxMillis: Int,
    val forPlaybackMillis: Int,
    val afterRebufferMillis: Int,
    /** What's kept behind the playing position to rewind into; 0 keeps nothing. */
    val backBufferMillis: Int,
    val targetBytes: Int,
) {

    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(minMillis, maxMillis, forPlaybackMillis, afterRebufferMillis)
        // Size wins over time: a high-bitrate stream buffers fewer seconds rather than the app
        // running out of memory.
        .setTargetBufferBytes(targetBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .apply { if (backBufferMillis > 0) setBackBuffer(backBufferMillis, true) }
        .build()

    companion object {

        fun forPlayer(
            preview: Boolean = false,
            dvr: Boolean = false,
            liveRecording: Boolean = false,
            heapLimitBytes: Long = Runtime.getRuntime().maxMemory(),
        ): BufferPlan {
            val targetBytes = bufferBytesFor(heapLimitBytes)
            return when {
                preview -> BufferPlan(
                    PREVIEW_MIN_BUFFER_MILLIS,
                    PREVIEW_MAX_BUFFER_MILLIS,
                    PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS,
                    PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS,
                    backBufferMillis = 0,
                    targetBytes,
                )
                liveRecording -> BufferPlan(
                    MIN_BUFFER_MILLIS,
                    LIVE_REC_MAX_BUFFER_MILLIS,
                    LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS,
                    LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS,
                    backBufferMillis = 0,
                    targetBytes,
                )
                else -> BufferPlan(
                    MIN_BUFFER_MILLIS,
                    if (dvr) DVR_MAX_BUFFER_MILLIS else MAX_BUFFER_MILLIS,
                    BUFFER_FOR_PLAYBACK_MILLIS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS,
                    backBufferMillis = if (dvr) livePauseMillisFor(targetBytes) else 0,
                    targetBytes,
                )
            }
        }

        /**
         * How long live TV can be paused, and how far back it can be rewound, on this device. A
         * live stream arrives at its own pace, so a pause can last only as long as the memory
         * left after the rewind window holds. Media3 sets the rewind window's length once, in
         * time, when the player is built, before anyone knows the stream's bitrate. So live
         * pause splits the memory limit in half at a typical bitrate: rewind gets one half, and
         * a pause the other. A stream above that bitrate fills the buffer early, and
         * [BufferWatch] ends the pause then.
         */
        fun livePauseMillis(heapLimitBytes: Long = Runtime.getRuntime().maxMemory()): Int =
            livePauseMillisFor(bufferBytesFor(heapLimitBytes))

        private fun livePauseMillisFor(targetBytes: Int): Int =
            (targetBytes / 2L * MILLIS_PER_SECOND / TYPICAL_BYTES_PER_SECOND).toInt()
                .coerceAtMost(DVR_MAX_BUFFER_MILLIS)

        /**
         * The most a player buffers, in bytes, given the app's heap limit. The durations are set in
         * milliseconds, and left to itself Media3 caps them at about 144 MB. That's more than a Fire
         * TV Stick lets an app have, so a 24 Mbps stream ran out of memory before it ever played
         * (#180). A quarter of the heap leaves the rest for the decoders, the artwork cache and the
         * screen around the player. It never goes above Media3's own limit, so devices with plenty
         * of memory buffer as they did before.
         */
        fun bufferBytesFor(heapLimitBytes: Long): Int =
            (heapLimitBytes / HEAP_SHARE_FOR_BUFFER).coerceAtMost(MEDIA3_BUFFER_BYTES.toLong()).toInt()

        private const val HEAP_SHARE_FOR_BUFFER = 4
        private const val MEDIA3_BUFFER_BYTES =
            DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE

        /**
         * Larger than ExoPlayer's defaults. IPTV sources are much twitchier than a CDN, and a
         * deeper buffer is the difference between a momentary hiccup and a visible stall.
         */
        private const val MIN_BUFFER_MILLIS = 15_000
        private const val MAX_BUFFER_MILLIS = 60_000
        private const val BUFFER_FOR_PLAYBACK_MILLIS = 2_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MILLIS = 5_000

        /**
         * Live pause at most: two minutes to rewind into and two to pause for, on a device with
         * the memory for them. Deliberately bounded: this is memory the box has to find.
         */
        private const val DVR_MAX_BUFFER_MILLIS = 120_000

        /**
         * About 4.8 Mbps, a typical HD channel. Chosen so a device at Media3's own limit keeps
         * the full two minutes of live pause.
         */
        private const val TYPICAL_BYTES_PER_SECOND = 600_000L
        private const val MILLIS_PER_SECOND = 1_000L

        // Watch-while-recording: hold playback a cushion *behind* the file's growing edge rather than
        // right on it. IPTV sources arrive in bursts, so a shallow buffer drains to nothing between
        // bursts and the picture stalls every second or two — worst over SMB to a NAS, where every
        // read is a network round-trip. Waiting for ~8s of reservoir before playback (and rebuilding
        // it after any stall) rides straight over those gaps; because playback and the recorder both
        // advance at 1x, that cushion holds without ever catching the edge again.
        private const val LIVE_REC_MAX_BUFFER_MILLIS = 60_000
        private const val LIVE_REC_BUFFER_FOR_PLAYBACK_MILLIS = 8_000
        private const val LIVE_REC_BUFFER_AFTER_REBUFFER_MILLIS = 8_000

        // Guide-preview tuning: start fast and don't hoard buffer (the preview is muted and
        // low-stakes). Kept within DefaultLoadControl's constraints: min >= both playback
        // thresholds, max >= min.
        private const val PREVIEW_MIN_BUFFER_MILLIS = 5_000
        private const val PREVIEW_MAX_BUFFER_MILLIS = 15_000
        private const val PREVIEW_BUFFER_FOR_PLAYBACK_MILLIS = 1_000
        private const val PREVIEW_BUFFER_AFTER_REBUFFER_MILLIS = 1_500
    }
}
