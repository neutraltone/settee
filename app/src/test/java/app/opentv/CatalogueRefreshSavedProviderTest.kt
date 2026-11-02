/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.repo.CatalogueRefresh
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Saving a provider while a Catalogue Refresh is under way, and what that does to its record of fresh titles. */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueRefreshSavedProviderTest {

    private var now = 1_000_000_000L
    private val test = TestCatalog(ApplicationProvider.getApplicationContext(), clock = { now })
    private val db = test.db
    private val panel = test.panel
    private val refresh = test.refresh { panel.asked += RESCAN }

    init {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive", "added": "100"}]"""
    }

    @After
    fun close() {
        // AppSettings is a process-wide singleton, so what one test sets would reach the next.
        test.settings.forgetDatabaseRows()
        test.close()
    }

    private fun vodDownloads() = panel.asked.count { it == "get_vod_streams" }

    private suspend fun openMoviesOrShows() = refresh.refresh(CatalogueRefresh.Trigger.OpenedMoviesOrShows)

    @Test
    fun `adding a provider still gets its guide and series rules if whatever asked stops waiting`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_live_streams")

        withContext(Dispatchers.Default) {
            val adding = async { refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE)) }
            during(hold) { adding.cancel() }
            hold.release()
            adding.join()
        }
        test.awaitRefresh()

        assertThat(panel.asked).containsAtLeast("xmltv.php", RESCAN).inOrder()
    }

    @Test
    fun `a download that read a provider's old login doesn't make the new account's titles look fresh`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(PANEL_SOURCE.copy(id = 2, name = "Second"))
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val opened = async { openMoviesOrShows() }
            during(hold) {
                val edited = PANEL_SOURCE.copy(id = 2, name = "Second", password = "new")
                db.sources().update(edited)
                refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(edited))
            }
            hold.release()
            opened.await()
        }
        panel.asked.clear()

        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `saving a provider with a login that fails still fetches its titles once it works`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        val channels = panel.replies.getValue("get_live_streams")
        panel.replies["get_live_streams"] = "[]"
        refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE.copy(password = "new")))
        panel.replies["get_live_streams"] = channels
        panel.asked.clear()

        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `a provider saved with a new login isn't downloaded with the old one when its turn comes`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        db.sources().insert(PANEL_SOURCE.copy(id = 2, name = "Second"))
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val opened = async { openMoviesOrShows() }
            during(hold) { db.sources().update(PANEL_SOURCE.copy(id = 2, name = "Second", password = "new")) }
            hold.release()
            opened.await()
        }

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `changing a provider's live format mid-download still remembers its titles as fresh`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_vod_streams")

        withContext(Dispatchers.Default) {
            val opened = async { openMoviesOrShows() }
            during(hold) { test.catalog.setLiveFormat(PANEL_SOURCE.id, LiveStreamFormat.MPEG_TS) }
            hold.release()
            opened.await()
        }
        panel.asked.clear()

        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(0)
    }

    @Test
    fun `saving a provider again fetches its titles the next time they're opened`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        openMoviesOrShows()
        panel.asked.clear()

        refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE.copy(username = "other")))
        openMoviesOrShows()

        assertThat(vodDownloads()).isEqualTo(1)
    }

    @Test
    fun `changed login details don't join a download running with the old ones`() = runTest {
        db.sources().insert(PANEL_SOURCE)
        val hold = panel.hold("get_live_streams")

        withContext(Dispatchers.Default) {
            val old = async { refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE)) }
            val edited = during(hold) {
                async {
                    refresh.refresh(CatalogueRefresh.Trigger.ProviderAdded(PANEL_SOURCE.copy(password = "new")))
                }.also { Thread.sleep(JOIN_MILLIS) }
            }
            hold.release()
            old.await()
            edited.await()
        }

        assertThat(panel.asked.count { it == "get_live_streams" }).isEqualTo(2)
    }

    private companion object {
        const val RESCAN = "rescan series rules"
        const val JOIN_MILLIS = 500L
    }
}
