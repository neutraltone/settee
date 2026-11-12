/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateUiState
import app.opentv.update.withProgress
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateProgressTest {

    private val update = UpdateChecker.Update(
        versionName = "0.5.0",
        title = "Settee 0.5.0",
        notes = null,
        apkUrl = "https://example.com/settee.apk",
        apkSizeBytes = 1000,
        releaseUrl = "https://example.com/release",
    )

    @Test
    fun `progress moves the downloading panel on`() {
        val showing = UpdateUiState.Downloading(update, 0.2f)

        assertThat(showing.withProgress(0.6f)).isEqualTo(UpdateUiState.Downloading(update, 0.6f))
    }

    @Test
    fun `progress after Back hid the panel leaves it hidden`() {
        assertThat(UpdateUiState.Idle.withProgress(0.6f)).isEqualTo(UpdateUiState.Idle)
    }
}
