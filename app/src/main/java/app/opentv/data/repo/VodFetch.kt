/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import androidx.annotation.StringRes
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.core.ViewerError
import app.opentv.data.model.Series

/**
 * Movies and shows that failed as a whole, outside either list: each list switched on failed with
 * it, so the tab still says why rather than that the provider sent nothing.
 */
fun vodFailed(error: Throwable, wording: ErrorWording, settings: AppSettings): VodFetch {
    fun failed(on: Boolean, @StringRes fallback: Int) = ListFetch.of(0, error, wording, fallback).takeIf { on }
    return VodFetch.of(
        failed(settings.moviesEnabled.value, R.string.error_movies_list),
        failed(settings.seriesEnabled.value, R.string.error_shows_list),
    )
}

/**
 * Series the provider updated, for New Episode detection, whether every list came back, and how
 * each of [movies] and [shows] went, for a tab whose list failed to say why: null when it wasn't
 * asked for, so it says nothing either way.
 */
data class VodFetch(
    val updated: List<Series>,
    val complete: Boolean,
    val movies: ListFetch? = null,
    val shows: ListFetch? = null,
) {
    companion object {
        fun of(movies: ListFetch?, shows: ListFetch?) = VodFetch(
            shows?.updated.orEmpty(),
            // Nothing at all may be a struggling panel's empty reply, so it isn't worth trusting
            // for 12 hours; an account with no VOD just asks again, which is a quick request.
            movies?.complete != false &&
                shows?.complete != false &&
                ((movies?.count ?: 0) > 0 || (shows?.count ?: 0) > 0),
            movies,
            shows,
        )
    }
}

/**
 * One of an Xtream provider's lists, movies or shows: how many titles it had, whether it came
 * back (a failed list still leaves the other to be saved, but mustn't count as fetched), and for
 * shows, the ones updated since they were last saved. A list that didn't come back has the
 * [failure] in words.
 */
data class ListFetch(
    val count: Int,
    val failure: ViewerError? = null,
    val updated: List<Series> = emptyList(),
    /**
     * What [failure] was worded from, for Setup, which words it again with its own sentence: a tab's
     * "try again in a moment" is wrong there, where Settee tries again by itself.
     */
    val cause: Throwable? = null,
) {
    val complete: Boolean get() = failure == null

    companion object {
        /** [count] titles, or a list that failed with [cause], worded with [fallback] for a tab. */
        fun of(
            count: Int,
            cause: Throwable?,
            wording: ErrorWording,
            @StringRes fallback: Int,
            updated: List<Series> = emptyList(),
        ) = ListFetch(count, cause?.let { wording.ofProvider(it, fallback) }, updated, cause)
    }
}
