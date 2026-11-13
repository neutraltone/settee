/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.media3.common.PlaybackException

/** What can be done about a failed stream: the viewer offered Retry, the player restarting it by itself. */
internal data class Recovery(val canRetry: Boolean, val canRestart: Boolean)

/**
 * Decides [Recovery] for [PlayerController]. [PlaybackErrors] says what went wrong; this says what's
 * worth trying again, so a stream that can't play shows why instead of a spinner.
 */
internal object PlaybackRecovery {

    /**
     * After [error], the [failuresInARow]th on this stream; [hadPlayed] is whether it reached playing
     * since it last loaded.
     */
    fun after(error: PlaybackException, failuresInARow: Int, hadPlayed: Boolean): Recovery {
        // Restarting can't change what this device can decode, and on a portal it would only mint
        // more links for a stream that will fail the same way.
        val canRetry = error.errorCode !in UNPLAYABLE_FORMATS && failuresInARow < MAX_AUTO_RESTARTS
        // Running out of memory took the Stick over a minute (#180), so a silent restart would hide
        // the message for another minute. Retry stays on offer: the memory may be free by the time
        // the viewer asks.
        //
        // A stream that won't parse from the start is usually the provider's HTML page, for an expired
        // account or a blocked channel, which a restart only fetches again (#255); Retry stays for
        // when the viewer has sorted the account out. One that played first and then broke is more
        // likely a bad segment, which a restart gets past.
        val hopeless = PlaybackErrors.isOutOfMemory(error) || (error.errorCode in PARSE_FAILURES && !hadPlayed)
        return Recovery(canRetry = canRetry, canRestart = canRetry && !hopeless)
    }

    /**
     * Two silent restarts: the count goes up before it's checked, so the third failure in a row
     * stops. The common case they cover is a provider dropping the connection when another device on
     * the account starts streaming.
     */
    private const val MAX_AUTO_RESTARTS = 3

    /**
     * Failures where this stream's format is the problem. A decoder failing partway through isn't
     * one: a single bad frame is worth a restart.
     */
    internal val UNPLAYABLE_FORMATS = setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    /** What arrived wasn't a stream the player could read. */
    private val PARSE_FAILURES = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
    )
}
