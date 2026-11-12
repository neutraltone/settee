/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgBuiltInFeedsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    /** Every guide address is gone, as the USA one was when epgshare01 renumbered it. */
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody("text/html".toMediaType()))
            .build()
    }.build()

    private val selfEnabled = EpgRepository.SelfEnabledGuide.InMemory()

    private val guide = EpgRepository(
        programmeDao = db.programmes(),
        feedDao = db.epgFeeds(),
        aliasDao = db.epgAliases(),
        channelDao = db.channels(),
        sourceDao = db.sources(),
        api = XtreamApi(http),
        http = http,
        selfEnabledGuide = selfEnabled,
    )

    private val usa = "USA — epgshare01"
    private val usaUrl = EpgRepository.BUILT_IN_FEEDS.toMap().getValue(usa)

    @After
    fun close() = db.close()

    private suspend fun americanChannels() = db.channels().upsertAll(
        listOf("CNN", "ESPN", "HBO", "AMC", "FOX", "NBC").mapIndexed { i, name ->
            val providerName = "US| $name HD"
            Channel(
                id = i + 1L,
                sourceId = 1,
                streamId = "$i",
                name = providerName,
                groupKey = ChannelNameNormalizer.normalize(providerName).groupKey,
                streamUrl = "https://example.com/$i.ts",
                categoryId = null,
                logoUrl = null,
                epgChannelId = null,
                number = null,
            )
        },
    )

    private suspend fun feed(name: String) = db.epgFeeds().all().single { it.builtIn && it.name == name }

    @Test
    fun `a built-in guide whose address moved keeps its row and switch and takes the new address`() = runTest {
        db.epgFeeds().insert(
            EpgFeed(
                name = usa,
                url = "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
                builtIn = true,
                enabled = true,
                lastResult = "Guide download failed (HTTP 404).",
            ),
        )

        val old = feed(usa)
        db.epgAliases().upsertAll(
            listOf(EpgChannelAlias(feedId = old.id, epgId = "CNN.us1", displayName = "CNN", normalizedKey = "cnn")),
        )

        guide.ensureFeeds()

        // The old file's channel ids would otherwise keep matching channels to a guide that never refreshes.
        assertThat(db.epgAliases().all()).isEmpty()
        val moved = feed(usa)
        assertThat(moved.url).isEqualTo(usaUrl)
        assertThat(moved.enabled).isTrue()
        assertThat(moved.lastResult).isEmpty()
        assertThat(db.epgFeeds().all().map { it.name }).containsNoDuplicates()
    }

    @Test
    fun `a guide Settee switched on by itself doesn't count as a failure when it fails`() = runTest {
        americanChannels()

        val summary = guide.syncAll(nowUtcMillis = NOW)

        assertThat(selfEnabled.name).isEqualTo(usa)
        assertThat(feed(usa).enabled).isTrue()
        assertThat(summary.feedsFailed).isEqualTo(0)
        // Guide settings still says why it isn't working.
        assertThat(feed(usa).lastResult).contains("404")
    }

    @Test
    fun `a custom guide sharing the self-enabled guide's name still counts when it fails`() = runTest {
        americanChannels()
        guide.syncAll(nowUtcMillis = NOW)
        guide.addCustomFeed(usa, "https://example.com/guide.xml")

        val summary = guide.syncAll(nowUtcMillis = NOW + 60_000L)

        assertThat(selfEnabled.name).isEqualTo(usa)
        assertThat(summary.feedsFailed).isEqualTo(1)
    }

    @Test
    fun `once the viewer switches that guide on themselves, its failures count`() = runTest {
        americanChannels()
        guide.syncAll(nowUtcMillis = NOW)

        guide.setFeedEnabled(feed(usa).id, false)
        guide.setFeedEnabled(feed(usa).id, true)
        val summary = guide.syncAll(nowUtcMillis = NOW + 60_000L)

        assertThat(selfEnabled.name).isNull()
        assertThat(summary.feedsFailed).isEqualTo(1)
    }
}

/** 2026-11-12, well past every feed's refresh interval from a never-synced stamp of 0. */
private const val NOW = 1_794_500_000_000L
