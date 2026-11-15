/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A feed removed while it downloads, as a deleted provider's is, leaves nothing behind: what the
 * download writes after the removal goes too, so its aliases don't keep matching channels (#343).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgFeedRemovedMidSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val url = "https://example.com/guide.xml"

    private val xmltv = """
        <tv>
          <channel id="news"><display-name>News</display-name></channel>
          <programme start="20330518033320 +0000" stop="20330518043320 +0000" channel="news">
            <title>News</title>
          </programme>
        </tv>
    """.trimIndent()

    /** Removes the feed as its download starts, then serves it. */
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        runBlocking { guide.removeFeed(db.epgFeeds().byUrl(url)!!) }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(xmltv.toResponseBody("application/xml".toMediaType()))
            .build()
    }.build()

    private val guide: EpgRepository = EpgRepository(
        programmeDao = db.programmes(),
        refreshDao = db.guideRefresh(),
        feedDao = db.epgFeeds(),
        aliasDao = db.epgAliases(),
        channelDao = db.channels(),
        sourceDao = db.sources(),
        api = XtreamApi(http),
        http = http,
        wording = ErrorWording(context, onTelevision = false),
        selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
    )

    @After
    fun close() = db.close()

    @Test
    fun `a feed removed while it downloads leaves no programmes or aliases`() = runTest {
        guide.addCustomFeed("My guide", url)

        val summary = guide.syncAll(nowUtcMillis = 0, force = true)

        assertThat(summary.feedsFailed).isEqualTo(0)
        // The refresh adds the built-in feeds, switched off; only this one should be gone.
        assertThat(db.epgFeeds().byUrl(url)).isNull()
        assertThat(guide.upcoming("news", nowUtcMillis = 0)).isEmpty()
        assertThat(db.epgAliases().all()).isEmpty()
    }
}
