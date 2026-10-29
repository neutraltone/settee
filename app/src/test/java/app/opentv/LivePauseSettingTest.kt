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

// Plain Application: OpenTvApp schedules WorkManager on start, which settings don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LivePauseSettingTest {

    @Test
    fun `turning live pause on forgets "don't ask again", so turning it off later asks again`() {
        val settings = AppSettings.get(ApplicationProvider.getApplicationContext())
        settings.setLivePauseEnabled(false)
        settings.setLivePausePromptDismissed(true)

        settings.setLivePauseEnabled(true)
        settings.setLivePauseEnabled(false)

        assertThat(settings.livePausePromptDismissed.value).isFalse()
    }
}
