/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateUiState
import app.opentv.update.shownWhere
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

    @Test
    fun `the launch check's find waits off the main screen and prompts on it`() {
        val found = UpdateUiState.Available(update)

        assertThat(found.shownWhere(canShow = false)).isEqualTo(UpdateUiState.Idle)
        assertThat(found.shownWhere(canShow = true)).isEqualTo(found)
    }

    @Test
    fun `an update the viewer checked for prompts on any screen`() {
        val asked = UpdateUiState.Available(update, asked = true)

        assertThat(asked.shownWhere(canShow = false)).isEqualTo(asked)
    }

    @Test
    fun `launch prompts open only on the main screen`() {
        assertThat(launchPromptMayShow(Routes.MAIN)).isTrue()
        assertThat(launchPromptMayShow(Routes.ADD_SOURCE)).isFalse()
        assertThat(launchPromptMayShow(Routes.PLAYER)).isFalse()
        assertThat(launchPromptMayShow(null)).isFalse()
    }

    @Test
    fun `the launch check's update waits for What's new`() {
        assertThat(launchPromptMayShow(Routes.MAIN, afterWhatsNew = false)).isFalse()
        assertThat(launchPromptMayShow(Routes.MAIN, afterWhatsNew = true)).isTrue()
    }

    @Test
    fun `the download's panels show on any screen`() {
        val downloading = UpdateUiState.Downloading(update, 0.2f)
        val failed = UpdateUiState.Failed(update, "HTTP 500")

        assertThat(downloading.shownWhere(canShow = false)).isEqualTo(downloading)
        assertThat(failed.shownWhere(canShow = false)).isEqualTo(failed)
    }
}
