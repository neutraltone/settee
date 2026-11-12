/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.repo.CatalogueRefresh.Outcome
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RefreshMessages
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RefreshMessagesTest {

    private val messages = RefreshMessages(ApplicationProvider.getApplicationContext())

    private fun guide(matched: Int, total: Int, failed: Int = 0) =
        EpgRepository.SyncSummary(1, failed, 0, matched, total)

    @Test
    fun `a clean refresh counts channels and the guide match`() {
        assertThat(messages.of(Outcome.Refreshed(120, 0, guide(90, 120))))
            .isEqualTo("Refreshed 120 channels, guide matched 90 of 120.")
    }

    @Test
    fun `one channel is singular`() {
        assertThat(messages.of(Outcome.Refreshed(1, 0, guide(1, 1))))
            .isEqualTo("Refreshed 1 channel, guide matched 1 of 1.")
    }

    @Test
    fun `a refresh with problems counts them and points at Guide settings`() {
        assertThat(messages.of(Outcome.Refreshed(120, 1, guide(90, 120))))
            .isEqualTo("Refreshed 120 channels, 1 problem. See Guide settings.")
        assertThat(messages.of(Outcome.Refreshed(1, 3, guide(0, 1))))
            .isEqualTo("Refreshed 1 channel, 3 problems. See Guide settings.")
    }

    @Test
    fun `adding a provider says what's still loading`() {
        assertThat(messages.of(Outcome.ChannelsLoaded(2))).isEqualTo(
            "Loaded 2 channels. The guide is loading in the background; Movies and Shows load when you open them.",
        )
        assertThat(messages.of(Outcome.ChannelsLoaded(1))).startsWith("Loaded 1 channel.")
    }

    @Test
    fun `a failed add has no status line, since its reason is shown with its detail line`() {
        assertThat(
            messages.of(Outcome.AddFailed(ViewerError("Wrong password.", "HTTP 401"))),
        ).isNull()
    }

    @Test
    fun `a new provider's guide says how much matched, or where to find a free one`() {
        assertThat(messages.of(Outcome.GuideLoaded(guide(5, 10))))
            .isEqualTo("Guide ready. Matched 5 of 10 channels.")
        assertThat(messages.of(Outcome.GuideLoaded(guide(0, 10))))
            .isEqualTo("Channels ready. No guide data matched yet. Add a free guide in Guide settings.")
    }

    @Test
    fun `Guide settings counts failed feeds only when there are any`() {
        assertThat(messages.of(Outcome.GuideRefreshed(guide(5, 10))))
            .isEqualTo("Guide matched 5 of 10 channels")
        assertThat(messages.of(Outcome.GuideRefreshed(guide(5, 10, failed = 1))))
            .isEqualTo("Guide matched 5 of 10 channels · 1 feed failed")
        assertThat(messages.of(Outcome.GuideRefreshed(guide(5, 10, failed = 2))))
            .isEqualTo("Guide matched 5 of 10 channels · 2 feeds failed")
    }

    @Test
    fun `checking Movies and Shows has nothing to say`() {
        assertThat(messages.of(Outcome.VodChecked(fetched = 1))).isNull()
    }
}
