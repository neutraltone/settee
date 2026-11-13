/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer

/**
 * What the decoders were given and what became of it, for diagnosing a stream on a device (#107,
 * #180). Everything is built from named fields, because [Format.toString], [Format.id],
 * [Format.label] and exception messages can all carry the stream's address, and with it the
 * credentials.
 */
@OptIn(UnstableApi::class)
object DecoderLog {

    /** Logs what a decoder failed on, when the failure reached the player. */
    fun logFailure(error: PlaybackException) {
        if (error.errorCode !in DECODER_FAILURES) return
        val failure = error as? ExoPlaybackException
        Log.w(
            TAG,
            "${error.errorCodeName}: ${namedFields(failure?.rendererFormat)} " +
                "renderer=${failure?.rendererName} decoder=${decoderNameOf(error.cause)}",
        )
    }

    /**
     * Logs each format a decoder is given, and each decoder as it starts or fails to. Fallback
     * recovers without an error reaching the player, so this is the only record of what a
     * fallback decoder was asked to play, and which one ended up playing it. The decoder's name
     * goes on its own line: this one listener serves every player, so it can't safely remember
     * which format went with which decoder.
     */
    val listener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = logFormat(format)

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = logFormat(format)

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = logStarted(decoderName)

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) = logStarted(decoderName)

        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) =
            logWontStart(videoCodecError)

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
            logWontStart(audioCodecError)
    }

    /**
     * Logs every audio and video track a stream offers, chosen or not, since a track nobody plays never
     * reaches a decoder. What [alreadyLogged] holds for this stream isn't logged again: tracks are
     * reported again whenever a subtitle or audio track changes.
     */
    fun logTracks(tracks: Tracks, decoders: DecoderList, alreadyLogged: MutableSet<String>) {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val supported = group.isTrackSupported(i, true) && !decoders.refuses(format)
                val track = "${namedFields(format)}${rates(format)} selected=${group.isTrackSelected(i)} " +
                    "supported=$supported"
                if (alreadyLogged.add(track)) Log.i(TAG, "Track: $track")
            }
        }
    }

    private fun logFormat(format: Format) {
        Log.i(TAG, "Format: ${namedFields(format)}${rates(format)}")
    }

    private fun rates(format: Format): String {
        // MPEG-TS from a panel rarely says either, and "-1" would read as a measurement.
        val bitrate = listOf(format.averageBitrate, format.peakBitrate).firstOrNull { it != Format.NO_VALUE }
        val frameRate = format.frameRate.takeIf { it != Format.NO_VALUE.toFloat() }
            ?.takeUnless { MimeTypes.isAudio(format.sampleMimeType) }
        return bitrate?.let { " bitrate=$it" }.orEmpty() + frameRate?.let { " frameRate=$it" }.orEmpty()
    }

    private fun logStarted(decoderName: String) {
        Log.i(TAG, "Decoder started: $decoderName")
    }

    private fun logWontStart(error: Exception) {
        decoderNameOf(error)?.let { Log.w(TAG, "Decoder wouldn't start: $it") }
    }

    private fun namedFields(format: Format?): String {
        val details = format?.takeIf { MimeTypes.isAudio(it.sampleMimeType) }
            ?.let { "channels=${it.channelCount} sampleRate=${it.sampleRate}" }
            ?: "size=${format?.width}x${format?.height} bitDepth=${format?.bitDepth}"
        return "${format?.sampleMimeType} codecs=${format?.codecs} $details"
    }

    private fun decoderNameOf(error: Throwable?): String? =
        (error as? MediaCodecRenderer.DecoderInitializationException)?.codecInfo?.name

    private const val TAG = "OpenTV"

    private val DECODER_FAILURES = PlaybackRecovery.UNPLAYABLE_FORMATS + PlaybackException.ERROR_CODE_DECODING_FAILED
}
