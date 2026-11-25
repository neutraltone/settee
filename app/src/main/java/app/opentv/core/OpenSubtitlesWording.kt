/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import app.opentv.R
import app.opentv.data.remote.OpenSubtitlesFailure

/** What went wrong with OpenSubtitles: finding, downloading or signing in. */
fun ErrorWording.ofOpenSubtitles(error: Throwable): ViewerError = when (error) {
    is OpenSubtitlesFailure.NoResults -> of(R.string.error_opensubtitles_none, "NoResults")
    is OpenSubtitlesFailure.DownloadLimit -> ofOpenSubtitlesLimit(error)
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
            ViewerError.detailOf("NotSaved", ErrorWording.codeOf(error.cause ?: error)),
        )
    is OpenSubtitlesFailure.SignInNotSaved ->
        ofHardware(
            R.string.error_opensubtitles_sign_in_not_saved_tv,
            R.string.error_opensubtitles_sign_in_not_saved_device,
            ViewerError.detailOf("SignInNotSaved", ErrorWording.codeOf(error.cause ?: error)),
        )
    is OpenSubtitlesFailure.Unreachable -> ofOpenSubtitlesUnreachable(error)
    else -> ofHardware(
        R.string.error_opensubtitles_unreachable_tv,
        R.string.error_opensubtitles_unreachable_device,
        ErrorWording.codeOf(error),
    )
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

/** Today's downloads used up, saying when more come where OpenSubtitles said, in the app's time format. */
private fun ErrorWording.ofOpenSubtitlesLimit(error: OpenSubtitlesFailure.DownloadLimit): ViewerError {
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
