/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import app.opentv.R
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.core.hardware
import app.opentv.data.repo.Playable
import java.util.Locale

/**
 * Turns a [PlaybackException] into something a person can act on: a sentence from strings.xml, and
 * the code, status and format for the detail line under it. Every mapping is listed in
 * [docs/ERRORS.md](../../../../../../../docs/ERRORS.md); change both together.
 *
 * "ERROR_CODE_IO_BAD_HTTP_STATUS" tells a user nothing. Worse, the habit of showing a raw
 * error code on screen is what led one player to *remove* its 403 display entirely as a
 * "fix" — hiding the symptom while the stream still failed. The right answer is to say what
 * probably went wrong and what to try, with the code kept, quietly, on the detail line.
 */
@OptIn(UnstableApi::class)
object PlaybackErrors {

    /**
     * The sentence and detail line for [error]. [onTelevision] picks "this TV" over "this device";
     * [format] is the stream's, for the detail line, when the error doesn't name its own.
     */
    fun describe(
        context: Context,
        error: PlaybackException,
        onTelevision: Boolean,
        format: Format? = null,
    ): ViewerError {
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return ViewerError(
            sentence = sentenceOf(context, error, onTelevision),
            detail = ViewerError.detailOf(
                readableCodeName(error.errorCodeName),
                status?.let { "HTTP $it" },
                ((error as? ExoPlaybackException)?.rendererFormat ?: format)?.displayNameAndSize,
            ),
        )
    }

    private fun sentenceOf(context: Context, error: PlaybackException, onTelevision: Boolean): String = when (
        val cause = error.cause
    ) {
        is HttpDataSource.InvalidResponseCodeException -> describeHttpStatus(context, cause.responseCode)
        else if ErrorWording.networkSentenceOf(cause, onTelevision) != null ->
            context.getString(ErrorWording.networkSentenceOf(cause, onTelevision)!!)
        // Checked before the error code: Media3 reports it as a source error with an IO code,
        // which would read as a network fault when the stream is simply too big for this device.
        else if isOutOfMemory(error) ->
            context.getString(
                hardware(onTelevision, R.string.error_out_of_memory_tv, R.string.error_out_of_memory_device),
            ) + " " +
                context.getString(R.string.playback_try_other_quality)

        else -> describeErrorCode(context, error, onTelevision)
    }

    private fun describeErrorCode(context: Context, error: PlaybackException, onTelevision: Boolean): String =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                context.getString(
                    hardware(onTelevision, R.string.error_cant_reach_tv, R.string.error_cant_reach_device),
                )

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> context.getString(R.string.error_timed_out)

            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> context.getString(R.string.error_web_page)

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            ->
                context.getString(R.string.error_damaged)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            ->
                describeUnplayable(context, (error as? ExoPlaybackException)?.rendererFormat, onTelevision)

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            ->
                describeUnplayable(
                    context,
                    (error as? ExoPlaybackException)?.rendererFormat,
                    onTelevision,
                    suggestQuality = true,
                )

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> context.getString(R.string.error_behind_live)

            else -> context.getString(R.string.error_anything_else)
        }

    /** Why a video that [DecoderList.refuses] won't play: no other quality or Retry is on offer here. */
    fun tooBig(context: Context, format: Format, onTelevision: Boolean): ViewerError = ViewerError(
        context.getString(hardware(onTelevision, R.string.error_too_big_tv, R.string.error_too_big_device)),
        format.displayNameAndSize,
    )

    /** Why a video whose decoder started and showed nothing has stopped; [format] is null if it never said. */
    fun noFirstFrame(context: Context, format: Format?, onTelevision: Boolean): ViewerError = ViewerError(
        context.getString(
            hardware(onTelevision, R.string.error_no_first_frame_tv, R.string.error_no_first_frame_device),
        ),
        format?.displayNameAndSize.orEmpty(),
    )

    /** Whether the app ran out of memory, however deep the loader and player wrapped it. */
    fun isOutOfMemory(error: PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is OutOfMemoryError }

    private fun describeUnplayable(
        context: Context,
        format: Format?,
        onTelevision: Boolean,
        suggestQuality: Boolean = false,
    ): String {
        val mimeType = format?.sampleMimeType
        val sentence = context.getString(
            when {
                mimeType == null -> hardware(onTelevision, R.string.error_format_tv, R.string.error_format_device)
                MimeTypes.isAudio(mimeType) ->
                    hardware(onTelevision, R.string.error_audio_format_tv, R.string.error_audio_format_device)
                MimeTypes.isVideo(mimeType) ->
                    hardware(onTelevision, R.string.error_video_format_tv, R.string.error_video_format_device)
                else -> hardware(onTelevision, R.string.error_format_tv, R.string.error_format_device)
            },
        )
        // Another quality is usually another video encoding; audio tends to be the same across them.
        return if (suggestQuality && mimeType != null && MimeTypes.isVideo(mimeType)) {
            "$sentence ${context.getString(R.string.playback_try_other_quality)}"
        } else {
            sentence
        }
    }

    /** Why [app.opentv.data.repo.PlaybackSources] had nothing to play. */
    fun describe(context: Context, failure: Playable.Failure): ViewerError = ViewerError(
        context.getString(
            when (failure) {
                Playable.Failure.LINK_REFUSED -> R.string.playback_link_refused
                Playable.Failure.NO_CATCHUP -> R.string.playback_no_catchup
                Playable.Failure.GONE -> R.string.error_gone
            },
        ),
        ErrorWording.codeName(failure.name),
    )

    fun describeHttpStatus(context: Context, code: Int): String = when (code) {
        401 -> context.getString(R.string.error_http_401)
        403 -> context.getString(R.string.error_http_403)
        404 -> context.getString(R.string.error_gone)
        // 405 usually means Xtream versus playlist is set wrong, but that's setup advice, so it stays in
        // provider setup rather than in a sentence the viewer reads mid-film.
        429 -> context.getString(R.string.error_http_429)
        451 -> context.getString(R.string.error_http_451)
        in 500..599 -> context.getString(R.string.error_http_5xx)
        else -> context.getString(R.string.error_anything_else)
    }

    /** Far deeper than Media3 wraps anything, and stops a cause that loops back on itself. */
    private const val MAX_CAUSE_DEPTH = 10
}

/** "ERROR_CODE_IO_BAD_HTTP_STATUS" as "IO bad HTTP status", for the detail line. */
internal fun readableCodeName(name: String): String = name.removePrefix("ERROR_CODE_")
    .split('_')
    .joinToString(" ") { word -> if (word in CODE_ACRONYMS) word else word.lowercase(Locale.ROOT) }
    .replaceFirstChar { it.titlecase(Locale.ROOT) }

private val CODE_ACRONYMS = setOf("IO", "HTTP", "DRM", "URI", "TLS")
