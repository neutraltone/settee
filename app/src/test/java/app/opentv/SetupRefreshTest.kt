/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.SetupStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Test
    fun `a playlist has no movies or shows to fetch`() = runTest {
        val playlist = Source(id = 2, name = "List", kind = SourceKind.M3U, url = "http://example.com/list.m3u")
        panel.replies[""] = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n"
        test.db.sources().insert(playlist)

        refresh.refresh(CatalogueRefresh.Trigger.Setup(playlist))
        test.awaitRefresh()

        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.GUIDE).inOrder()
        assertThat(refresh.state.value.setup?.finished).isTrue()
    }

    @Test
    fun `each step is named only while it runs`() = runTest {
        val guide = panel.hold("xmltv.php")
        val movies = panel.hold("get_vod_streams")
        test.db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))

        during(guide) {
            assertThat(refresh.state.value.setup?.running).isEqualTo(SetupStep.GUIDE)
            guide.release()
        }
        during(movies) {
            assertThat(refresh.state.value.setup?.running).isEqualTo(SetupStep.MOVIES)
            movies.release()
        }
        test.awaitRefresh()

        assertThat(refresh.state.value.setup?.running).isNull()
    }

    @Test
    fun `each step that's done says how many it found`() = runTest {
        panel.replies["get_live_streams"] =
            """[{"stream_id": 1, "name": "BBC One", "epg_channel_id": "bbc1"}, {"stream_id": 2, "name": "Dave"}]"""

        setUp()

        assertThat(refresh.state.value.setup?.done).containsExactly(
            CatalogueRefresh.StepDone(SetupStep.CHANNELS, count = 2),
            CatalogueRefresh.StepDone(SetupStep.GUIDE, count = 1, of = 2),
            CatalogueRefresh.StepDone(SetupStep.MOVIES, count = 1),
            CatalogueRefresh.StepDone(SetupStep.SHOWS, count = 1),
        ).inOrder()
    }

    @Test
    fun `a guide that won't download doesn't stop setup, which says it didn't load`() = runTest {
        panel.replies["xmltv.php"] = "!"

        setUp()

        val setup = refresh.state.value.setup!!
        assertThat(setup.finished).isTrue()
        assertThat(setup.failed).containsExactly(SetupStep.GUIDE)
        assertThat(panel.asked).contains("get_series")
    }

    @Test
    fun `movies or shows that won't download don't stop setup, which says which`() = runTest {
        panel.replies["get_vod_streams"] = "!"
        panel.replies["get_series"] = "!"

        setUp()

        val setup = refresh.state.value.setup!!
        assertThat(setup.finished).isTrue()
        assertThat(setup.failed).containsExactly(SetupStep.MOVIES, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `a login that fails says why, fetches nothing else and leaves no provider behind`() = runTest {
        panel.replies["get_live_streams"] = "[]"

        val outcome = setUp()

        assertThat(outcome).isInstanceOf(CatalogueRefresh.Outcome.AddFailed::class.java)
        assertThat(panel.asked).doesNotContain("xmltv.php")
        assertThat(panel.asked).doesNotContain("get_vod_streams")
        // So the form's next try is the first provider again, and a restart opens on the provider choice.
        assertThat(test.db.sources().byId(PANEL_SOURCE.id)).isNull()
    }

    @Test
    fun `opening Movies or Shows after setup doesn't fetch them a second time`() = runTest {
        setUp()
        panel.asked.clear()

        refresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(panel.asked).doesNotContain("get_series")
    }

    @Test
    fun `the bar fills each step's share as it finishes, and is full only once setup is`() = runTest {
        val guide = panel.hold("xmltv.php")
        val movies = panel.hold("get_vod_streams")
        test.db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))

        during(guide) {
            // The channels' 15%, and the guide's feeds set up: 7% of its 61%.
            assertThat(refresh.state.value.setup?.bar).isWithin(0.001f).of(0.15f + 0.61f * 0.07f)
            guide.release()
        }
        during(movies) {
            assertThat(refresh.state.value.setup?.bar).isWithin(0.001f).of(0.76f)
            movies.release()
        }
        test.awaitRefresh()

        assertThat(refresh.state.value.setup?.bar).isEqualTo(1f)
    }

    @Test
    fun `the bar moves while the guide downloads, not only when it's done`() = runTest {
        panel.replies["xmltv.php"] = buildString {
            append("<tv>")
            repeat(3_000) { append("""<programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="c$it"><title>P</title></programme>""") }
            append("</tv>")
        }
        val bars = java.util.Collections.synchronizedList(mutableListOf<Float>())
        backgroundScope.launch(Dispatchers.Unconfined) { refresh.state.collect { it.setup?.let { s -> bars += s.bar } } }

        setUp()

        assertThat(bars.filter { it > 0.2f && it < 0.7f }).isNotEmpty()
        assertThat(bars).isInOrder()
    }

    @Test
    fun `the bar moves while movies are saved, from where their download leaves it`() = runTest {
        panel.replies["get_vod_streams"] =
            (1..3_000).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it", "genre": "Drama"}""" }
        val bars = java.util.Collections.synchronizedList(mutableListOf<Float>())
        backgroundScope.launch(Dispatchers.Unconfined) { refresh.state.collect { it.setup?.let { s -> bars += s.bar } } }

        setUp()

        // Movies run from 76% to 92%; their download, which can't tell how far it's got, is the
        // first 30% of that, as on a Stick.
        val saving = 0.76f + 0.16f * 0.3f
        assertThat(bars.filter { it > saving + 0.01f && it < 0.91f }).isNotEmpty()
        assertThat(bars.filter { it > 0.765f && it < saving - 0.001f }).isEmpty()
    }

    @Test
    fun `once the guide is in, the module's last result says so, as adding a provider does`() = runTest {
        setUp()

        assertThat(refresh.state.value.last).isInstanceOf(CatalogueRefresh.Outcome.GuideLoaded::class.java)
    }

    @Test
    fun `while setup builds the guide, screens are told it's building`() = runTest {
        val guide = panel.hold("xmltv.php")
        test.db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))

        during(guide) {
            assertThat(refresh.state.value.buildingGuide).isTrue()
            guide.release()
        }
        test.awaitRefresh()

        assertThat(refresh.state.value.buildingGuide).isFalse()
    }

    private companion object {
        val GUIDE = """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent()
    }
}
