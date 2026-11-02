/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import app.opentv.pairing.ManagerServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A provider added from your phone behaves like one added on the TV (#106). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManagerAddSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val panel = test.panel
    private val serverScope = CoroutineScope(Dispatchers.Default)
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var rescans = 0
    private val server = ManagerServer(
        serverScope,
        test.catalog,
        AppSettings.get(context),
        SourceRepository(test.db.sources(), XtreamApi(panel.http), StalkerApi(panel.http)),
        test.lineup,
        // At time zero a guide that was never downloaded would look recent.
        test.catalogueRefresh(refreshScope, AppSettings.get(context), { 1_000_000_000L }, { rescans++ }),
    )

    @After
    fun close() {
        server.stop()
        serverScope.cancel()
        refreshScope.cancel()
        test.close()
    }

    private fun addProvider(): String {
        server.start()
        val session = (server.state.value as? ManagerServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        val request = Request.Builder()
            .url(session!!.url.replace("/?", "/source?"))
            .post(
                """{"kind":"xtream","name":"Test","url":"http://example.com","username":"u","password":"p"}"""
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return OkHttpClient().newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun `adding from your phone loads channels, then the guide and series rules, but no movies`() = runBlocking<Unit> {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "One"}]"""

        val reply = addProvider()
        refreshScope.coroutineContext.job.children.toList().joinAll()

        assertThat(reply).contains("\"ok\":true")
        assertThat(reply).contains("\"channels\":1")
        assertThat(panel.asked).contains(FakePanel.GUIDE)
        assertThat(panel.asked).doesNotContain("get_vod_streams")
        assertThat(rescans).isEqualTo(1)
    }

    @Test
    fun `a login that fails tells your phone why`() = runBlocking<Unit> {
        panel.replies[""] = """{"user_info": {"status": "Expired"}}"""

        val reply = addProvider()

        assertThat(reply).contains("\"ok\":false")
        assertThat(reply).contains("This account is Expired.")
    }
}
