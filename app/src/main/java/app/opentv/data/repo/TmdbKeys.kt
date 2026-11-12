/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.core.AppSettings
import app.opentv.data.remote.TmdbCheck
import app.opentv.data.remote.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Saves and removes the viewer's TMDB Key, for Settings › TMDB, Onboarding and the phone page alike.
 * A key is saved only once TMDB has accepted it: saving one blind would leave a mistyped key failing
 * quietly behind every poster lookup, with nothing on screen to say why.
 */
class TmdbKeys(private val settings: AppSettings, private val tmdb: TmdbClient) {

    private val _savedEnding = MutableStateFlow(endingOf(settings.tmdbApiKey.value))

    /** The saved key's last four characters, enough to recognise it by; null with no key. */
    val savedEnding: StateFlow<String?> = _savedEnding.asStateFlow()

    /** Checks [key] with TMDB and saves it if TMDB accepts it; a key it doesn't leaves the saved one as it was. */
    suspend fun save(key: String): TmdbCheck {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return TmdbCheck.Rejected
        val check = withContext(Dispatchers.IO) { tmdb.check(trimmed) }
        if (check == TmdbCheck.Accepted) {
            settings.setTmdbApiKey(trimmed)
            _savedEnding.value = endingOf(trimmed)
        }
        return check
    }

    /** Stops every call to TMDB. What TMDB filled in stays: nothing is cleared or aged out without a key. */
    fun remove() {
        settings.removeTmdbApiKey()
        _savedEnding.value = null
    }

    private fun endingOf(key: String): String? = key.takeIf { it.isNotBlank() }?.takeLast(ENDING)

    private companion object {
        const val ENDING = 4
    }
}
