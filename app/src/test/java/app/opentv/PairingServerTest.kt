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
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    private val server = PairingServer(scope, PairingFonts(context), resources = { context.resources })

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

    /** Sends a playlist from the phone's form, as the viewer would: with [code] typed, at [url]. */
    private fun submit(
        session: PairingServer.Session,
        code: String = session.confirmCode,
        url: String = "http://example.com/list.m3u",
    ): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("code", code)
            .add("kind", "m3u")
            .add("url", url)
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/submit").post(form).build()
        return OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `a session that times out goes straight to saying so, never through Idle`() {
        // Idle is what the phone screen takes as another screen stopping the server, and listens again.
        val timing =
            PairingServer(scope, PairingFonts(context), resources = { context.resources }, sessionTimeoutMillis = 200)
        val seen = java.util.Collections.synchronizedList(mutableListOf<PairingServer.State>())
        val watching = scope.launch(Dispatchers.Unconfined) { timing.state.collect { seen += it } }
        timing.start()
        assumeTrue("needs a private LAN address to serve on", timing.state.value is PairingServer.State.Listening)

        val deadline = System.currentTimeMillis() + 5_000
        while (timing.state.value !is PairingServer.State.Failed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        watching.cancel()
        timing.stop()

        val after = seen.dropWhile { it !is PairingServer.State.Listening }
        assertThat(after.last()).isEqualTo(PairingServer.State.Failed(app.opentv.core.ServerProblem.TIMED_OUT))
        assertThat(after).doesNotContain(PairingServer.State.Idle)
    }

    @Test
    fun `a form cut off partway is refused, so the TV never saves half an address`() {
        val session = listening()
        val form = "t=${session.url.substringAfter("t=")}&code=${session.confirmCode}&kind=m3u&url=http%3A%2F%2Fexam"

        val status = Socket(session.host, session.port).use { socket ->
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            out.write(
                (
                    "POST /submit HTTP/1.1\r\nHost: ${session.host}\r\n" +
                        "Content-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: ${form.length + 30}\r\nConnection: close\r\n\r\n$form"
                    ).toByteArray(Charsets.UTF_8),
            )
            out.flush()
            // The phone stops sending, as one that has dropped off the wifi does.
            socket.shutdownOutput()
            socket.getInputStream().bufferedReader().readLine()
        }

        assertThat(status).isEqualTo("HTTP/1.1 400 Bad Request")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
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
    fun `a login that fails shows why on the phone, with its detail line, and it can try again`() {
        val session = listening()

        val first = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(ViewerError("Wrong username or password.", "LoginRejected · HTTP 401"))
        val (_, page) = runBlocking { first.await() }

        assertThat(page).contains("<form")
        assertThat(page).contains("Wrong username or password.")
        assertThat(page).contains("LoginRejected · HTTP 401")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        val second = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        assertThat(runBlocking { second.await() }.second).contains("All done")
    }

    @Test
    fun `a form sent without an address asks for one, in the app's words`() {
        val session = listening()

        val (_, page) = submit(session, url = "")

        assertThat(shown(page)).contains(context.getString(R.string.pairing_phone_missing_address))
    }

    @Test
    fun `a wrong code says how many tries are left, and too many ends pairing, in the app's words`() {
        val session = listening()
        val wrong = if (session.confirmCode == "000000") "111111" else "000000"

        val (_, first) = submit(session, code = wrong)
        val (_, last) = (2..5).map { submit(session, code = wrong) }.last()

        assertThat(shown(first)).contains(context.resources.getQuantityString(R.plurals.pairing_phone_wrong_code, 4, 4))
        assertThat(last).contains(context.getString(R.string.pairing_phone_too_many_title))
        assertThat(last).contains(context.getString(R.string.pairing_phone_too_many_body))
    }

    @Test
    fun `the QR code's address opens the form`() {
        val session = listening()

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("<form")
    }

    @Test
    fun `the provider form's words come from the app's strings, both kinds' labels included`() {
        val session = listening()

        val page = shown(get(session.url).second)

        listOf(
            R.string.pairing_phone_form_title,
            R.string.pairing_phone_form_body,
            R.string.onboarding_xtream_login,
            R.string.onboarding_m3u_url,
            R.string.onboarding_server_address,
            R.string.onboarding_playlist_url,
            R.string.onboarding_playlist_help,
            R.string.pairing_phone_code,
            R.string.pairing_phone_send,
            R.string.pairing_phone_sending,
        ).forEach { assertThat(page).contains(context.getString(it)) }
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

    // ---- No TMDB Key ---------------------------------------------------------------------------

    /** [page]'s text as the phone shows it: the page escapes what it puts in, apostrophes included. */
    private fun shown(page: String) = page.replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Posts a TMDB Key to where the phone's paste box used to send one. */
    private fun sendKey(session: PairingServer.Session): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("key", KEY)
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/key").post(form).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    @Test
    fun `the phone is never asked for a TMDB Key, before or after the first provider`() {
        val session = listening()
        assertThat(get(session.url).second).doesNotContain("action=\"/key\"")

        sendKey(session)
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        val (_, page) = runBlocking { reply.await() }
        assertThat(page).doesNotContain("action=\"/key\"")
        assertThat(shown(page)).doesNotContain("TMDB")
    }

    /** Presses a Setup page's Not now, as the viewer would. */
    private fun skipKey(session: PairingServer.Session): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder().add("t", session.url.substringAfter("t=")).build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/skip").post(form).build()
        return OkHttpClient().newCall(request).execute().use { it.code to it.body.string() }
    }

    /** Sends an OpenSubtitles username and password from the phone, as the viewer would. */
    private fun signIn(session: PairingServer.Session, password: String = "p"): Pair<Int, String> {
        val form = okhttp3.FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("username", "u")
            .add("password", password)
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/signin").post(form).build()
        return OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun receivedSignIn(): PairingServer.State.SignInReceived {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            (server.state.value as? PairingServer.State.SignInReceived)?.let { return it }
            Thread.sleep(20)
        }
        error("the TV never got the sign-in")
    }

    @Test
    fun `for OpenSubtitles the QR code opens a sign-in form, with where to make a free account`() {
        val session = listening(PairingServer.Mode.OpenSubtitles())

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("action=\"/signin\"")
        assertThat(page).contains("name=\"username\"")
        assertThat(page).contains("type=\"password\"")
        assertThat(page).contains("https://www.opensubtitles.com/")
        assertThat(page).doesNotContain("action=\"/skip\"")
    }

    @Test
    fun `a sign-in sent from the phone reaches the TV, and the phone hears it worked`() {
        val session = listening(PairingServer.Mode.OpenSubtitles())

        val reply = scope.async(Dispatchers.IO) { signIn(session) }
        val got = receivedSignIn()
        assertThat(got.username).isEqualTo("u")
        assertThat(got.password).isEqualTo("p")
        got.answer(problem = null)

        assertThat(runBlocking { reply.await() }.second)
            .contains(context.getString(R.string.pairing_phone_opensubtitles_saved_title))
    }

    @Test
    fun `a sign-in OpenSubtitles turns down shows why on the phone, which can try again`() {
        val session = listening(PairingServer.Mode.OpenSubtitles())

        val first = scope.async(Dispatchers.IO) { signIn(session, password = "wrong") }
        receivedSignIn().answer(ViewerError("OpenSubtitles didn't accept that username or password.", "SignInRejected"))
        val (_, page) = runBlocking { first.await() }

        assertThat(shown(page)).contains("OpenSubtitles didn't accept that username or password.")
        assertThat(page).contains("action=\"/signin\"")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `after the first provider, the phone carries on to OpenSubtitles' sign-in at the same address`() {
        val session = listening(PairingServer.Mode.Provider(thenOpenSubtitles = true))

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        val (_, page) = runBlocking { reply.await() }

        assertThat(page).contains("action=\"/signin\"")
        assertThat(page).contains("action=\"/skip\"")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        // As the TV's OpenSubtitles step takes the server over, the address stays the same.
        server.start(PairingServer.Mode.OpenSubtitles(onboarding = true))
        val signedIn = scope.async(Dispatchers.IO) { signIn(session) }
        receivedSignIn().answer(problem = null)
        assertThat(runBlocking { signedIn.await() }.second)
            .contains(context.getString(R.string.pairing_phone_opensubtitles_saved_title))
    }

    @Test
    fun `a later provider leaves the phone done, with nothing to carry on to`() {
        val session = listening(PairingServer.Mode.Provider())

        val reply = scope.async(Dispatchers.IO) { submit(session) }
        received().answer(error = null)
        val (_, page) = runBlocking { reply.await() }

        assertThat(page).doesNotContain("action=\"/signin\"")
    }

    @Test
    fun `Setup's OpenSubtitles page has Not now, which tells the TV to carry on`() {
        val session = listening(PairingServer.Mode.OpenSubtitles(onboarding = true))
        assertThat(get(session.url).second).contains("action=\"/skip\"")

        skipKey(session)

        assertThat(server.state.value).isEqualTo(PairingServer.State.KeySkipped)
    }

    private companion object {
        /** A placeholder, low-entropy so gitleaks sees no secret. */
        val KEY = "d".repeat(32)
    }
}
