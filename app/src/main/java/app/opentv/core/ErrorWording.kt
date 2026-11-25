/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.opentv.R
import app.opentv.data.parser.NotAGuideException
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.GuideAddressException
import app.opentv.data.remote.GuideDownloadException
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.ProviderException
import app.opentv.data.remote.ProviderException.Problem
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

/** How far the look for a release that fixes an unreadable database has got (#334). */
enum class UpdateSearch { CHECKING, FOUND, NONE, COULD_NOT_CHECK }

/** A failure that came with an HTTP status, for the detail line. */
interface HttpStatus {
    val status: Int?
}

// The one place failures outside playback are worded: each function is a kind of failure, so it
// grows with them rather than splitting the sentences across classes.
@Suppress("TooManyFunctions")
class ErrorWording(private val context: Context, private val onTelevision: Boolean) {
    /** What Test connection says when it passes, in the same language as its failures. */
    val tests = TestOutcomeWording { resources }

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
        // A provider that couldn't be reached at all, as a Stalker portal's client reports it: the
        // network failure it carries is what to word (#468).
        val failure = (error as? ProviderException)?.takeIf { it.problem == Problem.BAD_REPLY }?.cause
            ?.takeIf { networkSentenceOf(it, onTelevision) != null }
            ?: error
        if (failure is ProviderException) return ofProvider(failure, fallback)
        val sentence = networkSentenceOf(failure, onTelevision) ?: fallback
        return ViewerError(resources.getString(sentence), ViewerError.detailOf(codeOf(failure)))
    }

    /** Anything else's [error]: always [sentence], with what went wrong on the detail line. */
    fun of(error: Throwable, @StringRes sentence: Int): ViewerError = ViewerError(
        resources.getString(sentence),
        ViewerError.detailOf(codeOf(error), (error as? HttpStatus)?.status?.let { "HTTP $it" }),
    )

    /**
     * A guide's [error] in words. The provider's own guide ([fromProvider]) is worded as the provider
     * is, so a login it turns down says so; any other guide has no provider to blame.
     */
    fun ofGuide(error: Throwable, fromProvider: Boolean): ViewerError = when {
        error is NotAGuideException || error is GuideAddressException -> of(error, R.string.error_not_a_guide)
        fromProvider -> ofProvider(error, R.string.error_guide)
        // Only the status: the exception's type says nothing the status doesn't.
        error is GuideDownloadException -> ViewerError(
            resources.getString(if (error.status in NOT_A_GUIDE) R.string.error_not_a_guide else R.string.error_guide),
            ViewerError.detailOf("HTTP ${error.status}"),
        )
        else -> of(error, R.string.error_guide)
    }

    private fun ofProvider(error: ProviderException, @StringRes fallback: Int): ViewerError {
        val status = error.status
        // Only a bare status is worded by its number: a 404 where an Xtream login should be keeps its
        // NOT_A_PANEL sentence, since the address is what's wrong, not a status to wait out.
        if (status != null && error.problem == Problem.HTTP_STATUS) return ofHttpStatus(status, fallback)
        val sentence = when (error.problem) {
            Problem.LOGIN_REJECTED -> R.string.error_login
            Problem.ACCOUNT_INACTIVE -> R.string.error_account_inactive
            Problem.BAD_ADDRESS -> R.string.error_bad_address
            Problem.DEVICE_REFUSED -> R.string.error_device_refused
            Problem.NOT_A_PLAYLIST -> R.string.error_playlist_empty
            Problem.NOT_A_PANEL -> R.string.error_not_a_panel
            Problem.HTTP_STATUS, Problem.BAD_REPLY -> fallback
        }
        return ViewerError(
            resources.getString(sentence),
            ViewerError.detailOf(codeName(error.problem.name), status?.let { "HTTP $it" }),
        )
    }

    /** A failure that's about the hardware: [tv]'s sentence on a television, [device]'s elsewhere. */
    fun ofHardware(@StringRes tv: Int, @StringRes device: Int, code: String? = null): ViewerError =
        of(hardware(onTelevision, tv, device), code)

    /**
     * The database wouldn't open at launch (#334). The way out depends on [search]: a release that
     * fixes it, or clearing the app's data, which costs the viewer everything, so it says so.
     */
    fun ofDatabase(error: Throwable, search: UpdateSearch): ViewerError {
        val (tv, device) = when (search) {
            UpdateSearch.CHECKING -> R.string.error_database_checking_tv to R.string.error_database_checking_device
            UpdateSearch.FOUND -> R.string.error_database_update_tv to R.string.error_database_update_device
            UpdateSearch.NONE -> R.string.error_database_no_update_tv to R.string.error_database_no_update_device
            UpdateSearch.COULD_NOT_CHECK -> R.string.error_database_offline_tv to R.string.error_database_offline_device
        }
        return ofHardware(tv, device, codeOf(error))
    }

    /** A failure the app found itself, with no exception behind it. */
    fun of(@StringRes sentence: Int, code: String? = null): ViewerError =
        ViewerError(resources.getString(sentence), ViewerError.detailOf(code))

    /** A failure the app found itself, about [count] things, worded with [sentence]'s plural for [count]. */
    fun ofCount(@PluralsRes sentence: Int, count: Int, code: String? = null): ViewerError =
        ViewerError(resources.getQuantityString(sentence, count, count), ViewerError.detailOf(code))

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
     * Why a Stremio add-on couldn't be used, in its own words rather than [ofProvider]'s: an add-on
     * isn't "your provider", and the viewer pasted its address themselves.
     */
    fun ofAddon(failure: AddonFailure): ViewerError = when (failure) {
        is AddonFailure.NotAnAddon -> ofHardware(
            R.string.error_not_an_addon_tv,
            R.string.error_not_an_addon_device,
            ViewerError.detailOf("NotAnAddon", failure.status?.let { "HTTP $it" }),
        )
        is AddonFailure.Http -> of(R.string.error_addon_trouble, "HTTP ${failure.status}")
        is AddonFailure.NoLookup ->
            ofHardware(
                R.string.error_addon_lookup_tv,
                R.string.error_addon_lookup_device,
                codeOf(failure.cause ?: failure),
            )
        is AddonFailure.Unreachable -> {
            val cause = failure.cause ?: failure
            // SocketTimeoutException is one of these.
            if (cause is InterruptedIOException) {
                of(R.string.error_addon_timed_out, codeOf(cause))
            } else {
                ofHardware(
                    R.string.error_addon_cant_reach_tv,
                    R.string.error_addon_cant_reach_device,
                    codeOf(cause),
                )
            }
        }
    }

    /** What went wrong with OpenSubtitles: finding, downloading or signing in. */
    fun ofOpenSubtitles(error: Throwable): ViewerError = when (error) {
        is OpenSubtitlesFailure.NoResults -> of(R.string.error_opensubtitles_none, "NoResults")
        is OpenSubtitlesFailure.DownloadLimit -> ofOpenSubtitlesLimit(error, resources, timeFormat)
        is OpenSubtitlesFailure.SignInRejected ->
            ViewerError(
                resources.getString(R.string.error_opensubtitles_sign_in),
                ViewerError.detailOf("SignInRejected", "HTTP 401"),
            )
        is OpenSubtitlesFailure.KeyRejected ->
            ViewerError(
                resources.getString(R.string.error_opensubtitles_key_rejected),
                ViewerError.detailOf("KeyRejected", "HTTP ${error.status}"),
            )
        is OpenSubtitlesFailure.TokenRejected ->
            ofHardware(
                R.string.error_opensubtitles_token_tv,
                R.string.error_opensubtitles_token_device,
                "TokenRejected",
            )
        is OpenSubtitlesFailure.NotSaved ->
            ofHardware(
                if (error.noRoom) R.string.error_opensubtitles_not_saved_tv else R.string.error_opensubtitles_not_kept_tv,
                if (error.noRoom) {
                    R.string.error_opensubtitles_not_saved_device
                } else {
                    R.string.error_opensubtitles_not_kept_device
                },
                ViewerError.detailOf("NotSaved", codeOf(error.cause ?: error)),
            )
        is OpenSubtitlesFailure.SignInNotSaved ->
            ofHardware(
                R.string.error_opensubtitles_sign_in_not_saved_tv,
                R.string.error_opensubtitles_sign_in_not_saved_device,
                ViewerError.detailOf("SignInNotSaved", codeOf(error.cause ?: error)),
            )
        is OpenSubtitlesFailure.Unreachable -> ofOpenSubtitlesUnreachable(error)
        else -> ofHardware(
            R.string.error_opensubtitles_unreachable_tv,
            R.string.error_opensubtitles_unreachable_device,
            codeOf(error),
        )
    }

    companion object {
        private const val TOO_MANY_REQUESTS = 429
        private val SERVER_ERRORS = 500..599

        /**
         * A guide's address answering one of these is wrong or dead: trying again won't help. A
         * login a guide wants is one no setting here can give it, so 401 and 403 are the same.
         */
        private val NOT_A_GUIDE = setOf(
            HttpURLConnection.HTTP_NOT_FOUND,
            HttpURLConnection.HTTP_GONE,
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN,
        )

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

/** No answer from OpenSubtitles, or trouble at their end ([OpenSubtitlesFailure.Unreachable.status]). */
private fun ErrorWording.ofOpenSubtitlesUnreachable(error: OpenSubtitlesFailure.Unreachable): ViewerError {
    val status = error.status
    return if (status != null) {
        of(R.string.error_opensubtitles_trouble, "HTTP $status")
    } else {
        ofHardware(
            R.string.error_opensubtitles_unreachable_tv,
            R.string.error_opensubtitles_unreachable_device,
            ErrorWording.codeOf(error.cause ?: error),
        )
    }
}

/** Today's downloads used up, saying when more come where OpenSubtitles said, in [timeFormat]. */
private fun ofOpenSubtitlesLimit(
    error: OpenSubtitlesFailure.DownloadLimit,
    resources: android.content.res.Resources,
    timeFormat: java.time.format.DateTimeFormatter,
): ViewerError {
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
    return ViewerError(sentence, ViewerError.detailOf("DownloadLimit", "HTTP 406"))
}
