/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerError
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.repo.SetupStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setup: the first provider's Catalogue Refresh, step by step (docs/LOOK.md, Setting up). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SetupRefreshTest {

    private var now = 1_000_000_000L

    @get:Rule
    val testGraph = TestGraph(name = "setup-refresh-test")

    private val test = TestCatalog(testGraph.app, testGraph.database, dispatchers = testGraph.graph.dispatchers) { now }
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

    /**
     * The catalogue's threads, where a test runs its overlapping requests so the graph counts them
     * from the moment they're made: a request that has joined another is then one waiting on it.
     */
    private val catalogueThreads get() = testGraph.graph.dispatchers.default

    /** Waits for every request to have gone as far as it can, off the catalogue's threads, not counted as one. */
    private suspend fun settle() = withContext(Dispatchers.Default) { testGraph.awaitIdle() }

    /** A test body in real time, as the catalogue's threads keep it. */
    private fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    /** Hears every state the refresh passes through, as it's set, until the test ends. */
    private fun watch(heard: suspend (CatalogueRefresh.State) -> Unit) {
        testGraph.launch { withContext(Dispatchers.Unconfined) { refresh.state.collect(heard) } }
    }

    private suspend fun setUp(): CatalogueRefresh.Outcome {
        test.db.sources().insert(PANEL_SOURCE)
        val outcome = refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
        test.awaitRefresh()
        return outcome
    }

    @Test
    fun `setup adds the channels, then the guide, then movies, then shows, one after another`() = testing {
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
    fun `with Live TV switched off, setup leaves out the guide`() = testing {
        test.settings.setLiveEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("xmltv.php")
        assertThat(panel.asked).contains("get_live_streams")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.MOVIES, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `with Movies switched off, setup fetches shows but not movies`() = testing {
        test.settings.setMoviesEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(panel.asked).contains("get_series")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.GUIDE, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `with Shows switched off, setup fetches movies but not shows`() = testing {
        test.settings.setSeriesEnabled(false)

        setUp()

        assertThat(panel.asked).doesNotContain("get_series")
        assertThat(panel.asked).contains("get_vod_streams")
        assertThat(refresh.state.value.setup?.steps)
            .containsExactly(SetupStep.CHANNELS, SetupStep.GUIDE, SetupStep.MOVIES).inOrder()
    }

    @Test
    fun `a playlist has no movies or shows to fetch`() = testing {
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
    fun `each step is named only while it runs`() = testing {
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
    fun `each step that's done says how many it found`() = testing {
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
    fun `a guide that won't download doesn't stop setup, which says it didn't load`() = testing {
        panel.replies["xmltv.php"] = "!"

        setUp()

        val setup = refresh.state.value.setup!!
        assertThat(setup.finished).isTrue()
        assertThat(setup.failed).containsExactly(SetupStep.GUIDE)
        assertThat(panel.asked).contains("get_series")
    }

    @Test
    fun `movies or shows that won't download don't stop setup, which says which`() = testing {
        panel.replies["get_vod_streams"] = "!"
        panel.replies["get_series"] = "!"

        setUp()

        val setup = refresh.state.value.setup!!
        assertThat(setup.finished).isTrue()
        assertThat(setup.failed).containsExactly(SetupStep.MOVIES, SetupStep.SHOWS).inOrder()
    }

    @Test
    fun `movies that time out say the provider stopped responding, with the timeout on the detail line`() = testing {
        panel.replies["get_vod_streams"] = "!timeout"

        setUp()

        val movies = refresh.state.value.setup!!.done.single { it.step == SetupStep.MOVIES }
        assertThat(movies.failure).isEqualTo(
            ViewerError(testGraph.app.getString(R.string.error_timed_out), "SocketTimeout"),
        )
    }

    @Test
    fun `shows that fail for no reason a viewer could act on keep setup's own sentence, with the cause`() = testing {
        panel.replies["get_series"] = "!"

        setUp()

        val shows = refresh.state.value.setup!!.done.single { it.step == SetupStep.SHOWS }
        assertThat(shows.failure).isEqualTo(ViewerError(testGraph.app.getString(R.string.setup_failed_shows), "IO"))
    }

    @Test
    fun `movies too big for memory say it's about this device, not the provider`() = testing {
        panel.replies["get_vod_streams"] = "!oom"

        setUp()

        val movies = refresh.state.value.setup!!.done.single { it.step == SetupStep.MOVIES }
        assertThat(movies.failure).isEqualTo(
            ViewerError(testGraph.app.getString(R.string.error_channel_list_memory_device), "OutOfMemory"),
        )
    }

    @Test
    fun `a guide that won't download says why`() = testing {
        panel.replies["xmltv.php"] = "!503"

        setUp()

        val guide = refresh.state.value.setup!!.done.single { it.step == SetupStep.GUIDE }
        assertThat(guide.failure).isEqualTo(ViewerError(testGraph.app.getString(R.string.error_http_5xx), "HTTP 503"))
    }

    @Test
    fun `a login that fails says why, fetches nothing else and leaves no provider behind`() = testing {
        panel.replies["get_live_streams"] = "[]"

        val outcome = setUp()

        assertThat(outcome).isInstanceOf(CatalogueRefresh.Outcome.AddFailed::class.java)
        assertThat(panel.asked).doesNotContain("xmltv.php")
        assertThat(panel.asked).doesNotContain("get_vod_streams")
        // So the form's next try is the first provider again, and a restart opens on the provider choice.
        assertThat(test.db.sources().byId(PANEL_SOURCE.id)).isNull()
    }

    @Test
    fun `setup remembers which provider it's adding only until the channels are in`() = testing {
        val channels = panel.hold("get_live_streams")
        test.db.sources().insert(PANEL_SOURCE)
        // In the background: it returns once the channels are in, which this test is holding up.
        val started = async(catalogueThreads) {
            refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
        }

        during(channels) {
            assertThat(test.settings.setupSourceId).isEqualTo(PANEL_SOURCE.id)
            channels.release()
        }
        started.await()
        test.awaitRefresh()

        assertThat(test.settings.setupSourceId).isEqualTo(0L)
    }

    @Test
    fun `opening Movies or Shows after setup doesn't fetch them a second time`() = testing {
        setUp()
        panel.asked.clear()

        refresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(panel.asked).doesNotContain("get_series")
    }

    @Test
    fun `the bar fills each step's share as it finishes, and is full only once setup is`() = testing {
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
    fun `the bar moves while the guide downloads, not only when it's done`() = testing {
        panel.replies["xmltv.php"] = buildString {
            append("<tv>")
            repeat(3_000) {
                append(
                    """<programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="c$it">""" +
                        "<title>P</title></programme>",
                )
            }
            append("</tv>")
        }
        val bars = java.util.Collections.synchronizedList(mutableListOf<Float>())
        watch { it.setup?.let { s -> bars += s.bar } }

        setUp()

        assertThat(bars.filter { it > 0.2f && it < 0.7f }).isNotEmpty()
        assertThat(bars).isInOrder()
    }

    @Test
    fun `the bar moves while movies are saved, from where their download leaves it`() = testing {
        panel.replies["get_vod_streams"] =
            (1..3_000).joinToString(",", "[", "]") { """{"stream_id": $it, "name": "Film $it", "genre": "Drama"}""" }
        val bars = java.util.Collections.synchronizedList(mutableListOf<Float>())
        watch { it.setup?.let { s -> bars += s.bar } }

        setUp()

        // Movies run from 76% to 92%; their download, which can't tell how far it's got, is the
        // first 30% of that, as on a Stick.
        val saving = 0.76f + 0.16f * 0.3f
        assertThat(bars.filter { it > saving + 0.01f && it < 0.91f }).isNotEmpty()
        assertThat(bars.filter { it > 0.765f && it < saving - 0.001f }).isEmpty()
    }

    @Test
    fun `once the guide is in, the module's last result says so, as adding a provider does`() = testing {
        setUp()

        assertThat(refresh.state.value.last).isInstanceOf(CatalogueRefresh.Outcome.GuideLoaded::class.java)
    }

    @Test
    fun `while setup builds the guide, screens are told it's building`() = testing {
        val guide = panel.hold("xmltv.php")
        test.db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))

        during(guide) {
            assertThat(refresh.state.value.refreshingGuide).isTrue()
            guide.release()
        }
        test.awaitRefresh()

        assertThat(refresh.state.value.refreshingGuide).isFalse()
    }

    @Test
    fun `movies and shows another refresh is already fetching are still counted when setup shares them`() = testing {
        val guide = panel.hold("xmltv.php")
        val vod = panel.hold("get_vod_streams")
        test.db.sources().insert(PANEL_SOURCE)
        withContext(catalogueThreads) {
            refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
            // Left for Live TV once the channels were in, then opened Movies while the guide loads.
            val opened = during(guide) { async { refresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows) } }
            during(vod) {
                guide.release()
                // As far as setup goes with movies held: to them, sharing the download that's running.
                settle()
                vod.release()
            }
            opened.await()
        }
        test.awaitRefresh()

        val setup = refresh.state.value.setup!!
        assertThat(setup.done.map { it.step }).containsAtLeast(SetupStep.MOVIES, SetupStep.SHOWS)
        assertThat(setup.done.first { it.step == SetupStep.MOVIES }.count).isEqualTo(1)
        assertThat(setup.failed).isEmpty()
    }

    @Test
    fun `while the guide arrives, setup counts its programmes so far`() = testing {
        panel.unsized += "xmltv.php"
        panel.replies["xmltv.php"] = buildString {
            append("<tv>")
            repeat(3_000) {
                append("""<programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="c$it">""")
                append("<title>P</title></programme>")
            }
            append("</tv>")
        }
        val counts = java.util.Collections.synchronizedList(mutableListOf<Int>())
        watch { state ->
            state.setup?.takeIf { it.running == SetupStep.GUIDE }?.let {
                counts +=
                    it.found
            }
        }

        setUp()

        assertThat(counts.filter { it in 1..2_999 }).isNotEmpty()
        assertThat(counts).isInOrder()
    }

    @Test
    fun `undoing setup while the channels load leaves no provider behind, and setup says it was undone`() = testing {
        val channels = panel.hold("get_live_streams")
        test.db.sources().insert(PANEL_SOURCE)
        val started = async(catalogueThreads) {
            refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))
        }

        during(channels) {
            coroutineScope {
                val undone = async { refresh.undoSetup() }
                channels.release()
                undone.await()
            }
        }

        assertThat(started.await()).isEqualTo(CatalogueRefresh.Outcome.Undone)
        test.awaitRefresh()
        assertThat(test.db.sources().byId(PANEL_SOURCE.id)).isNull()
        assertThat(test.db.channels().countForSource(PANEL_SOURCE.id)).isEqualTo(0)
        assertThat(refresh.state.value.setup).isNull()
        assertThat(test.settings.setupSourceId).isEqualTo(0L)
    }

    @Test
    fun `undoing setup once the channels are in stops it and takes away what it saved`() = testing {
        val movies = panel.hold("get_vod_streams")
        test.db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Setup(PANEL_SOURCE))

        during(movies) {
            coroutineScope {
                val undone = async { refresh.undoSetup() }
                movies.release()
                undone.await()
            }
        }
        test.awaitRefresh()

        assertThat(panel.asked).doesNotContain("get_series")
        assertThat(test.db.sources().byId(PANEL_SOURCE.id)).isNull()
        assertThat(test.db.channels().countForSource(PANEL_SOURCE.id)).isEqualTo(0)
        assertThat(test.db.movies().countForSource(PANEL_SOURCE.id)).isEqualTo(0)
        assertThat(refresh.state.value.setup).isNull()
    }

    private companion object {
        val GUIDE = """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent()
    }
}
