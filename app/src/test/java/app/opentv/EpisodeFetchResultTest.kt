/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpisodeFetchResultTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext<Application>())

    @After
    fun close() = test.close()

    @Test
    fun `a fetch the panel answers reports success and stores the episodes`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        test.panel.replies["get_series_info:7"] =
            """{"info": {}, "episodes": {"1": [{"id": "1", "episode_num": 1, "title": "Pilot"}]}}"""

        assertThat(test.catalog.ensureEpisodes(PANEL_SOURCE, "7")).isTrue()

        assertThat(test.db.episodes().forSeries(PANEL_SOURCE.id, "7").map { it.episodeId }).containsExactly("1")
    }

    @Test
    fun `a panel that doesn't answer reports failure`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        test.panel.replies["get_series_info:7"] = "!"

        assertThat(test.catalog.ensureEpisodes(PANEL_SOURCE, "7")).isFalse()
    }

    @Test
    fun `a cancelled fetch is cancelled, not a provider failure`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        val hold = test.panel.hold("get_series_info:7")
        var result: Boolean? = null
        val fetch = launch(Dispatchers.Default) { result = test.catalog.ensureEpisodes(PANEL_SOURCE, "7") }

        during(hold) { fetch.cancel() }
        hold.release()
        fetch.join()

        assertThat(result).isNull()
    }

    @Test
    fun `a series with no episodes isn't a failure`() = runTest {
        test.db.sources().insert(PANEL_SOURCE)
        test.panel.replies["get_series_info:7"] = """{"info": {}, "episodes": {}}"""

        assertThat(test.catalog.ensureEpisodes(PANEL_SOURCE, "7")).isTrue()
    }

    @Test
    fun `a playlist has nothing to fetch, which isn't a failure`() = runTest {
        val playlist = Source(id = 3, name = "Playlist", kind = SourceKind.M3U, url = "http://example.com/list.m3u")

        assertThat(test.catalog.ensureEpisodes(playlist, "7")).isTrue()

        assertThat(test.panel.asked).isEmpty()
    }
}
