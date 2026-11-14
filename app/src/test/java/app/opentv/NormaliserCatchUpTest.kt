/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.CatalogRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NormaliserCatchUpTest {

    // The file and key installed builds have already written, so a rename here would re-clean every launch.
    private val prefs = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("opentv", Context.MODE_PRIVATE)
    private var renormalised = 0
    private val catchUp = NormaliserCatchUp(prefs, renormalise = { renormalised++ })

    @Test
    fun `a fresh install cleans the catalogue and remembers the normaliser it used`() = runTest {
        catchUp()

        assertThat(renormalised).isEqualTo(1)
        assertThat(prefs.getInt("normalizer_version", 0)).isEqualTo(CatalogRepository.NORMALIZER_VERSION)
    }

    @Test
    fun `a catalogue already cleaned by this normaliser isn't cleaned again`() = runTest {
        prefs.edit { putInt("normalizer_version", CatalogRepository.NORMALIZER_VERSION) }

        catchUp()

        assertThat(renormalised).isEqualTo(0)
    }
}
