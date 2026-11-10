/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.CatalogueRefresh
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueRefreshTest {

    private var now = 1_000_000_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext(), clock = { now })
    private val db = test.db
    private val panel = test.panel
    private val refresh = test.refresh { panel.asked += RESCAN }

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        panel.replies["xmltv.php"] = GUIDE
    }

    @After
    fun close() {
        // AppSettings is a process-wide singleton, so what one test sets would reach the next.
        test.settings.forgetDatabaseRows()
        test.settings.setLiveEnabled(true)
        test.settings.setMoviesEnabled(true)
        test.settings.setSeriesEnabled(true)
        test.close()
    }

    private fun vodDownloads() = panel.asked.count { it == "get_vod_streams" }

    /** Clears the log, once a sync's background date lookups can no longer land in what comes next. */
    private suspend fun forgetAsked() {
        test.catalog.awaitBackground()
        panel.asked.clear()
    }

    private suspend fun openMoviesOrShows() = refresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)

    @Test
    fun `the periodic refresh fetches live, then movies and shows, then the guide, then rescans series rules`() =
        runTest {
            db.sources().insert(PANEL_SOURCE)

            refresh.refresh(CatalogueRefresh.Trigger.Periodic)

            // Movies' release dates are looked up in the background, at no fixed point in this order.
            assertThat(panel.asked.filterNot { it.startsWith("get_vod_info") }).containsExactly(
                "",
                "get_live_categories",
                "get_live_streams",
                "get_vod_categories",
                "get_vod_streams",
                "get_series_categories",
                "get_series",
                "xmltv.php",
                RESCAN,
            ).inOrder()
        }

    @Test
    fun `a refresh the viewer asked for downloads the guide even if it was just fetched`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        forgetAsked()

        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(panel.asked).contains("xmltv.php")
    }

    @Test
    fun `a periodic refresh leaves a guide it just fetched alone`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        forgetAsked()

        refresh.refresh(CatalogueRefresh.Trigger.Periodic)

        assertThat(panel.asked).doesNotContain("xmltv.php")
    }

    @Test
    fun `adding a provider loads its channels, then the guide and series rules, and no movies or shows`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))
        test.awaitRefresh()

        assertThat(outcome).isEqualTo(CatalogueRefresh.Outcome.ChannelsLoaded(channels = 1))
        assertThat(panel.asked).containsExactly(
            "",
            "get_live_categories",
            "get_live_streams",
            "xmltv.php",
            RESCAN,
        ).inOrder()
    }

    @Test
    fun `adding a provider whose login fails says why and fetches nothing else`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_live_streams"] = "[]"

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))
        test.awaitRefresh()

        assertThat(outcome).isInstanceOf(CatalogueRefresh.Outcome.AddFailed::class.java)
        assertThat(panel.asked).doesNotContain("xmltv.php")
        assertThat(panel.asked).doesNotContain(RESCAN)
    }

    @Test
    fun `when the guide has loaded for a new provider, the module's last result says so`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))
        test.awaitRefresh()

        assertThat(refresh.state.value.last).isInstanceOf(CatalogueRefresh.Outcome.GuideLoaded::class.java)
    }

    @Test
    fun `app launch only refreshes a guide that is out of date`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        refresh.refresh(CatalogueRefresh.Trigger.AppLaunch)
        assertThat(panel.asked).containsExactly("xmltv.php")
        forgetAsked()

        refresh.refresh(CatalogueRefresh.Trigger.AppLaunch)
        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `Guide settings downloads the guide every time and touches nothing else`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        refresh.refresh(CatalogueRefresh.Trigger.GuideSettings)
        refresh.refresh(CatalogueRefresh.Trigger.GuideSettings)

        assertThat(panel.asked).containsExactly("xmltv.php", "xmltv.php")
    }

    @Test
    fun `opening Movies or Shows fetches a provider that has never been fetched`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        openMoviesOrShows()

        assertThat(db.movies().all().map { it.name }).containsExactly("Drive")
        assertThat(panel.asked).doesNotContain("get_live_streams")
    }

    @Test
    fun `opening Movies or Shows again within 12 hours downloads nothing`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        forgetAsked()

        now += TWELVE_HOURS - 1
        openMoviesOrShows()

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `opening Movies or Shows after 12 hours fetches them again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        forgetAsked()

        now += TWELVE_HOURS
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `opening Movies or Shows right after the periodic refresh doesn't download them again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        forgetAsked()

        openMoviesOrShows()

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `opening Movies or Shows right after a manual refresh doesn't download them again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        refresh.refresh(CatalogueRefresh.Trigger.Manual)
        forgetAsked()

        openMoviesOrShows()

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `a provider added soon after the last fetch has its titles fetched the first time they're opened`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        forgetAsked()

        val second = PANEL_SOURCE.copy(id = 2, name = "Second")
        db.sources().insert(second)
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
        assertThat(db.movies().forSource(2).map { it.name }).containsExactly("Drive")
    }

    @Test
    fun `a movie list longer than a batch is all saved, and the next fetch updates the same rows`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        fun films(added: Int) = (1..1_200).joinToString(",", "[", "]") {
            """{"stream_id": $it, "name": "Film $it", "added": "$added"}"""
        }
        panel.replies["get_vod_streams"] = films(added = 100)
        openMoviesOrShows()
        val ids = db.movies().forSource(1).associate { it.streamId to it.id }
        forgetAsked()

        now += TWELVE_HOURS
        panel.replies["get_vod_streams"] = films(added = 200)
        openMoviesOrShows()

        val movies = db.movies().forSource(1)
        assertThat(movies.associate { it.streamId to it.id }).isEqualTo(ids)
        assertThat(ids).hasSize(1_200)
        assertThat(movies.map { it.addedMillis }.toSet()).containsExactly(200_000L)
    }

    @Test
    fun `a failed movie list isn't remembered as fresh, so the next open tries again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_vod_streams"] = "!"
        openMoviesOrShows()
        forgetAsked()

        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        openMoviesOrShows()

        assertThat(db.movies().all().map { it.name }).containsExactly("Drive")
    }

    @Test
    fun `a failed show list isn't remembered as fresh either`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series"] = "!"
        openMoviesOrShows()
        forgetAsked()

        panel.replies["get_series"] = "[]"
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a database rebuilt from scratch forgets what was fetched`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        forgetAsked()

        test.settings.forgetDatabaseRows()
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `switching Movies on fetches every provider the next time they're opened`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        forgetAsked()

        test.settings.setMoviesEnabled(true)
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a second refresh of a provider shares the running download and its result`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_live_streams")

        val outcomes = withContext(Dispatchers.Default) {
            val first = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            val second = during(hold) {
                async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }.also { Thread.sleep(JOIN_MILLIS) }
            }
            hold.release()
            first.await() to second.await()
        }

        assertThat(panel.asked.count { it == "get_live_streams" }).isEqualTo(1)
        assertThat(vodDownloads()).isEqualTo(1)
        assertThat(outcomes.first.channels).isEqualTo(1)
        assertThat(outcomes.second.channels).isEqualTo(1)
    }

    @Test
    fun `opening Movies during another refresh's movie download waits for it and doesn't download again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val worker = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            val opened = during(hold) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
            hold.release()
            worker.await()
            opened.await()
        }

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `opening Movies while a new provider's channels download waits its turn, then fetches them`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_live_streams")

        withContext(Dispatchers.Default) {
            val added = async { refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE)) }
            val opened = during(hold) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
            assertThat(panel.asked).doesNotContain("get_vod_categories")
            hold.release()
            added.await()
            opened.await()
        }

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a download others are sharing carries on when the first to ask leaves`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val leaving = async { openMoviesOrShows() }
            val staying = during(hold) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
            leaving.cancel()
            hold.release()
            staying.await()
        }

        assertThat(db.movies().all().map { it.name }).containsExactly("Drive")
    }

    /** A show the Profile is caught up on, that the provider has just updated. */
    private suspend fun caughtUpOnAnUpdatedShow() {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "100"}]"""
        panel.replies["get_series_info:7"] =
            """{"info": {}, "episodes": {"1": [{"id": "1", "episode_num": 1, "title": "Pilot"}]}}"""
        openMoviesOrShows()
        test.catalog.ensureEpisodes(PANEL_SOURCE, "7", now)
        val pilot = db.episodes().forSeries(1, "7").single()
        test.viewerLibrary.recordProgress("ep:${pilot.id}", positionMillis = 2_400_000, durationMillis = 2_400_000)
        test.awaitRefresh()
        test.catalog.awaitBackground()
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "200"}]"""
        now += TWELVE_HOURS
    }

    private suspend fun assertWaitsForNewEpisodes(trigger: CatalogueRefresh.Trigger.Catalogue) {
        caughtUpOnAnUpdatedShow()
        val hold = panel.hold("get_series_info:7")
        withContext(Dispatchers.Default) {
            val refreshing = async { refresh.refresh(trigger) }
            during(hold) { Thread.sleep(JOIN_MILLIS) }
            assertThat(refreshing.isCompleted).isFalse()
            hold.release()
            refreshing.await()
        }
    }

    @Test
    fun `the periodic refresh waits for New Episode detection`() = runTest {
        assertWaitsForNewEpisodes(CatalogueRefresh.Trigger.Periodic)
    }

    @Test
    fun `a manual refresh, which runs as background work, waits for New Episode detection`() = runTest {
        assertWaitsForNewEpisodes(CatalogueRefresh.Trigger.Manual)
    }

    @Test
    fun `opening Movies or Shows launches New Episode detection and doesn't wait for it`() = runTest {
        caughtUpOnAnUpdatedShow()
        val hold = panel.hold("get_series_info:7")

        withContext(Dispatchers.Default) {
            withTimeout(10_000) { openMoviesOrShows() }
        }
        assertThat(hold.reached.await(10, TimeUnit.SECONDS)).isTrue()
        hold.release()
    }

    @Test
    fun `with Live off a refresh leaves channels alone but still fetches movies, shows and the guide`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        test.settings.setLiveEnabled(false)

        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(panel.asked).doesNotContain("get_live_streams")
        assertThat(vodDownloads()).isEqualTo(2)
        assertThat(panel.asked).contains("xmltv.php")
    }

    @Test
    fun `with Movies off no trigger downloads movies, and shows still come`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        test.settings.setMoviesEnabled(false)

        openMoviesOrShows()
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(panel.asked).contains("get_series")
    }

    @Test
    fun `with Shows off no trigger downloads shows, and movies still come`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        test.settings.setSeriesEnabled(false)

        openMoviesOrShows()
        refresh.refresh(CatalogueRefresh.Trigger.Periodic)
        refresh.refresh(CatalogueRefresh.Trigger.Manual)

        assertThat(panel.asked).doesNotContain("get_series")
        assertThat(panel.asked).contains("get_vod_streams")
    }

    @Test
    fun `adding a provider loads its channels even with Live off`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        test.settings.setLiveEnabled(false)

        val outcome = refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))

        assertThat(outcome).isEqualTo(CatalogueRefresh.Outcome.ChannelsLoaded(channels = 1))
    }

    @Test
    fun `a refresh says it's refreshing while it runs, and leaves its result behind`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_live_streams")

        withContext(Dispatchers.Default) {
            val running = async { refresh.refresh(CatalogueRefresh.Trigger.Manual) }
            during(hold) { assertThat(refresh.state.value.refreshing).isTrue() }
            hold.release()
            running.await()
        }

        assertThat(refresh.state.value.refreshing).isFalse()
        assertThat(refresh.state.value.last).isInstanceOf(CatalogueRefresh.Outcome.Refreshed::class.java)
    }

    @Test
    fun `two refreshes overlapping stay refreshing until the last has finished`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("xmltv.php")

        withContext(Dispatchers.Default) {
            val first = async { refresh.refresh(CatalogueRefresh.Trigger.Manual) }
            val second = during(hold) { async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) } }
            hold.release()
            first.await()
            second.await()
        }

        assertThat(refresh.state.value.refreshing).isFalse()
    }

    @Test
    fun `Movies and Shows say they're loading only while a download is running`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val opened = async { openMoviesOrShows() }
            during(hold) { assertThat(refresh.state.value.loadingVod).isTrue() }
            hold.release()
            opened.await()
        }

        assertThat(refresh.state.value.loadingVod).isFalse()
    }

    @Test
    fun `a new provider says the guide is building only while it is`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("xmltv.php")

        withContext(Dispatchers.Default) {
            refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))
            during(hold) { assertThat(refresh.state.value.buildingGuide).isTrue() }
            hold.release()
            test.awaitRefresh()
        }

        assertThat(refresh.state.value.buildingGuide).isFalse()
    }

    @Test
    fun `however refreshes and guide passes overlap, nothing stays marked as running`() = runTest {
        db.sources().insert(PANEL_SOURCE)

        withContext(Dispatchers.Default) {
            repeat(5) {
                (1..64).map { i ->
                    async {
                        when (i % 4) {
                            0 -> refresh.refresh(CatalogueRefresh.Trigger.Periodic)
                            1 -> openMoviesOrShows()
                            2 -> refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE))
                            else -> refresh.refresh(CatalogueRefresh.Trigger.Manual)
                        }
                    }
                }.forEach { it.await() }
                test.awaitRefresh()
            }
        }

        val state = refresh.state.value
        assertThat(listOf(state.refreshing, state.loadingVod, state.buildingGuide)).containsExactly(false, false, false)
    }

    @Test
    fun `a refresh finishes and publishes its result in one step, so a repeated result is still news`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val finished = mutableListOf<CatalogueRefresh.State>()
        val watching = launch(Dispatchers.Unconfined) {
            var wasRefreshing = false
            refresh.state.collect {
                if (wasRefreshing && !it.refreshing) finished += it
                wasRefreshing = it.refreshing
            }
        }

        refresh.refresh(CatalogueRefresh.Trigger.Manual)
        val first = refresh.state.value.last
        refresh.refresh(CatalogueRefresh.Trigger.Manual)
        watching.cancel()

        assertThat(finished).hasSize(2)
        assertThat(finished[0].last).isNotNull()
        assertThat(finished[1].last).isEqualTo(first)
        assertThat(finished[1].last).isNotSameInstanceAs(first)
    }

    @Test
    fun `a provider another refresh fetched while it waited its turn isn't downloaded again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(PANEL_SOURCE.copy(id = 2, name = "Second"))
        val hold = panel.hold("get_vod_streams")

        val outcome = withContext(Dispatchers.Default) {
            val opened = async { openMoviesOrShows() }
            during(hold) { test.settings.setVodFetchedAtMillis(2, now) }
            hold.release()
            opened.await()
        }

        assertThat(vodDownloads()).isEqualTo(1)
        assertThat(outcome).isEqualTo(CatalogueRefresh.Outcome.VodChecked(fetched = 1))
    }

    @Test
    fun `the same login shares a download even after the provider's row has moved on`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val first = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            val second = during(hold) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
            hold.release()
            first.await()
            second.await()
        }

        assertThat(db.sources().enabled().single().lastCatalogSyncMillis).isEqualTo(now)
        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a provider that returned no titles at all isn't remembered as fresh`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        panel.replies["get_vod_streams"] = "[]"
        openMoviesOrShows()
        forgetAsked()

        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a manual refresh during another's guide download doesn't download that guide again`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("xmltv.php")

        withContext(Dispatchers.Default) {
            val periodic = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            val manual = during(hold) {
                async { refresh.refresh(CatalogueRefresh.Trigger.Manual) }.also { Thread.sleep(JOIN_MILLIS) }
            }
            hold.release()
            periodic.await()
            manual.await()
        }

        assertThat(panel.asked.count { it == "xmltv.php" }).isEqualTo(1)
    }

    @Test
    fun `the periodic refresh still waits for New Episodes when it joins a download Movies started`() = runTest {
        caughtUpOnAnUpdatedShow()
        val channels = panel.hold("get_live_streams")
        val episodes = panel.hold("get_series_info:7")

        withContext(Dispatchers.Default) {
            val periodic = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
            val opened = during(channels) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
            channels.release()
            during(episodes) { Thread.sleep(JOIN_MILLIS) }
            assertThat(periodic.isCompleted).isFalse()
            episodes.release()
            periodic.await()
            opened.await()
        }
    }

    @Test
    fun `opening Movies doesn't wait for New Episodes when it joins a download the periodic refresh started`() =
        runTest {
            caughtUpOnAnUpdatedShow()
            val titles = panel.hold("get_vod_streams")
            val episodes = panel.hold("get_series_info:7")

            withContext(Dispatchers.Default) {
                val periodic = async { refresh.refresh(CatalogueRefresh.Trigger.Periodic) }
                val opened = during(titles) { async { openMoviesOrShows() }.also { Thread.sleep(JOIN_MILLIS) } }
                titles.release()
                during(episodes) { Thread.sleep(JOIN_MILLIS) }
                assertThat(opened.isCompleted).isTrue()
                episodes.release()
                periodic.await()
            }
        }

    @Test
    fun `New Episode detection keeps the provider's turn, so nothing else downloads from it meanwhile`() = runTest {
        caughtUpOnAnUpdatedShow()
        val episodes = panel.hold("get_series_info:7")

        withContext(Dispatchers.Default) {
            openMoviesOrShows()
            val added = during(episodes) {
                panel.asked.clear()
                async { refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE)) }
                    .also { Thread.sleep(JOIN_MILLIS) }
            }
            assertThat(panel.asked).doesNotContain("get_live_streams")
            episodes.release()
            added.await()
        }
    }

    private companion object {
        const val RESCAN = "rescan series rules"
        const val TWELVE_HOURS = 12L * 60 * 60 * 1000

        /** Long enough for a second request to have reached the module before the first is let go. */
        const val JOIN_MILLIS = 500L
        val GUIDE = """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent()
    }
}
