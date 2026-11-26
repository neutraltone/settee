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
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.RecordedOn
import app.opentv.isRunningOnTelevision
import app.opentv.player.torrent.TorrentFailedException
import app.opentv.player.torrent.TorrentFailure
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
     * [format] is the stream's, for the detail line, when the error doesn't name its own. [recordedOn] is
     * where a finished recording is kept, null for anything else. [stream] is what's known of the stream that
     * failed, so an add-on's is worded as the add-on's.
     */
    @Suppress("LongParameterList") // The last three are each known only for some streams, and default to nothing.
    fun describe(
        context: Context,
        error: PlaybackException,
        onTelevision: Boolean,
        format: Format? = null,
        recordedOn: RecordedOn? = null,
        stream: FailedStream = FailedStream(),
    ): ViewerError {
        torrentFailureOf(error)?.let { return torrentError(context, it) }
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return ViewerError(
            sentence = recordingSentenceOf(error, recordedOn)?.let(context::getString)
                ?: sentenceOf(context, error, onTelevision, stream),
            detail = ViewerError.detailOf(
                readableCodeName(error.errorCodeName),
                status?.let { "HTTP $it" },
                ((error as? ExoPlaybackException)?.rendererFormat ?: format)?.displayNameAndSize,
            ),
        )
    }

    private fun sentenceOf(
        context: Context,
        error: PlaybackException,
        onTelevision: Boolean,
        stream: FailedStream,
    ): String = when (
        val cause = error.cause
    ) {
        is HttpDataSource.InvalidResponseCodeException -> describeHttpStatus(context, cause.responseCode)
        else if ErrorWording.isNetworkFailure(cause) ->
            context.getString(ErrorWording.networkSentenceOf(cause, onTelevision)!!)
        // Checked before the error code: Media3 reports it as a source error with an IO code,
        // which would read as a network fault when the stream is simply too big for this device.
        else if isOutOfMemory(error) ->
            context.getString(
                hardware(onTelevision, R.string.error_out_of_memory_tv, R.string.error_out_of_memory_device),
            ) + " " +
                context.getString(R.string.playback_try_other_quality)

        else -> describeErrorCode(context, error, onTelevision, stream)
    }

    private fun describeErrorCode(
        context: Context,
        error: PlaybackException,
        onTelevision: Boolean,
        stream: FailedStream,
    ): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            context.getString(
                hardware(onTelevision, R.string.error_cant_reach_tv, R.string.error_cant_reach_device),
            )

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> context.getString(R.string.error_timed_out)

        // The player's data source checks no content type, so an expired or blocked account's
        // HTML page (sent with a 200) only fails when it's parsed, as a file in a container the
        // player can't read does. StreamPeek's look at the bytes tells them apart; one that
        // couldn't be looked at is taken for the web page it most often is (#462).
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            context.getString(stream.unreadableSentence)

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

    /** Why the player stopped a stream that raised no error. */
    enum class Stopped {
        /** Nothing played within [StartUpWatch]'s limit. */
        START_UP_LIMIT,

        /** A live stream came to an end, and restarting it hasn't helped (#472). */
        STREAM_ENDED,
    }

    /**
     * Why [stopped]. Nothing failed, so there's no code, status or format to show; the detail line names
     * what happened instead, as [Playable.Failure]'s do. [recordedOn] is where a finished recording is kept,
     * null for anything else.
     */
    fun describe(context: Context, stopped: Stopped, recordedOn: RecordedOn? = null): ViewerError = ViewerError(
        context.getString(
            when (stopped) {
                // A NAS that's off can take longer than the limit to refuse, once the retries add up.
                Stopped.START_UP_LIMIT ->
                    if (recordedOn == RecordedOn.NAS) R.string.sync_nas_failed else R.string.error_timed_out
                Stopped.STREAM_ENDED -> R.string.error_live_ended
            },
        ),
        ErrorWording.codeName(stopped.name),
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

    /**
     * Why [app.opentv.data.repo.PlaybackSources] had nothing to play. [cause] is [Playable.Failed]'s:
     * a portal that couldn't be reached is worded as the network failure it was, as a stream's is.
     * [onTelevision] is worked out from [context] unless a caller (or a test) already knows it.
     */
    fun describe(
        context: Context,
        failure: Playable.Failure,
        cause: Throwable? = null,
        onTelevision: Boolean = isRunningOnTelevision(context),
    ): ViewerError = ViewerError(
        context.getString(
            when (failure) {
                Playable.Failure.LINK_REFUSED -> R.string.playback_link_refused
                Playable.Failure.UNREACHABLE -> ErrorWording.networkSentenceOf(cause, onTelevision)
                    ?: hardware(onTelevision, R.string.error_cant_reach_tv, R.string.error_cant_reach_device)
                Playable.Failure.NO_CATCHUP -> R.string.playback_no_catchup
                Playable.Failure.GONE -> R.string.error_gone
                Playable.Failure.BAD_ADDRESS -> R.string.error_bad_address
                Playable.Failure.TORRENTS_OFF -> R.string.error_torrents_off
            },
        ),
        cause?.let(ErrorWording::codeOf) ?: ErrorWording.codeName(failure.name),
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

/**
 * What's known of a stream that failed, for [PlaybackErrors.describe]: what was playing ([key]), so an add-on's
 * stream is worded as the add-on's, and what [StreamPeek] found it started with ([start]), when [isUnreadable]
 * had it looked at.
 */
data class FailedStream(val key: MediaKey? = null, val start: StreamStart? = null)

/** Why a stream [isUnreadable]: a web page unless its bytes said otherwise, and the add-on's if one sent it. */
private val FailedStream.unreadableSentence: Int
    get() {
        val fromAddon = key is MediaKey.AddonStream
        return when {
            start == StreamStart.SOMETHING_ELSE ->
                if (fromAddon) R.string.error_addon_unplayable_file else R.string.error_unplayable_file
            fromAddon -> R.string.error_addon_web_page
            else -> R.string.error_web_page
        }
    }

/**
 * Whether [error] is the player failing to read what it was sent, which is either a web page or a file in a
 * container it can't play: [StreamPeek] tells which.
 */
fun isUnreadable(error: PlaybackException): Boolean = error.errorCode in UNREADABLE

/**
 * The title to pick another stream of, when [key] is an add-on's stream that [isUnreadable], or a torrent that
 * couldn't play; else null.
 */
fun anotherStreamOf(error: PlaybackException, key: MediaKey?): MediaKey.Title? =
    (key as? MediaKey.AddonStream)?.title?.takeIf { isUnreadable(error) || torrentFailureOf(error) != null }

/**
 * Why a torrent couldn't play (#670). The engine's limit on finding people does the start-up limit's job,
 * so its sentences say what it found: nobody, or people but no file list in time. The detail line names
 * the failure and what the engine said, such as how many people it found, never Media3's code, which for
 * every one of these would be the same unspecified input error.
 */
private fun torrentError(context: Context, failure: TorrentFailedException): ViewerError = ViewerError(
    context.getString(
        when (failure.reason) {
            TorrentFailure.NOBODY_SHARING -> R.string.error_torrent_nobody_sharing
            TorrentFailure.NOT_ENOUGH_SPACE -> R.string.error_torrent_no_space
            TorrentFailure.START_TIMED_OUT -> R.string.error_torrent_timed_out
            TorrentFailure.UNSUPPORTED_DEVICE -> R.string.error_torrent_unsupported
            TorrentFailure.ENGINE_ERROR -> R.string.error_torrent_failed
        },
    ),
    ViewerError.detailOf(ErrorWording.codeName(failure.reason.name), failure.detail),
)

/** Why a torrent couldn't play, however deep the loader and player wrapped it; null for anything else. */
fun torrentFailureOf(error: Throwable): TorrentFailedException? =
    generateSequence(error) { it.cause }.take(MAX_TORRENT_CAUSE_DEPTH).filterIsInstance<TorrentFailedException>()
        .firstOrNull()

private const val MAX_TORRENT_CAUSE_DEPTH = 10

private val UNREADABLE = setOf(
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
)

/**
 * Why a finished recording kept on [recordedOn] couldn't be read, worded for where it's kept; null for anything
 * else, which is worded as a provider's stream is.
 */
private fun recordingSentenceOf(error: PlaybackException, recordedOn: RecordedOn?): Int? = when {
    recordedOn == null -> null
    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> when (recordedOn) {
        RecordedOn.USB -> R.string.error_recording_usb_missing
        RecordedOn.NAS, RecordedOn.DEVICE -> R.string.error_recording_missing
    }
    // Memory is reported with an IO code too, and isn't the NAS's doing.
    PlaybackErrors.isOutOfMemory(error) -> null
    // smbj's exceptions reach the player as they are, so its network ones would read as the provider's and its
    // SMB refusals, wrapped by the loader, as "try again". Whichever it was, the NAS is the place to look.
    recordedOn == RecordedOn.NAS && error.errorCode in NAS_READ_ERRORS -> R.string.sync_nas_failed
    else -> null
}

/**
 * Media3's input and output errors, which it numbers 2xxx, but for the two only HTTP raises: a NAS can't, and
 * they keep their own wording.
 */
private val NAS_READ_ERRORS = (PlaybackException.ERROR_CODE_IO_UNSPECIFIED until 3000) - setOf(
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
)

/** "ERROR_CODE_IO_BAD_HTTP_STATUS" as "IO bad HTTP status", for the detail line. */
internal fun readableCodeName(name: String): String = name.removePrefix("ERROR_CODE_")
    .split('_')
    .joinToString(" ") { word -> if (word in CODE_ACRONYMS) word else word.lowercase(Locale.ROOT) }
    .replaceFirstChar { it.titlecase(Locale.ROOT) }

private val CODE_ACRONYMS = setOf("IO", "HTTP", "DRM", "URI", "TLS")
