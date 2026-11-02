/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RefreshStatus
import app.opentv.ui.guideStatusLine
import app.opentv.ui.refreshMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a viewer reads about a Catalogue Refresh. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RefreshMessagesTest {

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun message(status: RefreshStatus) = refreshMessage(resources, status)

    private fun guide(failed: Int, matched: Int = 90) = EpgRepository.SyncSummary(1, failed, 500, matched, 120)

    @Test
    fun `nothing happening says nothing`() {
        assertThat(message(RefreshStatus.Idle)).isNull()
    }

    @Test
    fun `work in progress says what it's doing`() {
        assertThat(message(RefreshStatus.Refreshing)).isEqualTo("Refreshing…")
        assertThat(message(RefreshStatus.LoadingChannels)).isEqualTo("Loading channels…")
    }

    @Test
    fun `a clean refresh gives the channel count and how much of the guide matched`() {
        assertThat(message(RefreshStatus.Refreshed(channels = 1, problems = 0, 90, 120)))
            .isEqualTo("Refreshed 1 channel, guide matched 90 of 120.")
        assertThat(message(RefreshStatus.Refreshed(channels = 300, problems = 0, 90, 120)))
            .isEqualTo("Refreshed 300 channels, guide matched 90 of 120.")
    }

    @Test
    fun `a refresh with problems counts them and says where to look`() {
        assertThat(message(RefreshStatus.Refreshed(channels = 300, problems = 1, 90, 120)))
            .isEqualTo("Refreshed 300 channels, 1 problem — see Guide settings.")
        assertThat(message(RefreshStatus.Refreshed(channels = 300, problems = 2, 90, 120)))
            .isEqualTo("Refreshed 300 channels, 2 problems — see Guide settings.")
    }

    @Test
    fun `a new provider's channels arriving says the rest is on its way`() {
        val rest = "The guide is loading in the background; Movies and Shows load when you open them."
        assertThat(message(RefreshStatus.BuildingGuide(channels = 1))).isEqualTo("Loaded 1 channel. $rest")
        assertThat(message(RefreshStatus.BuildingGuide(channels = 300))).isEqualTo("Loaded 300 channels. $rest")
    }

    @Test
    fun `a new provider's guide finishing says how much matched, or what to do if nothing did`() {
        assertThat(message(RefreshStatus.ProviderReady(channelsMatched = 90, channelsTotal = 120)))
            .isEqualTo("Guide ready — matched 90 of 120 channels.")
        assertThat(message(RefreshStatus.ProviderReady(channelsMatched = 0, channelsTotal = 120)))
            .isEqualTo("Channels ready. No guide data matched yet — add a free guide under Guide settings.")
    }

    @Test
    fun `a provider that won't load passes on the reason`() {
        assertThat(
            message(RefreshStatus.ProviderFailed("This account is Expired.")),
        ).isEqualTo("This account is Expired.")
    }

    @Test
    fun `Guide settings says how much matched, and how many feeds failed if any did`() {
        assertThat(guideStatusLine(resources, guide(failed = 0))).isEqualTo("Guide matched 90 of 120 channels")
        assertThat(guideStatusLine(resources, guide(failed = 1)))
            .isEqualTo("Guide matched 90 of 120 channels · 1 feed failed")
        assertThat(guideStatusLine(resources, guide(failed = 2)))
            .isEqualTo("Guide matched 90 of 120 channels · 2 feeds failed")
    }
}
