/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.model.Channel
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import app.opentv.pairing.ManagerServer
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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

    /** Each reorder's ids as it commits; the first waits for [firstMayCommit] once [firstCommitting] says it's begun. */
    private val committed: MutableList<List<Long>> = Collections.synchronizedList(mutableListOf())
    private val firstCommitting = CountDownLatch(1)
    private val firstMayCommit = CountDownLatch(1)
    @Volatile private var holdFirstCommit = false

    private val server = ManagerServer(
        scope,
        test.catalog,
        AppSettings.get(context),
        SourceRepository(test.db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        test.lineup,
        test.refresh(),
        saveOrder = { ids ->
            if (holdFirstCommit && firstCommitting.count > 0) {
                firstCommitting.countDown()
                firstMayCommit.await(5, TimeUnit.SECONDS)
            }
            committed += ids
            test.catalog.setChannelOrder(ids)
        },
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

    @Test
    fun `reordering a category from the manager saves the new order, skipping ids that aren't channels`() {
        val session = listening()
        val (a, b, c) = runBlocking {
            test.db.sources().insert(PANEL_SOURCE)
            test.db.channels().upsertAll(
                listOf("1", "2", "3").map { id ->
                    Channel(
                        sourceId = PANEL_SOURCE.id,
                        streamId = id,
                        name = "Channel $id",
                        displayName = "Channel $id",
                        groupKey = "ch:$id",
                        qualityRank = 200,
                        qualityLabel = "HD",
                        categoryId = "1",
                        logoUrl = null,
                        epgChannelId = null,
                        number = null,
                        streamUrl = "http://example.com/live/u/p/$id.m3u8",
                    )
                },
            )
            test.db.channels().everyRow().sortedBy { it.streamId }.map { it.id }
        }

        val reorder = Request.Builder()
            .url(session.url.replace("/?", "/reorder?"))
            .post("""{"ids":[$c,0,$a,-1,$b]}""".toRequestBody("application/json".toMediaType()))
            .build()
        val (code, body) = OkHttpClient().newCall(reorder).execute().use { it.code to it.body.string() }

        assertThat(code).isEqualTo(200)
        assertThat(body).isEqualTo("""{"ok":true}""")
        val saved = runBlocking { test.db.channels().everyRow() }.associate { it.id to it.sortIndex }
        assertThat(saved).containsExactly(c, 0, a, 2, b, 4)
    }

    @Test
    fun `two reorders commit in the order the TV took them, even when the first is slow to save`() {
        val session = listening()
        holdFirstCommit = true
        val first = thread { post(session, "/reorder", """{"ids":[1,2,3]}""") }
        assertThat(firstCommitting.await(5, TimeUnit.SECONDS)).isTrue()

        val second = thread { post(session, "/reorder", """{"ids":[3,2,1]}""") }
        // Long enough for the second to save, if nothing made it wait its turn.
        Thread.sleep(500)
        firstMayCommit.countDown()
        first.join(5_000)
        second.join(5_000)

        assertThat(committed).containsExactly(listOf(1L, 2L, 3L), listOf(3L, 2L, 1L)).inOrder()
    }

    private fun post(session: ManagerServer.Session, path: String, body: String): Int {
        val request = Request.Builder()
            .url(session.url.replace("/?", "$path?"))
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return OkHttpClient().newCall(request).execute().use { it.code }
    }
}
