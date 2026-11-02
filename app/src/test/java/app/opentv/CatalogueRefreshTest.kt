/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.Episode
import app.opentv.data.repo.RefreshStatus
import app.opentv.data.repo.RefreshTrigger
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What each trigger of a Catalogue Refresh fetches, and in what order. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueRefreshTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000_000L
    private val test = TestCatalog(context)
    private val db = test.db
    private val panel = test.panel
    private val settings = AppSettings.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rescan = "rescan series rules"

    private val refresh = test.catalogueRefresh(
        scope = scope,
        settings = settings,
        clock = { now },
        rescanSeriesRules = { synchronized(panel.asked) { panel.asked += rescan } },
    )

    private val live = listOf("", "get_live_categories", "get_live_streams")
    private val vod = listOf("get_vod_categories", "get_vod_streams", "get_series_categories", "get_series")

    @Before
    fun panelWithChannels() = runBlocking<Unit> {
        // AppSettings is a process-wide singleton, so one test's switches would reach the next.
        settings.setLiveEnabled(true)
        settings.setMoviesEnabled(true)
        settings.setSeriesEnabled(true)
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "One"}]"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
        db.sources().insert(PANEL_SOURCE)
    }

    @After
    fun close() {
        scope.cancel()
        test.close()
    }

    @Test
    fun `the six-hourly refresh fetches channels, movies and shows, then the guide, then rescans series rules`() =
        runBlocking<Unit> {
            val outcome = refresh.refresh(RefreshTrigger.Periodic)

            assertThat(panel.asked).containsExactlyElementsIn(live + vod + FakePanel.GUIDE + rescan).inOrder()
            assertThat(outcome.channels).isEqualTo(1)
            assertThat(outcome.problems).isEqualTo(0)
        }

    @Test
    fun `asking for a refresh fetches everything too, and downloads the guide even if it's recent`() =
        runBlocking<Unit> {
            refresh.refresh(RefreshTrigger.Periodic)
            panel.asked.clear()

            refresh.refresh(RefreshTrigger.Manual)

            assertThat(panel.asked).containsExactlyElementsIn(live + vod + FakePanel.GUIDE + rescan).inOrder()
        }

    @Test
    fun `the six-hourly refresh leaves a recent guide alone`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.Periodic)
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.Periodic)

        assertThat(panel.asked).containsExactlyElementsIn(live + vod + rescan).inOrder()
    }

    @Test
    fun `app launch only downloads a guide that isn't recent`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.AppLaunch)
        assertThat(panel.asked).containsExactly(FakePanel.GUIDE)
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.AppLaunch)

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `Guide settings downloads the guide even if it's recent, and fetches nothing else`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.AppLaunch)
        panel.asked.clear()

        val outcome = refresh.refresh(RefreshTrigger.GuideSettings)

        assertThat(panel.asked).containsExactly(FakePanel.GUIDE)
        assertThat(outcome.guide?.feedsSucceeded).isEqualTo(1)
    }

    @Test
    fun `opening Movies or Shows straight after a refresh downloads nothing`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.Periodic)
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `opening Movies or Shows downloads them the first time, then not again until they're 12 hours old`() =
        runBlocking<Unit> {
            refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
            assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
            panel.asked.clear()

            now += TWELVE_HOURS - 1
            refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
            assertThat(panel.asked).isEmpty()

            now += 1
            refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
            assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
        }

    @Test
    fun `a provider added since the last fetch has its movies downloaded, and only its movies`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        db.sources().insert(PANEL_SOURCE.copy(id = 2, name = "Second"))
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
        assertThat(db.movies().all().map { it.sourceId }).containsExactly(1L, 2L)
    }

    @Test
    fun `a panel that answers with no titles at all is asked again the next time they're opened`() = runBlocking<Unit> {
        panel.replies["get_vod_streams"] = "{}"
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
    }

    @Test
    fun `Movies and Shows only say they have something to download when they do`() = runBlocking<Unit> {
        assertThat(refresh.vodIsStale()).isTrue()

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        assertThat(refresh.vodIsStale()).isFalse()

        now += TWELVE_HOURS
        assertThat(refresh.vodIsStale()).isTrue()

        settings.setMoviesEnabled(false)
        settings.setSeriesEnabled(false)
        assertThat(refresh.vodIsStale()).isFalse()
    }

    @Test
    fun `with no providers a refresh downloads nothing, even a free guide`() = runBlocking<Unit> {
        db.sources().delete(PANEL_SOURCE.id)

        refresh.refresh(RefreshTrigger.Periodic)
        refresh.refresh(RefreshTrigger.Manual)

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `a download that failed is tried again the next time Movies or Shows is opened`() = runBlocking<Unit> {
        panel.replies["get_vod_streams"] = "!"
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        panel.asked.clear()

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
    }

    @Test
    fun `an account with no movies isn't a problem, so the six-hourly refresh doesn't retry over it`() =
        runBlocking<Unit> {
            panel.replies["get_vod_streams"] = "!"
            panel.replies["get_series"] = "!"

            val outcome = refresh.refresh(RefreshTrigger.Periodic)

            assertThat(outcome.channels).isEqualTo(1)
            assertThat(outcome.problems).isEqualTo(0)
        }

    @Test
    fun `a playlist or portal has no movies to download`() = runBlocking<Unit> {
        db.sources().delete(PANEL_SOURCE.id)
        db.sources().insert(STALKER_SOURCE)

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).isEmpty()
    }

    @Test
    fun `switching Shows on downloads again the next time Movies or Shows is opened`() = runBlocking<Unit> {
        settings.setSeriesEnabled(false)
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        panel.asked.clear()

        settings.setSeriesEnabled(true)
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
    }

    private suspend fun backgroundWorkDone() {
        scope.coroutineContext.job.children.toList().joinAll()
        test.catalog.awaitBackground()
    }

    @Test
    fun `adding a provider loads its channels straight away, then the guide and series rules, but no movies`() =
        runBlocking<Unit> {
            val guideReached = CountDownLatch(1)
            val finishGuide = CountDownLatch(1)
            panel.onRequest = { action ->
                if (action == FakePanel.GUIDE) {
                    guideReached.countDown()
                    finishGuide.await(5, TimeUnit.SECONDS)
                }
            }

            val outcome = refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE))

            // Returned with the guide still downloading, so the viewer has their channels now.
            assertThat(outcome.channels).isEqualTo(1)
            assertThat(guideReached.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(panel.asked).doesNotContain(rescan)
            finishGuide.countDown()
            backgroundWorkDone()
            assertThat(panel.asked).containsExactlyElementsIn(live + FakePanel.GUIDE + rescan).inOrder()
        }

    @Test
    fun `adding a provider with a bad login says why and goes no further`() = runBlocking<Unit> {
        panel.replies[""] = """{"user_info": {"status": "Expired"}}"""

        val outcome = refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE))
        backgroundWorkDone()

        assertThat(outcome.failure).isEqualTo("This account is Expired.")
        assertThat(panel.asked).containsExactly("")
    }

    @Test
    fun `adding a provider loads its channels even with the Live switch off`() = runBlocking<Unit> {
        settings.setLiveEnabled(false)

        val outcome = refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE))
        backgroundWorkDone()

        assertThat(outcome.channels).isEqualTo(1)
    }

    @Test
    fun `saving a provider again means its movies download the next time Movies or Shows is opened`() =
        runBlocking<Unit> {
            refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

            refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE))
            backgroundWorkDone()
            panel.asked.clear()
            refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

            assertThat(panel.asked).containsExactlyElementsIn(vod).inOrder()
        }

    @Test
    fun `with the Live switch off a refresh leaves channels alone`() = runBlocking<Unit> {
        settings.setLiveEnabled(false)

        refresh.refresh(RefreshTrigger.Periodic)
        refresh.refresh(RefreshTrigger.Manual)

        assertThat(panel.asked.filter { it in live }).isEmpty()
    }

    @Test
    fun `with the Movies switch off only shows are fetched, whatever asked`() = runBlocking<Unit> {
        settings.setMoviesEnabled(false)

        for (trigger in listOf(RefreshTrigger.MoviesOrShowsOpened, RefreshTrigger.Periodic, RefreshTrigger.Manual)) {
            refresh.refresh(trigger)
        }

        assertThat(panel.asked).containsNoneOf("get_vod_categories", "get_vod_streams")
        assertThat(panel.asked).contains("get_series")
    }

    @Test
    fun `with the Shows switch off only movies are fetched`() = runBlocking<Unit> {
        settings.setSeriesEnabled(false)

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)

        assertThat(panel.asked).containsExactly("get_vod_categories", "get_vod_streams").inOrder()
    }

    @Test
    fun `with Movies and Shows both off, opening them downloads nothing`() = runBlocking<Unit> {
        settings.setMoviesEnabled(false)
        settings.setSeriesEnabled(false)

        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        refresh.refresh(RefreshTrigger.Periodic)

        assertThat(panel.asked).containsNoneIn(vod)
    }

    /** Holds [action]'s download open until the returned latch counts down, once [reached] has. */
    private fun holdDownloadOf(action: String, reached: CountDownLatch): CountDownLatch {
        val release = CountDownLatch(1)
        panel.onRequest = {
            if (it == action) {
                reached.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        return release
    }

    @Test
    fun `two refreshes at once download once and share the result`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_live_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)

        val first = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        val second = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        delay(100)
        release.countDown()

        assertThat(first.await().channels).isEqualTo(1)
        assertThat(second.await().channels).isEqualTo(1)
        assertThat(
            panel.asked.filter {
                it != rescan
            },
        ).containsExactlyElementsIn(live + vod + FakePanel.GUIDE).inOrder()
    }

    @Test
    fun `a refresh arriving while Movies downloads waits for it, then fetches only the channels`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_vod_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)

        val opened = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        delay(100)
        release.countDown()
        opened.await()
        periodic.await()

        assertThat(panel.asked.count { it == "get_vod_streams" }).isEqualTo(1)
        assertThat(panel.asked.count { it == "get_live_streams" }).isEqualTo(1)
    }

    @Test
    fun `leaving the screen that asked doesn't stop a download someone else is waiting on`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_vod_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)

        val leaving = client.launch { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        val staying = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
        delay(100)
        leaving.cancelAndJoin()
        release.countDown()

        assertThat(staying.await().problems).isEqualTo(0)
        assertThat(settings.vodFetchedAtMillis(PANEL_SOURCE.id)).isEqualTo(now)
        assertThat(panel.asked.count { it == "get_vod_streams" }).isEqualTo(1)
    }

    /** A show listed once, then updated, with its episodes held open until the latch counts down. */
    private suspend fun showTheProviderUpdated(reached: CountDownLatch, trigger: RefreshTrigger): CountDownLatch {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "100"}]"""
        refresh.refresh(trigger)
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show", "last_modified": "200"}]"""
        panel.replies["get_series_info"] = """
            {"info": {}, "episodes": {"1": [{"id": "1", "episode_num": 1}, {"id": "2", "episode_num": 2}]}}
        """.trimIndent()
        now += TWELVE_HOURS
        return holdDownloadOf("get_series_info", reached)
    }

    private fun caughtUpOnAShow() = runBlocking<Unit> {
        db.episodes().upsertAll(
            listOf(
                Episode(
                    sourceId = PANEL_SOURCE.id,
                    seriesId = "7",
                    episodeId = "1",
                    season = 1,
                    episodeNumber = 1,
                    title = "Pilot",
                    plot = null,
                    durationSeconds = null,
                    stillUrl = null,
                    streamUrl = "http://example.com/series/u/p/1.mp4",
                ),
            ),
        )
        val pilot = db.episodes().forSeries(PANEL_SOURCE.id, "7").single()
        test.viewerLibrary.recordProgress("ep:${pilot.id}", positionMillis = 1_000, durationMillis = 1_000)
    }

    @Test
    fun `the six-hourly refresh waits for New Episode detection`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = showTheProviderUpdated(reached, RefreshTrigger.Periodic)
        caughtUpOnAShow()
        val client = CoroutineScope(Dispatchers.Default)

        val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        delay(100)
        assertThat(periodic.isCompleted).isFalse()

        release.countDown()
        periodic.await()
        assertThat(db.episodes().forSeries(PANEL_SOURCE.id, "7")).hasSize(2)
    }

    @Test
    fun `opening Movies or Shows doesn't wait for New Episode detection, but it still happens`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = showTheProviderUpdated(reached, RefreshTrigger.MoviesOrShowsOpened)
        caughtUpOnAShow()

        // Returns with the episodes still held open.
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(db.episodes().forSeries(PANEL_SOURCE.id, "7")).hasSize(1)

        release.countDown()
        backgroundWorkDone()
        assertThat(db.episodes().forSeries(PANEL_SOURCE.id, "7")).hasSize(2)
    }

    @Test
    fun `a refresh the viewer asked for shows it's running, then how it went`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_live_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)

        val manual = client.async { refresh.refresh(RefreshTrigger.Manual) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(refresh.status.value).isEqualTo(RefreshStatus.Refreshing)
        release.countDown()
        manual.await()

        assertThat(refresh.status.value).isEqualTo(RefreshStatus.Refreshed(channels = 1, problems = 0, 0, 1))
    }

    @Test
    fun `a refresh the viewer asked for finishes even if whatever asked for it stops`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_live_streams", reached)
        val worker = CoroutineScope(Dispatchers.Default).launch { refresh.refresh(RefreshTrigger.Manual) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

        worker.cancelAndJoin()
        release.countDown()
        backgroundWorkDone()

        assertThat(refresh.status.value).isEqualTo(RefreshStatus.Refreshed(channels = 1, problems = 0, 0, 1))
    }

    @Test
    fun `adding a provider shows channels loading, then the guide building, then the guide ready`() =
        runBlocking<Unit> {
            val reachedLive = CountDownLatch(1)
            val releaseLive = holdDownloadOf("get_live_streams", reachedLive)
            val client = CoroutineScope(Dispatchers.Default)

            val adding = client.async { refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE)) }
            assertThat(reachedLive.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(refresh.status.value).isEqualTo(RefreshStatus.LoadingChannels)

            val reachedGuide = CountDownLatch(1)
            val releaseGuide = holdDownloadOf(FakePanel.GUIDE, reachedGuide)
            releaseLive.countDown()
            adding.await()
            assertThat(refresh.status.value).isEqualTo(RefreshStatus.BuildingGuide(channels = 1))

            releaseGuide.countDown()
            backgroundWorkDone()
            assertThat(
                refresh.status.value,
            ).isEqualTo(RefreshStatus.ProviderReady(channelsMatched = 0, channelsTotal = 1))
        }

    @Test
    fun `a provider that won't load shows why`() = runBlocking<Unit> {
        panel.replies[""] = """{"user_info": {"status": "Expired"}}"""

        refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE))

        assertThat(refresh.status.value).isEqualTo(RefreshStatus.ProviderFailed("This account is Expired."))
    }

    @Test
    fun `refreshes nobody asked for don't show on screen`() = runBlocking<Unit> {
        refresh.refresh(RefreshTrigger.Periodic)
        refresh.refresh(RefreshTrigger.MoviesOrShowsOpened)
        refresh.refresh(RefreshTrigger.AppLaunch)

        assertThat(refresh.status.value).isEqualTo(RefreshStatus.Idle)
    }

    @Test
    fun `a provider added while a guide is downloading still gets its own guide`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf(FakePanel.GUIDE, reached)
        val launch = CoroutineScope(Dispatchers.Default).async { refresh.refresh(RefreshTrigger.AppLaunch) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

        val second = PANEL_SOURCE.copy(id = 2, name = "Second")
        db.sources().insert(second)
        refresh.refresh(RefreshTrigger.ProviderAdded(second))
        release.countDown()
        launch.await()
        backgroundWorkDone()

        assertThat(panel.asked.count { it == FakePanel.GUIDE }).isEqualTo(2)
    }

    @Test
    fun `Guide settings asking mid-download downloads again, so a feed just turned on is included`() =
        runBlocking<Unit> {
            val reached = CountDownLatch(1)
            val release = holdDownloadOf(FakePanel.GUIDE, reached)
            val client = CoroutineScope(Dispatchers.Default)
            val manual = client.async { refresh.refresh(RefreshTrigger.Manual) }
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

            val settingsAsked = client.async { refresh.refresh(RefreshTrigger.GuideSettings) }
            delay(100)
            release.countDown()
            manual.await()
            settingsAsked.await()

            assertThat(panel.asked.count { it == FakePanel.GUIDE }).isEqualTo(2)
        }

    @Test
    fun `a provider saved with new details doesn't share a download using the old ones`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_live_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)
        val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

        val saving = client.async {
            refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE.copy(password = "new")))
        }
        delay(100)
        release.countDown()
        saving.await()
        periodic.await()

        assertThat(panel.asked.count { it == "get_live_streams" }).isEqualTo(2)
    }

    @Test
    fun `a download finishing with the old details doesn't make the new account's movies look fresh`() =
        runBlocking<Unit> {
            val reached = CountDownLatch(1)
            val release = holdDownloadOf("get_vod_streams", reached)
            val client = CoroutineScope(Dispatchers.Default)
            val opened = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
            assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

            val saving = client.async {
                refresh.refresh(RefreshTrigger.ProviderAdded(PANEL_SOURCE.copy(password = "new")))
            }
            delay(100)
            release.countDown()
            opened.await()
            saving.await()
            backgroundWorkDone()

            assertThat(settings.vodFetchedAtMillis(PANEL_SOURCE.id)).isEqualTo(0L)
        }

    @Test
    fun `a provider's last-refreshed time changing doesn't stop two requests sharing a download`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = holdDownloadOf("get_vod_streams", reached)
        val client = CoroutineScope(Dispatchers.Default)
        // By the time it reaches movies, channels have loaded and moved the provider's stored time.
        val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
        assertThat(reached.await(5, TimeUnit.SECONDS)).isTrue()

        val opened = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
        delay(100)
        release.countDown()
        periodic.await()
        opened.await()

        assertThat(panel.asked.count { it == "get_vod_streams" }).isEqualTo(1)
    }

    /** Holds the show list open until the first latch counts down, then its episodes until the second. */
    private fun holdShowsThenEpisodes(listReached: CountDownLatch): Pair<CountDownLatch, CountDownLatch> {
        val releaseList = CountDownLatch(1)
        val releaseEpisodes = CountDownLatch(1)
        panel.onRequest = {
            if (it == "get_series") {
                listReached.countDown()
                releaseList.await(5, TimeUnit.SECONDS)
            }
            if (it == "get_series_info") releaseEpisodes.await(5, TimeUnit.SECONDS)
        }
        return releaseList to releaseEpisodes
    }

    @Test
    fun `the six-hourly refresh still waits for New Episodes when it joins a download Movies started`() =
        runBlocking<Unit> {
            showTheProviderUpdated(CountDownLatch(1), RefreshTrigger.MoviesOrShowsOpened)
            caughtUpOnAShow()
            val listReached = CountDownLatch(1)
            val (releaseList, releaseEpisodes) = holdShowsThenEpisodes(listReached)
            val client = CoroutineScope(Dispatchers.Default)
            val opened = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
            assertThat(listReached.await(5, TimeUnit.SECONDS)).isTrue()

            val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
            delay(100)
            releaseList.countDown()
            opened.await()
            assertThat(withTimeoutOrNull(1_000) { periodic.await() }).isNull()

            releaseEpisodes.countDown()
            periodic.await()
            assertThat(db.episodes().forSeries(PANEL_SOURCE.id, "7")).hasSize(2)
        }

    @Test
    fun `opening Movies doesn't wait for New Episodes when it joins a download the worker started`() =
        runBlocking<Unit> {
            showTheProviderUpdated(CountDownLatch(1), RefreshTrigger.Periodic)
            caughtUpOnAShow()
            val listReached = CountDownLatch(1)
            val (releaseList, releaseEpisodes) = holdShowsThenEpisodes(listReached)
            val client = CoroutineScope(Dispatchers.Default)
            val periodic = client.async { refresh.refresh(RefreshTrigger.Periodic) }
            assertThat(listReached.await(5, TimeUnit.SECONDS)).isTrue()

            val opened = client.async { refresh.refresh(RefreshTrigger.MoviesOrShowsOpened) }
            delay(100)
            releaseList.countDown()
            assertThat(withTimeoutOrNull(1_000) { opened.await() }).isNotNull()

            releaseEpisodes.countDown()
            periodic.await()
        }

    private companion object {
        const val TWELVE_HOURS = 12L * 60 * 60 * 1000
    }
}
