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
import app.opentv.data.db.ChannelDao
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.ChannelEpgMatch
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.parser.ChannelNameNormalizer
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpgMatcherWritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val channels = CountingChannels(db.channels())

    private val http = OkHttpClient()
    private val guide = EpgRepository(
        programmeDao = db.programmes(),
        feedDao = db.epgFeeds(),
        aliasDao = db.epgAliases(),
        channelDao = channels,
        sourceDao = db.sources(),
        api = XtreamApi(http),
        http = http,
        wording = ErrorWording(ApplicationProvider.getApplicationContext(), onTelevision = false),
        selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
    )

    @After
    fun close() = db.close()

    /** Spelt in k-t, which can't make a quality tag like `HD` that the normaliser would strip, colliding two names. */
    private fun name(n: Int) = "Station " + n.toString().map { 'k' + (it - '0') }.joinToString("")

    private fun epgId(n: Int) = "station$n.guide"

    private fun channel(n: Int, matchedEpgId: String? = null) = Channel(
        id = n + 1L,
        sourceId = 1,
        streamId = "$n",
        name = name(n),
        groupKey = ChannelNameNormalizer.normalize(name(n)).groupKey,
        streamUrl = "https://example.com/$n.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        matchedEpgId = matchedEpgId,
        number = null,
    )

    /** A guide that lists a channel for each of [numbers] and has programmes for those in [populated]. */
    private suspend fun guideFor(numbers: Iterable<Int>, populated: Iterable<Int> = numbers) {
        val feedId = db.epgFeeds().insert(EpgFeed(name = "Guide", url = "https://example.com/guide.xml"))
        db.epgAliases().upsertAll(
            numbers.map {
                EpgChannelAlias(
                    feedId = feedId,
                    epgId = epgId(it),
                    displayName = name(it),
                    normalizedKey = ChannelNameNormalizer.normalize(name(it)).groupKey,
                )
            },
        )
        db.programmes().insertOrReplace(
            populated.map {
                Programme(
                    feedId = feedId,
                    epgChannelId = epgId(it),
                    startUtcMillis = 0,
                    endUtcMillis = 1,
                    title = "Programme",
                )
            },
        )
    }

    @Test
    fun `every match reaches the database in a single write`() = runTest {
        val numbers = 0..1_000
        db.channels().upsertAll(numbers.map { channel(it) })
        guideFor(numbers)

        guide.runMatcher()

        assertThat(channels.writes).containsExactly(numbers.count())
        val written = db.channels().matcherInputs().associate { it.id to it.matchedEpgId }
        assertThat(written).isEqualTo(numbers.associate { it + 1L to epgId(it) })
    }

    @Test
    fun `matches that fail partway are saved as none, because they're one transaction`() = runTest {
        db.channels().upsertAll((0..1).map { channel(it) })
        val failsOnTheSecond = object : AbstractList<ChannelEpgMatch>() {
            override val size = 2

            override fun get(index: Int) = if (index == 0) ChannelEpgMatch(1, epgId(0)) else error("interrupted")
        }

        runCatching { db.channels().setMatchedEpgIds(failsOnTheSecond) }

        assertThat(db.channels().matcherInputs().map { it.matchedEpgId }).containsExactly(null, null)
    }

    @Test
    fun `a matcher run that changes nothing writes nothing`() = runTest {
        db.channels().upsertAll((0..9).map { channel(it, matchedEpgId = epgId(it)) })
        guideFor(0..9)

        guide.runMatcher()

        assertThat(channels.writes).isEmpty()
    }

    @Test
    fun `a channel the guide no longer lists loses its match, and others keep theirs`() = runTest {
        db.channels().upsertAll((0..2).map { channel(it, matchedEpgId = "old.guide") })
        guideFor(listOf(0, 1))

        guide.runMatcher()

        val written = db.channels().matcherInputs().associate { it.id to it.matchedEpgId }
        assertThat(written).containsExactly(1L, epgId(0), 2L, epgId(1), 3L, null)
    }

    @Test
    fun `the matcher counts channels whose match has programmes, against every channel`() = runTest {
        db.channels().upsertAll((0..9).map { channel(it) })
        guideFor(0..5, populated = 0..2)

        assertThat(guide.runMatcher()).isEqualTo(3 to 10)
    }

    /** How many matches each write to the database carried. */
    private class CountingChannels(private val real: ChannelDao) : ChannelDao by real {
        val writes = mutableListOf<Int>()

        override suspend fun setMatchedEpgIds(matches: List<ChannelEpgMatch>) {
            writes += matches.size
            real.setMatchedEpgIds(matches)
        }
    }
}
