/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.pairing.PairingFonts
import app.opentv.pairing.PairingServer
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    private val server = PairingServer(scope, PairingFonts(ApplicationProvider.getApplicationContext()))

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
    fun `the address typed from the screen opens the form`() {
        val session = listening()

        val (code, page) = get("http://${session.shortUrl}")

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("<form")
    }

    @Test
    fun `the typed address still opens the form if the phone capitalises it or adds a slash`() {
        val session = listening()

        val (code, _) = get("http://${session.shortUrl.uppercase()}/")

        assertThat(code).isEqualTo(200)
    }

    @Test
    fun `the TV's address and port alone are not enough`() {
        val session = listening()

        val (code, page) = get("http://${session.host}:${session.port}/")

        assertThat(code).isEqualTo(404)
        assertThat(page).isEqualTo("Not found.")
    }

    @Test
    fun `a guessed short code is refused`() {
        val session = listening()
        val (code, _) = get("http://${session.host}:${session.port}/${wrongShortToken(session)}")

        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `the typed address is short enough to read off a TV`() {
        val session = listening()

        assertThat(session.shortUrl).matches("""[0-9.]+:[0-9]+/[a-z3-9]{7}""")
    }

    @Test
    fun `the typed address never uses characters that look alike across a room`() {
        // Many sessions, since one token is only seven of the characters it could have used.
        val tokens = (1..30).joinToString("") {
            server.stop()
            listening().shortToken
        }

        // One of each look-alike group is dropped: 0/o, 1/l/i, 2/z, 5/s, 6/b, 9/g/q, u/v/y.
        assertThat(tokens).doesNotContainMatch("""[01ilo2z5sbgquvy]""")
    }

    @Test
    fun `a mistyped address tells the viewer to check it against the TV`() {
        val session = listening()

        val (code, page) = get("http://${session.host}:${session.port}/${wrongShortToken(session)}")

        assertThat(code).isEqualTo(404)
        assertThat(page).contains("Check the address")
    }

    @Test
    fun `after too many wrong addresses the typed address stops working but the QR code still does`() {
        val session = listening()
        repeat(PairingServer.MAX_SHORT_TOKEN_GUESSES) {
            get("http://${session.host}:${session.port}/${wrongShortToken(session)}")
        }

        val (typed, page) = get("http://${session.shortUrl}")
        val (scanned, _) = get(session.url)

        assertThat(typed).isEqualTo(404)
        assertThat(page).contains("scan the QR code")
        assertThat(scanned).isEqualTo(200)
    }

    @Test
    fun `the form fetches Settee's own fonts from the TV and asks nothing of the outside world`() {
        val session = listening()
        val (_, page) = get(session.url)

        val fetched = Regex("""(?:src|href|action)\s*=\s*"([^"]*)"|url\(\s*['"]?([^'")\s]+)""")
            .findAll(page)
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            .toList()
        assertThat(fetched.filter { it.startsWith("//") || it.contains("://") }).isEmpty()

        val fonts = fetched.filter { it.contains(".ttf") }
        assertThat(fonts).hasSize(3)
        val bundled = File("src/main/res/font").listFiles()!!.map { it.readBytes().toList() }
        fonts.forEach { path ->
            val response = OkHttpClient().newCall(
                Request.Builder().url(session.url.toHttpUrl().resolve(path)!!).build(),
            ).execute()
            response.use {
                assertThat(it.code).isEqualTo(200)
                assertThat(it.header("Content-Type")).startsWith("font/ttf")
                assertThat(bundled).contains(it.body.bytes().toList())
            }
        }
    }

    @Test
    fun `a font asked for without the token is not served`() {
        val session = listening()

        val (code, _) = get("http://${session.host}:${session.port}/fonts/young-serif.ttf")

        assertThat(code).isEqualTo(404)
    }

    @Test
    fun `the form says Settee, in Settee's colours`() {
        val session = listening()

        val (_, page) = get(session.url)

        assertThat(page).contains("Settee")
        assertThat(page.lowercase()).doesNotContain("opentv")
        // docs/LOOK.md's background, surface, text and coral.
        listOf("#1a1416", "#281f22", "#f8efea", "#ff8f6b").forEach { assertThat(page.lowercase()).contains(it) }
    }

    private fun wrongShortToken(session: PairingServer.Session) =
        session.shortToken.map { if (it == '3') '4' else '3' }.joinToString("")
}
