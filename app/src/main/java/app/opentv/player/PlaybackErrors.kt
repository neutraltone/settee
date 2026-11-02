/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import app.opentv.R
import app.opentv.data.repo.Playable
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns a [PlaybackException] into something a person can act on.
 *
 * "ERROR_CODE_IO_BAD_HTTP_STATUS" tells a user nothing. Worse, the habit of showing a raw
 * error code on screen is what led one player to *remove* its 403 display entirely as a
 * "fix" — hiding the symptom while the stream still failed. The right answer is to say what
 * probably went wrong and what to try.
 */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    fun describe(context: Context, error: PlaybackException): String = when (val cause = error.cause) {
        is HttpDataSource.InvalidResponseCodeException -> describeHttpStatus(cause.responseCode)
        is UnknownHostException -> "Can't reach the server. Check this device is online and the address is right."
        is SocketTimeoutException -> "The server stopped responding. It may be overloaded — try again in a moment."
        else -> describeErrorCode(context, error)
    }

    private fun describeErrorCode(context: Context, error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        ->
            "Lost connection to the server."

        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "The server sent a web page instead of a stream. The link may have expired."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        ->
            "This stream is malformed and can't be played."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        ->
            describeUnplayable(context, (error as? ExoPlaybackException)?.rendererFormat)

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        ->
            describeUnplayable(context, (error as? ExoPlaybackException)?.rendererFormat, suggestQuality = true)

        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            "Playback fell too far behind the live edge. Reloading."

        else -> "Playback failed (${error.errorCodeName})."
    }

    /**
     * Whether this stream's format is the problem, so trying it again can't help. A decoder failing
     * partway through isn't counted: one bad frame in a stream is worth a restart.
     */
    fun isUnplayableFormat(error: PlaybackException): Boolean = error.errorCode in UNPLAYABLE_FORMATS

    /**
     * Logs what a decoder failed on. Built from named fields rather than the exception or
     * [Format.toString], either of which can carry the stream's address, and with it the credentials.
     */
    fun logDecoderFailure(error: PlaybackException) {
        if (error.errorCode !in DECODER_FAILURES) return
        val failure = error as? ExoPlaybackException
        val format = failure?.rendererFormat
        val details = format?.takeIf { MimeTypes.isAudio(it.sampleMimeType) }
            ?.let { "channels=${it.channelCount} sampleRate=${it.sampleRate}" }
            ?: "size=${format?.width}x${format?.height} bitDepth=${format?.bitDepth}"
        Log.w(
            TAG,
            "${error.errorCodeName}: ${format?.sampleMimeType} codecs=${format?.codecs} $details " +
                "renderer=${failure?.rendererName} decoder=${decoderNameOf(error.cause)}",
        )
    }

    /**
     * Logs each decoder as it starts or fails to. Fallback recovers without an error reaching the
     * player, so this is the only record of which decoder ended up playing.
     */
    val decoderLog = object : AnalyticsListener {
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

    private fun logStarted(decoderName: String) {
        Log.i(TAG, "Decoder started: $decoderName")
    }

    private fun logWontStart(error: Exception) {
        decoderNameOf(error)?.let { Log.w(TAG, "Decoder wouldn't start: $it") }
    }

    private fun decoderNameOf(error: Throwable?): String? =
        (error as? MediaCodecRenderer.DecoderInitializationException)?.codecInfo?.name

    private fun describeUnplayable(context: Context, format: Format?, suggestQuality: Boolean = false): String {
        val mimeType = format?.sampleMimeType ?: return context.getString(R.string.playback_format_unsupported)
        val sentence = context.getString(
            when {
                MimeTypes.isAudio(mimeType) -> R.string.playback_audio_format_unsupported
                MimeTypes.isVideo(mimeType) -> R.string.playback_video_format_unsupported
                else -> R.string.playback_other_format_unsupported
            },
            format.displayName,
        )
        // Another quality is usually another video encoding; audio tends to be the same across them.
        return if (suggestQuality && MimeTypes.isVideo(mimeType)) {
            "$sentence ${context.getString(R.string.playback_try_other_quality)}"
        } else {
            sentence
        }
    }

    /** Why [app.opentv.data.repo.PlaybackSources] had nothing to play, as a string resource. */
    @StringRes
    fun messageOf(failure: Playable.Failure): Int = when (failure) {
        Playable.Failure.LINK_REFUSED -> R.string.playback_link_refused
        Playable.Failure.NO_CATCHUP -> R.string.playback_no_catchup
        Playable.Failure.GONE -> R.string.playback_gone
    }

    fun describeHttpStatus(code: Int): String = when (code) {
        401 -> "The server rejected these credentials. Check the username and password."
        403 ->
            "The server refused this stream (403). Often this means the account is already " +
                "streaming on another device, or the provider is blocking this app's User-Agent — " +
                "you can change it in the source's advanced settings."
        404 -> "That channel no longer exists on the server. Refresh the channel list."
        405 ->
            "The server rejected the request (405). The stream address looks wrong for this " +
                "provider — try switching the source between Xtream and playlist mode."
        429 -> "Too many requests. The provider is rate-limiting this device."
        451 -> "Blocked for legal reasons in this region."
        in 500..599 -> "The provider's server is having problems ($code)."
        else -> "The server returned HTTP $code."
    }

    private const val TAG = "OpenTV"

    private val UNPLAYABLE_FORMATS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    private val DECODER_FAILURES = UNPLAYABLE_FORMATS + PlaybackException.ERROR_CODE_DECODING_FAILED
}
