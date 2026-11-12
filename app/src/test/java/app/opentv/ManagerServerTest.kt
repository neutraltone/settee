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
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a phone or laptop reaches at the addresses Manage on phone or laptop shows. How those
 * addresses refuse wrong ones is [app.opentv.pairing.PhoneAddress]'s, tested in PhoneAddressTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManagerServerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val server = ManagerServer(
        scope,
        test.catalog,
        AppSettings.get(context),
        SourceRepository(test.db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        test.lineup,
        test.refresh(),
    )

    @After
    fun close() {
        server.stop()
        scope.cancel()
        test.close()
    }

    private fun listening(): ManagerServer.Session {
        server.start()
        val session = (server.state.value as? ManagerServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        return session!!
    }

    private fun get(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `the address shown on the TV is the same short kind setup shows`() {
        val session = listening()

        assertThat(session.shortUrl).matches("""[0-9.]+:[0-9]+/[a-z3-9]{7}""")
    }

    @Test
    fun `the typed address opens the manager, which can then reach the TV's data`() {
        val session = listening()

        val (code, page) = get("http://${session.shortUrl}")
        val token = Regex("""EMBEDDED_TOKEN = "([0-9a-f]+)"""").find(page)?.groupValues?.get(1)
        val (meta, _) = get("http://${session.host}:${session.port}/meta?t=$token")

        assertThat(code).isEqualTo(200)
        assertThat(meta).isEqualTo(200)
    }

    @Test
    fun `the QR code's address opens the manager`() {
        val session = listening()

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("EMBEDDED_TOKEN")
    }

    @Test
    fun `the TV's address and port alone are not enough`() {
        val session = listening()

        val (code, page) = get("http://${session.host}:${session.port}/")

        assertThat(code).isEqualTo(404)
        assertThat(page).isEqualTo("Not found.")
    }
}
