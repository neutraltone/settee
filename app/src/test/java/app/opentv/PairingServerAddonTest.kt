/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServerProblem
import app.opentv.core.ViewerError
import app.opentv.pairing.PairingFonts
import app.opentv.pairing.PairingServer
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adding add-ons from a phone: one address a submission, as many as the viewer likes in one session,
 * each checked by the TV, which answers with the add-on's name or why it was refused. Placeholder
 * addresses only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PairingServerAddonTest {

    private val scope = CoroutineScope(Dispatchers.Default)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var server = PairingServer(scope, PairingFonts(context), resources = { context.resources })

    @After
    fun close() {
        server.stop()
        scope.cancel()
    }

    private fun listening(): PairingServer.Session {
        server.start(PairingServer.Mode.Addon)
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        return session!!
    }

    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    private fun get(url: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code to it.body.string() }

    private fun post(session: PairingServer.Session, path: String, vararg fields: Pair<String, String>): String {
        val form = FormBody.Builder().add("t", session.url.substringAfter("t="))
        fields.forEach { (name, value) -> form.add(name, value) }
        val request = Request.Builder().url("http://${session.host}:${session.port}$path").post(form.build()).build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    /** Sends [address] from the phone's page, as the viewer would, without waiting for the answer. */
    private fun send(session: PairingServer.Session, address: String) =
        scope.async(Dispatchers.IO) { post(session, "/addon", "url" to address) }

    private fun received(): PairingServer.State.AddonReceived = runBlocking {
        withTimeoutOrNull(10_000) {
            server.state.first { it is PairingServer.State.AddonReceived } as PairingServer.State.AddonReceived
        }
    } ?: error("the TV never got the address")

    /** [page]'s text as the phone shows it: the page escapes what it puts in. */
    private fun shown(page: String) = page.replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    @Test
    fun `for add-ons the QR code opens a form for one address, with Done`() {
        val session = listening()

        val (code, page) = get(session.url)

        assertThat(code).isEqualTo(200)
        assertThat(page).contains("action=\"/addon\"")
        assertThat(page).contains("name=\"url\"")
        assertThat(page).contains("action=\"/done\"")
        assertThat(shown(page)).contains(context.getString(R.string.pairing_phone_addon_title))
    }

    @Test
    fun `an add-on the TV accepts clears the field, says it was added, and the session stays open`() {
        val session = listening()

        val reply = send(session, "stremio://addons.example.com/films/manifest.json")
        val got = received()
        assertThat(got.address).isEqualTo("stremio://addons.example.com/films/manifest.json")
        got.accept("Films add-on")
        val page = shown(runBlocking { reply.await() })

        assertThat(page).contains("Added Films add-on")
        assertThat(page).contains("action=\"/addon\"")
        assertThat(page).doesNotContain("value=\"stremio://")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `an add-on the TV refuses says why, and the address stays on the page to correct`() {
        val session = listening()

        val reply = send(session, "addons.example.com/flims")
        received().refuse(ViewerError("That address didn't give an add-on.", "NotAnAddon · HTTP 404"))
        val page = shown(runBlocking { reply.await() })

        assertThat(page).contains("That address didn't give an add-on.")
        assertThat(page).contains("NotAnAddon · HTTP 404")
        assertThat(page).contains("value=\"addons.example.com/flims\"")
        assertThat(page).doesNotContain("Added ")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `several add-ons go in one session, and the page lists each one added`() {
        val session = listening()

        val first = send(session, "addons.example.com/films")
        received().accept("Films add-on")
        runBlocking { first.await() }
        val second = send(session, "addons.example.com/shows")
        received().accept("Shows add-on")
        val page = shown(runBlocking { second.await() })

        assertThat(page).contains("Films add-on")
        assertThat(page).contains("Shows add-on")
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
    }

    @Test
    fun `each address sent starts the time left again`() {
        server.stop()
        val clock = StandardTestDispatcher()
        server = PairingServer(
            scope,
            PairingFonts(context),
            resources = { context.resources },
            sessionTimeoutMillis = TIMEOUT_MILLIS,
            timerDispatcher = clock,
        )
        val session = listening()

        clock.scheduler.advanceTimeBy(TIMEOUT_MILLIS * 2 / 3)
        val reply = send(session, "addons.example.com/films")
        received().accept("Films add-on")
        runBlocking { reply.await() }
        clock.scheduler.advanceTimeBy(TIMEOUT_MILLIS * 2 / 3)

        // Past the first session's end, but not a whole session since the address came.
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        clock.scheduler.advanceTimeBy(TIMEOUT_MILLIS / 2)
        assertThat(server.state.value).isEqualTo(PairingServer.State.Failed(ServerProblem.TIMED_OUT))
    }

    @Test
    fun `Done on the phone ends the session and tells the TV`() {
        val session = listening()

        val page = shown(post(session, "/done"))

        assertThat(page).contains(context.getString(R.string.pairing_phone_addon_done_title))
        assertThat(server.state.value).isEqualTo(PairingServer.State.AddonsDone)
        assertThat(runCatching { get(session.url) }.isFailure).isTrue()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 1_500L
    }
}
