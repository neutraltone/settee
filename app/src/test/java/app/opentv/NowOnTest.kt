/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.GUIDE_FINGERPRINT_QUERY
import app.opentv.data.db.ON_NOW_QUERY
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.nowOn
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What's on now on many Channels at once, as Home's Favourites row and the player's channel list ask it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NowOnTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every statement the database runs, to tell whether the guide's ids were read again. */
    private val issued: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val catalogue = TestCatalog(
        context,
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> issued += sql }, Runnable::run)
            .build(),
    )
    private val db = catalogue.db
    private val lineup = catalogue.lineup

    @Before
    fun provider() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "http://example.com"))
    }

    @After
    fun close() = catalogue.close()

    /** Stores [names] as one provider's rows, cleaned the way an import cleans them. */
    private suspend fun seed(vararg names: String) {
        val rows = names.mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i-$name",
                name = name,
                categoryId = null,
                logoUrl = null,
                epgChannelId = null,
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        catalogue.catalog.renormalizeAll()
    }

    private suspend fun guide(rowName: String, epgId: String) =
        db.channels().setEpgOverride(db.channels().everyRow().single { it.name == rowName }.id, epgId)

    private suspend fun store(vararg programmes: Programme) = db.programmes().insertOrReplace(programmes.toList())

    private fun on(epgId: String, title: String, start: Long, end: Long, feedId: Long = 1) =
        Programme(feedId = feedId, epgChannelId = epgId, startUtcMillis = start, endUtcMillis = end, title = title)

    private suspend fun all(): List<LineupChannel> = lineup.channels(LineupScope.All()).first()

    /** What's on now by the name of each Channel's best Quality, as Home would label it. */
    private suspend fun nowOnByName(nowMillis: Long): Map<String, String?> {
        val channels = all()
        val nowOn = lineup.nowOn(channels, nowMillis)
        return channels.associate { it.primary.name to nowOn[it.key] }
    }

    @Test
    fun `when two programmes overlap on a guide, the one that started first is on now`() = runTest {
        seed("UK| ITV HD")
        guide("UK| ITV HD", "itv.hd")
        store(on("itv.hd", "Quiz", start = 200, end = 2_000, feedId = 2), on("itv.hd", "News", start = 0, end = 1_000))

        assertThat(nowOnByName(nowMillis = 500)).containsExactly("UK| ITV HD", "News")
    }

    @Test
    fun `a programme a whole day long is still on now in its last minute`() = runTest {
        seed("UK| ITV HD")
        guide("UK| ITV HD", "itv.hd")
        val now = 3 * DAY
        store(on("itv.hd", "Cricket", start = now - DAY + MINUTE, end = now + MINUTE))

        assertThat(nowOnByName(now)).containsExactly("UK| ITV HD", "Cricket")
    }

    @Test
    fun `a programme with more than a day still to run is on now`() = runTest {
        seed("UK| ITV HD")
        guide("UK| ITV HD", "itv.hd")
        val now = 3 * DAY
        store(on("itv.hd", "Telethon", start = now - HOUR, end = now + 30 * HOUR))

        assertThat(nowOnByName(now)).containsExactly("UK| ITV HD", "Telethon")
    }

    @Test
    fun `a guide that gains programmes is used from then on`() = runTest {
        seed("UK| ITV FHD", "UK| ITV HD")
        guide("UK| ITV FHD", "itv.fhd")
        guide("UK| ITV HD", "itv.hd")
        store(on("itv.hd", "News", start = 0, end = 10 * MINUTE))
        assertThat(nowOnByName(nowMillis = MINUTE)).containsExactly("UK| ITV FHD", "News")

        store(on("itv.fhd", "Late News", start = 0, end = 10 * MINUTE))

        assertThat(nowOnByName(nowMillis = MINUTE)).containsExactly("UK| ITV FHD", "Late News")
    }

    @Test
    fun `a guide whose last programmes are pruned stops shadowing one that has them`() = runTest {
        seed("UK| ITV FHD", "UK| ITV HD")
        guide("UK| ITV FHD", "itv.fhd")
        guide("UK| ITV HD", "itv.hd")
        store(on("itv.fhd", "Breakfast", start = 0, end = MINUTE), on("itv.hd", "News", start = 0, end = 10 * MINUTE))
        assertThat(nowOnByName(nowMillis = 2 * MINUTE)).containsExactly("UK| ITV FHD", null)

        db.programmes().deleteEndedBefore(2 * MINUTE)

        assertThat(nowOnByName(nowMillis = 2 * MINUTE)).containsExactly("UK| ITV FHD", "News")
    }

    @Test
    fun `asking every minute doesn't read which guides have programmes again until the guide changes`() = runTest {
        seed("UK| ITV HD")
        guide("UK| ITV HD", "itv.hd")
        store(on("itv.hd", "News", start = 0, end = 10 * MINUTE))
        val channels = all()
        issued.clear()
        lineup.nowOn(channels, nowMillis = MINUTE)
        assertThat(issued.filter { "DISTINCT epgChannelId" in it }).hasSize(1)
        issued.clear()

        lineup.nowOn(channels, nowMillis = 2 * MINUTE)
        lineup.nowOn(channels, nowMillis = 3 * MINUTE)

        assertThat(issued.filter { "DISTINCT epgChannelId" in it }).isEmpty()
    }

    @Test
    fun `telling whether the guide changed reads the ends of two indexes, not the guide`() {
        val plan = db.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $GUIDE_FINGERPRINT_QUERY"))
            .use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(3)) } }

        // The outer SELECT has no table: newer SQLite lists it as a scan of one constant row.
        assertThat(plan.filter { it.startsWith("SCAN") && it != "SCAN CONSTANT ROW" }).isEmpty()
    }

    @Test
    fun `what's on now is looked up in the end and start index over a bounded range`() {
        val plan = db.query(
            SimpleSQLiteQuery(
                "EXPLAIN QUERY PLAN " + ON_NOW_QUERY.replace(":nowUtcMillis", "?").replace(":endsByUtcMillis", "?"),
                arrayOf<Any>(MINUTE, DAY + MINUTE, MINUTE),
            ),
        ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(3)) } }

        // Older SQLite, as on older Android, writes "SEARCH TABLE programmes": the index and range are what count.
        assertThat(plan).hasSize(1)
        assertThat(plan.single()).startsWith("SEARCH")
        assertThat(plan.single()).endsWith(
            "USING INDEX index_programmes_endUtcMillis_startUtcMillis (endUtcMillis>? AND endUtcMillis<?)",
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * 60 * MINUTE
    }
}
