/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.pairing.PairingFonts
import app.opentv.pairing.PairingServer
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a phone reaches at the addresses the "Set up from your phone" screen shows. How those
 * addresses refuse wrong ones is [app.opentv.pairing.PhoneAddress]'s, tested in PhoneAddressTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairingServerTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val server = PairingServer(scope, PairingFonts(context), context::getString)

    @After
    fun close() {
        server.stop()
        scope.cancel()
    }

    private fun listening(mode: PairingServer.Mode = PairingServer.Mode.Provider()): PairingServer.Session {
        server.start(mode)
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        return session!!
    }

    private fun get(url: String): Pair<Int, String> {
        val request = Request.Builder().url(url).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    /** Sends a playlist from the phone's form, as the viewer would. */
    private fun submit(session: PairingServer.Session): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("code", session.confirmCode)
            .add("kind", "m3u")
            .add("url", "http://example.com/list.m3u")
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/submit").post(form).build()
        return OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { it.code to it.body.string() }
    }

    /** The details the TV got, once it has them. */
    private fun received(): PairingServer.State.Received {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            (server.state.value as? PairingServer.State.Received)?.let { return it }
            Thread.sleep(20)
        }
        error("the TV never got the details")
    }

    @Test
    fun `the phone waits until the channels are in, then says it's all done`() {
        val session = listening()

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        val got = received()
        assertThat(reply.isCompleted).isFalse()
        got.answer(error = null)

        val (_, page) = runBlocking { reply.await() }
        assertThat(page).contains("All done")
        assertThat(page).contains("Carry on on your TV.")
    }

    @Test
    fun `a phone waiting when the TV leaves pairing is told at once, not left hanging`() {
        val session = listening()

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received()
        val started = System.currentTimeMillis()
        server.stop()

        val (_, page) = runBlocking { reply.await() }
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000L)
        assertThat(page).contains("Setup stopped on your TV")
    }

    @Test
    fun `a login that fails shows why on the phone, which can try again`() {
        val session = listening()

        val first = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = "Wrong username or password.")
        val (_, page) = runBlocking { first.await() }

        assertThat(page).contains("<form")
        assertThat(page).contains("Wrong username or password.")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        val second = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        assertThat(runBlocking { second.await() }.second).contains("All done")
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

    // ---- The TMDB Key --------------------------------------------------------------------------

    /** [page]'s text as the phone shows it: the page escapes what it puts in, apostrophes included. */
    private fun shown(page: String) = page.replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** What the phone says once the TV has saved its key. */
    private val saved get() = context.getString(R.string.pairing_phone_tmdb_saved_title)

    /** Sends a TMDB Key from the phone's paste box, as the viewer would. */
    private fun sendKey(session: PairingServer.Session, key: String = KEY): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("key", key)
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/key").post(form).build()
        return OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { it.code to it.body.string() }
    }

    /** The TMDB Key the TV got, once it has it. */
    private fun receivedKey(): PairingServer.State.KeyReceived {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            (server.state.value as? PairingServer.State.KeyReceived)?.let { return it }
            Thread.sleep(20)
        }
        error("the TV never got the key")
    }

    @Test
    fun `for a TMDB Key the QR code opens a paste box, with how to get a key under it`() {
        val session = listening(PairingServer.Mode.TmdbKey())

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("action=\"/key\"")
        assertThat(page).contains("https://www.themoviedb.org/signup")
        assertThat(page).contains("https://www.themoviedb.org/settings/api")
        assertThat(page).contains("Settee")
        // The paste box comes first, the steps under it.
        assertThat(page.indexOf("name=\"key\"")).isLessThan(page.indexOf("themoviedb.org/signup"))
    }

    @Test
    fun `a TMDB Key pasted on the phone reaches the TV, and the phone hears it was saved`() {
        val session = listening(PairingServer.Mode.TmdbKey())

        val reply = scope.async(Dispatchers.IO) { sendKey(session) }
        val got = receivedKey()
        assertThat(got.key).isEqualTo(KEY)
        assertThat(reply.isCompleted).isFalse()
        got.answer(problem = null)

        val (_, page) = runBlocking { reply.await() }
        assertThat(page).contains(saved)
    }

    @Test
    fun `a TMDB Key TMDB turns down shows why on the phone, which can paste another`() {
        val session = listening(PairingServer.Mode.TmdbKey())

        val first = scope.async(Dispatchers.IO) { sendKey(session) }
        receivedKey().answer(ViewerError("TMDB didn't accept that key.", "KeyRejected · HTTP 401"))
        val (_, page) = runBlocking { first.await() }

        assertThat(shown(page)).contains("TMDB didn't accept that key.")
        assertThat(page).contains("KeyRejected · HTTP 401")
        assertThat(page).contains("action=\"/key\"")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        val second = scope.async(Dispatchers.IO) { sendKey(session) }
        receivedKey().answer(problem = null)
        assertThat(runBlocking { second.await() }.second).contains(saved)
    }

    @Test
    fun `once the TV leaves, a key it was sent is forgotten, so the next screen isn't handed it again`() {
        val session = listening(PairingServer.Mode.TmdbKey())
        val reply = scope.async(Dispatchers.IO) { sendKey(session) }
        receivedKey().answer(problem = null)
        runBlocking { reply.await() }

        server.stop()

        assertThat(server.state.value).isEqualTo(PairingServer.State.Idle)
    }

    @Test
    fun `a blank TMDB Key is asked for again without troubling the TV`() {
        val session = listening(PairingServer.Mode.TmdbKey())

        val (_, page) = sendKey(session, key = "   ")

        assertThat(page).contains("action=\"/key\"")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `a TMDB Key is not taken while the phone is adding a provider`() {
        val session = listening(PairingServer.Mode.Provider())

        sendKey(session)

        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `after the first provider, the phone carries on to the TMDB Key`() {
        val session = listening(PairingServer.Mode.Provider(thenTmdbKey = true))

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        val (_, page) = runBlocking { reply.await() }

        assertThat(page).contains("action=\"/key\"")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        val key = scope.async(Dispatchers.IO) { sendKey(session) }
        receivedKey().answer(problem = null)
        assertThat(runBlocking { key.await() }.second).contains(saved)
    }

    @Test
    fun `the phone's TMDB step says the key is optional and can be added later`() {
        val session = listening(PairingServer.Mode.Provider(thenTmdbKey = true))

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        val (_, page) = runBlocking { reply.await() }

        assertThat(shown(page)).contains(context.getString(R.string.onboarding_tmdb_help))
        assertThat(shown(page)).contains(context.getString(R.string.pairing_phone_tmdb_how))
    }

    /** Presses the Onboarding TMDB page's Skip for now, as the viewer would. */
    private fun skipKey(session: PairingServer.Session): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder().add("t", session.url.substringAfter("t=")).build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/skip").post(form).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `Onboarding's TMDB page offers Skip for now, which tells the TV and the phone`() {
        val session = listening(PairingServer.Mode.TmdbKey(onboarding = true))
        assertThat(get(session.url).second).contains("action=\"/skip\"")

        val (_, page) = skipKey(session)

        assertThat(server.state.value).isEqualTo(PairingServer.State.KeySkipped)
        assertThat(shown(page)).contains(context.getString(R.string.pairing_phone_tmdb_skipped_title))
    }

    @Test
    fun `Settings' TMDB page has no Skip, and a skip sent anyway is ignored`() {
        val session = listening(PairingServer.Mode.TmdbKey(onboarding = false))
        assertThat(get(session.url).second).doesNotContain("action=\"/skip\"")

        skipKey(session)

        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    private companion object {
        /** A placeholder, low-entropy so gitleaks sees no secret. */
        val KEY = "d".repeat(32)
    }
}
