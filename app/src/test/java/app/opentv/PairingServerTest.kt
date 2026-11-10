/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.pairing.PairingServer
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

/** What a phone reaches at the addresses the "Set up from your phone" screen shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairingServerTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val server = PairingServer(scope)

    @After
    fun close() {
        server.stop()
        scope.cancel()
    }

    private fun listening(): PairingServer.Session {
        server.start()
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        return session!!
    }

    private fun get(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `the QR code's address opens the form`() {
        val session = listening()

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("<form")
    }

    @Test
    fun `the address typed from the screen without the QR's token is refused`() {
        val session = listening()

        val (code, page) = get("http://${session.shortUrl}/")

        assertThat(code).isEqualTo(404)
        assertThat(page).isEqualTo("Not found.")
    }
}
