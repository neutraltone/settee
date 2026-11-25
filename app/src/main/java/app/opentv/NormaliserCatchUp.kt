/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.core.content.edit
import app.opentv.data.repo.CatalogRepository

/**
 * When the normaliser has moved on since the catalogue was last processed, re-cleans the stored
 * channels and re-runs the guide matcher, locally, with no re-download. This is why a name-cleanup
 * fix shows up on the next launch rather than the next 6-hour sync, and it costs nothing when the
 * version has not changed.
 */
internal class NormaliserCatchUp(
    private val context: Context,
    private val renormalise: suspend () -> Unit,
    /**
     * Moves Parental lock's saved category keys to the ones the new normaliser gives, told which
     * version made them. First, as it's quick and the categories show until it's done; cleaning the
     * catalogue isn't.
     */
    private val carryLocks: suspend (from: Int) -> Unit = {},
    /**
     * With the normaliser unchanged: has the locks remember the names of the categories they lock,
     * while today's keys still find them, so the next change can carry them.
     */
    private val rememberLocks: suspend () -> Unit = {},
) {
    // Opened here rather than passed in, so the file is read on the launch work's thread, not the
    // main one that builds it as the first screen opens.
    suspend operator fun invoke() {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val from = prefs.getInt(KEY, 0)
        if (from < CatalogRepository.NORMALIZER_VERSION) {
            carryLocks(from)
            renormalise()
            prefs.edit { putInt(KEY, CatalogRepository.NORMALIZER_VERSION) }
        } else {
            rememberLocks()
        }
    }

    private companion object {
        const val KEY = "normalizer_version"
    }
}
