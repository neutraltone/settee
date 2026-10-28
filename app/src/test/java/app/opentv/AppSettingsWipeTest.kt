/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A destructive migration empties every table but leaves SharedPreferences alone, so settings
 * that name a row would point at whatever reuses that id once the user re-adds a provider.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which settings don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppSettingsWipeTest {

    @Test
    fun `forgetting database rows resets every setting that names a row`() {
        val settings = AppSettings.get(ApplicationProvider.getApplicationContext())
        settings.setActiveProfile(3L)
        settings.lastChannelId = 42L
        settings.vodSyncedAtMillis = 1_700_000_000_000L

        settings.forgetDatabaseRows()

        assertThat(settings.activeProfileId.value).isEqualTo(1L)
        assertThat(settings.lastChannelId).isEqualTo(0L)
        assertThat(settings.vodSyncedAtMillis).isEqualTo(0L)
    }
}
