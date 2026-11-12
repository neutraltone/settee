/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.SetupStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setup: the first provider's Catalogue Refresh, step by step (docs/LOOK.md, Setting up). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SetupRefreshTest {

    private var now = 1_000_000_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext(), clock = { now })
    private val panel = test.panel
    private val refresh = test.refresh()

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One", "epg_channel_id": "bbc1"}]"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Taskmaster"}]"""
        panel.replies["xmltv.php"] = GUIDE
    }

    @After
    fun close() {
        test.settings.forgetDatabaseRows()
        test.settings.setLiveEnabled(true)
        test.settings.setMoviesEnabled(true)
        test.settings.setSeriesEnabled(true)
        test.close()
    }

    private suspend fun setUp(): CatalogueRefresh.Outcome.Added {
        test.db.sources().insert(PANEL_SOURCE)
        val outcome = refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
        test.awaitRefresh()
        return outcome
    }

    @Test
    fun `setup adds the channels, then the guide, then movies, then shows, one after another`() = runTest {
        setUp()

        assertThat(panel.asked.filterNot { it.startsWith("get_vod_info") }).containsExactly(
            "",
            "get_live_categories",
            "get_live_streams",
            "xmltv.php",
            "get_vod_categories",
            "get_vod_streams",
            "get_series_categories",
            "get_series",
        ).inOrder()
        assertThat(refresh.state.value.setup?.finished).isTrue()
    }

    @Test
    fun `with Live TV switched off, setup leaves out the guide`() = runTest {
        test.settings.setLiveEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("xmltv.php")
        assertThat(panel.asked).contains("get_live_streams")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.MOVIES, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `with Movies switched off, setup fetches shows but not movies`() = runTest {
        test.settings.setMoviesEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(panel.asked).contains("get_series")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.GUIDE, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `with Shows switched off, setup fetches movies but not shows`() = runTest {
        test.settings.setSeriesEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("get_series")
        assertThat(panel.asked).contains("get_vod_streams")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.GUIDE, SetupStep.MOVIES).inOrder()
    }

    private companion object {
        val GUIDE = """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent()
    }
}
