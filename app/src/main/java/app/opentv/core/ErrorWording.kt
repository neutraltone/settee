/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import androidx.annotation.StringRes
import app.opentv.R
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.ProviderException
import app.opentv.data.remote.ProviderException.Problem
import app.opentv.data.remote.TmdbCheck
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Turns a failure outside playback into what the viewer sees (docs/ERRORS.md). The sentence comes
 * from the exception's type, never its message: a message is written for logs, and an HTTP client's
 * often holds the request's URL, so an Xtream login or a portal's MAC with it.
 */
/** The "this TV" string on a television and the "this device" one elsewhere. */
@StringRes
fun hardware(onTelevision: Boolean, @StringRes tv: Int, @StringRes device: Int): Int = if (onTelevision) tv else device

/** A failure that came with an HTTP status, for the detail line. */
interface HttpStatus {
    val status: Int?
}

class ErrorWording(private val context: Context, private val onTelevision: Boolean) {
    // Looked up each time, as RefreshMessages does: changing language only recreates the activity.
    private val resources get() = LocaleUtils.wrap(context).resources

    // The app's own language decides 12 or 24 hours, as it does the words around it. Android's DateFormat
    // goes by the device's setting instead, which would say "12:00 AM" in a British sentence.
    private val timeFormat get() = java.time.format.DateTimeFormatter.ofLocalizedTime(
        java.time.format.FormatStyle.SHORT,
    )
        .withLocale(resources.configuration.locales[0])
        .withZone(java.time.ZoneId.systemDefault())

    /**
     * A provider's [error] in words, or [fallback] when its type says nothing a viewer could act on.
     * Only for the provider: "can't reach your provider" would be wrong about a NAS or a free guide.
     */
    fun ofProvider(error: Throwable, @StringRes fallback: Int): ViewerError {
        if (error is ProviderException) return ofProvider(error, fallback)
        return ViewerError(resources.getString(sentenceOf(error) ?: fallback), ViewerError.detailOf(codeOf(error)))
    }

    /** Anything else's [error]: always [sentence], with what went wrong on the detail line. */
    fun of(error: Throwable, @StringRes sentence: Int): ViewerError = ViewerError(
        resources.getString(sentence),
        ViewerError.detailOf(codeOf(error), (error as? HttpStatus)?.status?.let { "HTTP $it" }),
    )

    private fun ofProvider(error: ProviderException, @StringRes fallback: Int): ViewerError {
        val status = error.status
        if (status != null) return ofHttpStatus(status, fallback)
        val sentence = when (error.problem) {
            Problem.LOGIN_REJECTED -> R.string.error_login
            Problem.ACCOUNT_INACTIVE -> R.string.error_account_inactive
            Problem.BAD_ADDRESS -> R.string.error_bad_address
            Problem.DEVICE_REFUSED -> R.string.error_device_refused
            Problem.HTTP_STATUS, Problem.BAD_REPLY -> fallback
        }
        return ViewerError(resources.getString(sentence), codeName(error.problem.name))
    }

    /** A failure that's about the hardware: [tv]'s sentence on a television, [device]'s elsewhere. */
    fun ofHardware(@StringRes tv: Int, @StringRes device: Int, code: String? = null): ViewerError =
        of(hardware(onTelevision, tv, device), code)

    /**
     * The database wouldn't open at launch (#334): one left by a build Settee can't migrate from. The
     * app can't run without it and its own updater sits behind it, so the way out is outside the app.
     */
    fun ofDatabase(error: Throwable): ViewerError =
        ofHardware(R.string.error_database_tv, R.string.error_database_device, codeOf(error))

    /** A failure the app found itself, with no exception behind it. */
    fun of(@StringRes sentence: Int, code: String? = null): ViewerError =
        ViewerError(resources.getString(sentence), ViewerError.detailOf(code))

