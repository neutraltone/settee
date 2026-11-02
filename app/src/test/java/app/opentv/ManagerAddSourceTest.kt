/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import app.opentv.pairing.ManagerServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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

/** Adding a provider from a phone does what adding one on the TV does (#106). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManagerAddSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context, clock = { 1_000_000_000L })
    private val panel = test.panel
    private val scope = CoroutineScope(Dispatchers.Default)
    private val server = ManagerServer(
        scope,
        test.catalog,
        test.settings,
        SourceRepository(test.db.sources(), XtreamApi(panel.http), StalkerApi(panel.http)),
        test.lineup,
        test.refresh { panel.asked += RESCAN },
    )

    @After
    fun close() {
        server.stop()
        scope.cancel()
        test.close()
    }

    private fun addProvider(): String {
        server.start()
        val session = (server.state.value as? ManagerServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        val add = Request.Builder()
            .url(session!!.url.replace("/?", "/source?"))
            .post(
                """{"kind":"xtream","name":"Test","url":"http://example.com","username":"u","password":"p"}"""
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return OkHttpClient().newCall(add).execute().use { it.body.string() }
    }

    @Test
    fun `a provider added from a phone gets its channels at once, then its guide and series rules`() = runBlocking {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""
        panel.replies["get_live_streams"] = """[{"stream_id": 1, "name": "BBC One"}]"""
        panel.replies["xmltv.php"] = """
            <tv><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="bbc1">
            <title>News</title></programme></tv>
        """.trimIndent()

        val reply = addProvider()
        test.awaitRefresh()

        assertThat(reply).contains("\"ok\":true")
        assertThat(reply).contains("\"channels\":1")
        assertThat(panel.asked).containsExactly(
            "",
            "get_live_categories",
            "get_live_streams",
            "xmltv.php",
            RESCAN,
        ).inOrder()
    }

    @Test
    fun `a provider whose channels won't load from a phone says why and goes no further`() = runBlocking {
        panel.replies[""] = """{"user_info": {"status": "Active"}}"""

        val reply = addProvider()
        test.awaitRefresh()

        assertThat(reply).contains("\"ok\":false")
        assertThat(reply).contains("no channels")
        assertThat(panel.asked).doesNotContain("xmltv.php")
    }

    private companion object {
        const val RESCAN = "rescan series rules"
    }
}
