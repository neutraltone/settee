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
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import app.opentv.pairing.ManagerServer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** Starring from the web manager stars the whole Channel, as the guide does (#104). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ManagerFavouritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val scope = CoroutineScope(Dispatchers.Default)
    private val server = ManagerServer(
        scope,
        test.catalog,
        AppSettings.get(context),
        SourceRepository(db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        test.lineup,
    )

    @After
    fun close() {
        server.stop()
        scope.cancel()
        test.close()
    }

    private fun itv(sourceId: Long, streamId: String, name: String, rank: Int, label: String) = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        displayName = "ITV",
        groupKey = "uk:itv",
        qualityRank = rank,
        qualityLabel = label,
        categoryId = streamId,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
    )

    @Test
    fun `starring a row stars every row of its channel, and every row shows the star`() = runBlocking {
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.sources().insert(Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "http://example.org"))
        // The same FHD stream listed in two of B's categories: one Quality to switch to, two rows to star.
        db.channels().upsertAll(
            listOf(
                itv(1, "10", "UK| ITV HD", 200, "HD"),
                itv(2, "20", "UK| ITV FHD", 300, "FHD"),
                itv(2, "21", "UK| ITV FHD", 300, "FHD"),
            ),
        )
        val hd = db.channels().allForMatching().single { it.sourceId == 1L }
        server.start()
        val session = (server.state.value as? ManagerServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        val http = OkHttpClient()

        val patch = Request.Builder()
            .url(session!!.url.replace("/?", "/channel?"))
            .post("""{"id":${hd.id},"favourite":true}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(patch).execute().use { assertThat(it.code).isEqualTo(200) }

        assertThat(test.viewerLibrary.favourites.channelIds().first()).hasSize(3)
        val listing = Request.Builder().url(session.url.replace("/?", "/channels?cat=21&source=2&")).build()
        val body = http.newCall(listing).execute().use { it.body.string() }
        assertThat(body).contains("\"favourite\":true")
    }
}
