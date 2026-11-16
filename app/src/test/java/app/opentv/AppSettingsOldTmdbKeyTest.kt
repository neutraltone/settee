/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Viewers no longer enter a TMDB Key (#515), so one saved under an earlier version would sit in the
 * settings file, and in Android's backups of it, with nothing left to read or remove it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppSettingsOldTmdbKeyTest {

    @Test
    fun `a TMDB Key a viewer saved under an earlier version is cleared`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        file.edit { putString("tmdb_api_key", "e".repeat(32)) }

        AppSettings.separate(context, FILE)

        assertThat(file.contains("tmdb_api_key")).isFalse()
    }

    private companion object {
        const val FILE = "old_tmdb_key"
    }
}
