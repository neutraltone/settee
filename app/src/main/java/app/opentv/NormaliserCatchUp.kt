/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.SharedPreferences
import androidx.core.content.edit
import app.opentv.data.repo.CatalogRepository

/**
 * When the normaliser has moved on since the catalogue was last processed, re-cleans the stored
 * channels and re-runs the guide matcher, locally, with no re-download. This is why a name-cleanup
 * fix shows up on the next launch rather than the next 6-hour sync, and it costs nothing when the
 * version has not changed.
 */
internal class NormaliserCatchUp(private val prefs: SharedPreferences, private val renormalise: suspend () -> Unit) {
    suspend operator fun invoke() {
        if (prefs.getInt(KEY, 0) < CatalogRepository.NORMALIZER_VERSION) {
            renormalise()
            prefs.edit { putInt(KEY, CatalogRepository.NORMALIZER_VERSION) }
        }
    }

    private companion object {
        const val KEY = "normalizer_version"
    }
}