    /** A provider answering [status], with [fallback] for a status that says nothing a viewer could act on. */
    fun ofHttpStatus(status: Int, @StringRes fallback: Int): ViewerError = ViewerError(
        resources.getString(
            when (status) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> R.string.error_login
                TOO_MANY_REQUESTS -> R.string.error_http_429
                in SERVER_ERRORS -> R.string.error_http_5xx
                else -> fallback
            },
        ),
        ViewerError.detailOf("HTTP $status"),
    )

    /**
     * Why a TMDB Key wasn't saved, or null when TMDB accepted it. A wrong key and no answer are
     * worded apart, so a viewer whose network is down isn't sent off to fetch a new key.
     */
    fun ofTmdb(check: TmdbCheck): ViewerError? = when (check) {
        TmdbCheck.Accepted -> null
        TmdbCheck.Rejected -> ViewerError(
            resources.getString(R.string.error_tmdb_key_rejected),
            ViewerError.detailOf("KeyRejected", "HTTP 401"),
        )
        is TmdbCheck.Unreachable -> {
            val status = check.status
            if (status != null) {
                ViewerError(resources.getString(R.string.error_tmdb_trouble), ViewerError.detailOf("HTTP $status"))
            } else {
                val tv = R.string.error_tmdb_unreachable_tv
                ofHardware(tv, R.string.error_tmdb_unreachable_device, codeOf(check.cause))
            }
        }
    }

    /** What went wrong with OpenSubtitles: finding, downloading or signing in. */
    fun ofOpenSubtitles(error: Throwable): ViewerError = when (error) {
        is OpenSubtitlesFailure.NoResults -> of(R.string.error_opensubtitles_none, "NoResults")
        is OpenSubtitlesFailure.DownloadLimit -> {
            val resetsAt = error.resetsAt
            val sentence = when {
                !error.signedIn -> resources.getString(R.string.error_opensubtitles_limit_anonymous)
                resetsAt != null -> resources.getString(
                    R.string.error_opensubtitles_limit_account,
                    // In the app's language, as the sentence around it is, not the device's.
                    timeFormat.format(java.time.Instant.ofEpochMilli(resetsAt)),
                )
                else -> resources.getString(R.string.error_opensubtitles_limit_account_unknown)
            }
            ViewerError(sentence, ViewerError.detailOf("DownloadLimit", "HTTP 406"))
        }
        is OpenSubtitlesFailure.SignInRejected ->
            ViewerError(
                resources.getString(R.string.error_opensubtitles_sign_in),
                ViewerError.detailOf("SignInRejected", "HTTP 401"),
            )
        is OpenSubtitlesFailure.TokenRejected ->
            ofHardware(
                R.string.error_opensubtitles_token_tv,
                R.string.error_opensubtitles_token_device,
                "TokenRejected",
            )
        is OpenSubtitlesFailure.Unreachable -> {
            val status = error.status
            if (status != null) {
                ViewerError(
                    resources.getString(R.string.error_opensubtitles_trouble),
                    ViewerError.detailOf("HTTP $status"),
                )
            } else {
                val cause = error.cause ?: error
                ofHardware(
                    R.string.error_opensubtitles_unreachable_tv,
                    R.string.error_opensubtitles_unreachable_device,
                    codeOf(cause),
                )
            }
        }
        else -> ofHardware(
            R.string.error_opensubtitles_unreachable_tv,
            R.string.error_opensubtitles_unreachable_device,
            codeOf(error),
        )
    }

    @StringRes
    private fun sentenceOf(error: Throwable): Int? = networkSentenceOf(error, onTelevision)

    companion object {
        private const val TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = 500..599

        /** An exception's type as the detail line shows it: "UnknownHost" for UnknownHostException. */
        fun codeOf(error: Throwable): String = error.javaClass.simpleName.removeSuffix("Exception")

        /** "LOGIN_REJECTED" as "LoginRejected": a code, like an exception's type, not a phrase to translate. */
        fun codeName(name: String): String = name.split('_').joinToString("") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        /**
         * The sentence for a provider that couldn't be reached or stopped answering, or null for any
         * other failure. Playback and everything else share it, so both say the same thing.
         */
        @StringRes
        fun networkSentenceOf(error: Throwable?, onTelevision: Boolean): Int? = when (error) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                hardware(onTelevision, R.string.error_cant_reach_tv, R.string.error_cant_reach_device)
            // SocketTimeoutException is one of these.
            is InterruptedIOException -> R.string.error_timed_out
            else -> null
        }
    }
}
