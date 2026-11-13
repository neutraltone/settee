/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppSettings
import app.opentv.data.remote.TmdbCheck
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Saves and removes the viewer's TMDB Key, for Settings › TMDB, Onboarding and the phone page alike.
 * A key is saved only once TMDB has accepted it ([check], `TmdbClient.check`): saving one blind would
 * leave a mistyped key failing quietly behind every poster lookup, with nothing on screen to say why.
 */
class TmdbKeys(private val settings: AppSettings, private val check: suspend (String) -> TmdbCheck) {

    /** The saved key's last four characters, enough to recognise it by; null with no key. */
    val savedEnding: Flow<String?> = settings.tmdbApiKey.map(::endingOf)

    /** Checks [key] with TMDB and saves it if TMDB accepts it; a key it doesn't leaves the saved one as it was. */
    suspend fun save(key: String): TmdbCheck {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return TmdbCheck.Rejected
        val answer = check(trimmed)
        if (answer == TmdbCheck.Accepted) settings.setTmdbApiKey(trimmed)
        return answer
    }

    /** Stops every call to TMDB. What TMDB filled in stays: nothing is cleared or aged out without a key. */
    fun remove() = settings.removeTmdbApiKey()

    companion object {
        private const val ENDING = 4

        /** The last four characters of [key], enough to recognise it by; null for no key. */
        fun endingOf(key: String): String? = key.takeIf { it.isNotBlank() }?.takeLast(ENDING)
    }
}
