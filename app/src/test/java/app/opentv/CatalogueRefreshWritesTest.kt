/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.personKey
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a catalogue refresh asks SQLite to do. A refresh over rows already stored should update them,
 * not try an INSERT that fails on the unique index for each and then update, as Room's upsert does:
 * on a 70,000-row catalogue on a Fire TV Stick that was a second or two of failed statements.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CatalogueRefreshWritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every statement the database is given, in order, as Room logs it before running it. */
    private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> statements += sql }, Executor(Runnable::run))
        .build()

    private val channels = db.channels()
    private val movies = db.movies()
    private val shows = db.series()

    @After
    fun close() = db.close()

    private fun writes(verb: String, table: String): List<String> {
        val pattern = Regex("""^\s*$verb\b.*`$table`""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return synchronized(statements) { statements.filter { pattern.containsMatchIn(it) } }
    }

    private suspend fun source(kind: SourceKind = SourceKind.M3U) =
        db.sources().insert(Source(name = "Home", kind = kind, url = "https://example.com/list"))

    private fun channel(sourceId: Long, streamId: String, name: String = "Station $streamId") = Channel(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.com/$streamId.ts",
    )

    /** The batched overload, as Xtream's list is saved: the whole of [list] as one batch. */
    private suspend fun refreshInBatches(sourceId: Long, list: List<Channel>, syncStamp: Long) =
        channels.replaceCatalogue(sourceId, syncStamp) { save -> save(list) }

    @Test
    fun `refreshing an unchanged channel list again updates its rows without trying to insert them`() = runTest {
        val sourceId = source()
        val list = listOf(channel(sourceId, "a"), channel(sourceId, "b"))
        channels.replaceCatalogue(sourceId, list, syncStamp = 1)
        // The counter sees this database's writes at all, so an empty list below means something.
        assertThat(writes("INSERT", "channels")).isNotEmpty()
        val ids = channels.forSource(sourceId).associate { it.streamId to it.id }
        statements.clear()

        channels.replaceCatalogue(sourceId, list, syncStamp = 2)

        assertThat(writes("INSERT", "channels")).isEmpty()
        assertThat(writes("UPDATE", "channels")).isNotEmpty()
        val after = channels.forSource(sourceId)
        assertThat(after.associate { it.streamId to it.id }).isEqualTo(ids)
        assertThat(after.map { it.lastSeenMillis }).containsExactly(2L, 2L)
    }

    @Test
    fun `refreshing a channel list read in batches again updates its rows without trying to insert them`() = runTest {
        val sourceId = source(SourceKind.XTREAM)
        val list = listOf(channel(sourceId, "a"), channel(sourceId, "b"))
        refreshInBatches(sourceId, list, syncStamp = 1)
        assertThat(writes("INSERT", "channels")).isNotEmpty()
        val ids = channels.forSource(sourceId).associate { it.streamId to it.id }
        statements.clear()

        refreshInBatches(sourceId, list, syncStamp = 2)

        assertThat(writes("INSERT", "channels")).isEmpty()
        assertThat(writes("UPDATE", "channels")).isNotEmpty()
        val after = channels.forSource(sourceId)
        assertThat(after.associate { it.streamId to it.id }).isEqualTo(ids)
        assertThat(after.map { it.lastSeenMillis }).containsExactly(2L, 2L)
    }

    @Test
    fun `a new channel listed twice in one refresh is one row, as its last listing`() = runTest {
        val sourceId = source()

        channels.replaceCatalogue(
            sourceId,
            listOf(channel(sourceId, "a", "First"), channel(sourceId, "b"), channel(sourceId, "a", "Second")),
            syncStamp = 1,
        )

        val rows = channels.forSource(sourceId)
        assertThat(rows.map { it.streamId }).containsExactly("a", "b")
        assertThat(rows.single { it.streamId == "a" }.name).isEqualTo("Second")
    }

    @Test
    fun `a new channel listed twice in a batch is one row, as its last listing`() = runTest {
        val sourceId = source(SourceKind.XTREAM)

        refreshInBatches(
            sourceId,
            listOf(channel(sourceId, "a", "First"), channel(sourceId, "b"), channel(sourceId, "a", "Second")),
            syncStamp = 1,
        )

        val rows = channels.forSource(sourceId)
        assertThat(rows.map { it.streamId }).containsExactly("a", "b")
        assertThat(rows.single { it.streamId == "a" }.name).isEqualTo("Second")
    }

    private fun movie(sourceId: Long, streamId: String, name: String = "Film $streamId", cast: String? = null) = Movie(
        sourceId = sourceId,
        streamId = streamId,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "https://example.com/movie/$streamId.mp4",
        cast = cast,
    )

    @Test
    fun `refreshing films already stored updates them without trying to insert them`() = runTest {
        val sourceId = source(SourceKind.XTREAM)
        movies.upsertAll(listOf(movie(sourceId, "a"), movie(sourceId, "b")))
        assertThat(writes("INSERT", "movies")).isNotEmpty()
        val stored = movies.forSource(sourceId).associateBy { it.streamId }
        statements.clear()

        // As a refresh saves them: the provider's fresh rows, each carrying the id of the one it replaces.
        movies.upsertAll(
            listOf(
                movie(sourceId, "a", "Film a, remastered").copy(id = stored.getValue("a").id),
                movie(sourceId, "b").copy(id = stored.getValue("b").id),
            ),
        )

        assertThat(writes("INSERT", "movies")).isEmpty()
        assertThat(writes("UPDATE", "movies")).isNotEmpty()
        val after = movies.forSource(sourceId)
        assertThat(after.associate { it.streamId to it.id }).isEqualTo(stored.mapValues { it.value.id })
        assertThat(after.single { it.streamId == "a" }.name).isEqualTo("Film a, remastered")
    }

    @Test
    fun `a film new in a refresh has its cast credited under its new id`() = runTest {
        val sourceId = source(SourceKind.XTREAM)
        movies.upsertAll(listOf(movie(sourceId, "a")))
        val a = movies.forSource(sourceId).single()

        movies.upsertAll(listOf(a, movie(sourceId, "b", cast = "Jane Doe, John Roe")))

        val b = movies.forSource(sourceId).single { it.streamId == "b" }
        assertThat(movies.moviesWithPerson(personKey("Jane Doe"), limit = 10).map { it.id }).containsExactly(b.id)
        assertThat(movies.moviesWithPerson(personKey("John Roe"), limit = 10).map { it.id }).containsExactly(b.id)
    }

    @Test
    fun `a new film listed twice in one refresh is one row, as its last listing`() = runTest {
        val sourceId = source(SourceKind.XTREAM)

        movies.upsertAll(
            listOf(
                movie(sourceId, "a", "First", cast = "Jane Doe"),
                movie(sourceId, "b"),
                movie(sourceId, "a", "Second", cast = "John Roe"),
            ),
        )

        val rows = movies.forSource(sourceId)
        assertThat(rows.map { it.streamId }).containsExactly("a", "b")
        val a = rows.single { it.streamId == "a" }
        assertThat(a.name).isEqualTo("Second")
        assertThat(movies.moviesWithPerson(personKey("John Roe"), limit = 10).map { it.id }).containsExactly(a.id)
        assertThat(movies.moviesWithPerson(personKey("Jane Doe"), limit = 10)).isEmpty()
    }

    @Test
    fun `a film saved with an id that isn't stored yet is added under that id, credits and all`() = runTest {
        val sourceId = source(SourceKind.XTREAM)

        movies.upsertAll(listOf(movie(sourceId, "a", cast = "Jane Doe").copy(id = 7)))

        assertThat(movies.byId(7)?.streamId).isEqualTo("a")
        assertThat(movies.moviesWithPerson(personKey("Jane Doe"), limit = 10).map { it.id }).containsExactly(7L)
    }

    private fun show(sourceId: Long, seriesId: String, name: String = "Show $seriesId", cast: String? = null) = Series(
        sourceId = sourceId,
        seriesId = seriesId,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        cast = cast,
    )

    @Test
    fun `refreshing shows already stored updates them without trying to insert them`() = runTest {
        val sourceId = source(SourceKind.XTREAM)
        shows.upsertAll(listOf(show(sourceId, "a"), show(sourceId, "b")))
        assertThat(writes("INSERT", "series")).isNotEmpty()
        val stored = shows.forSource(sourceId).associateBy { it.seriesId }
        statements.clear()

        shows.upsertAll(
            listOf(
                show(sourceId, "a", "Show a, season two").copy(id = stored.getValue("a").id),
                show(sourceId, "b").copy(id = stored.getValue("b").id),
            ),
        )

        assertThat(writes("INSERT", "series")).isEmpty()
        assertThat(writes("UPDATE", "series")).isNotEmpty()
        val after = shows.forSource(sourceId)
        assertThat(after.associate { it.seriesId to it.id }).isEqualTo(stored.mapValues { it.value.id })
        assertThat(after.single { it.seriesId == "a" }.name).isEqualTo("Show a, season two")
    }

    @Test
    fun `a show new in a refresh has its cast credited under its new id`() = runTest {
        val sourceId = source(SourceKind.XTREAM)
        shows.upsertAll(listOf(show(sourceId, "a")))
        val a = shows.forSource(sourceId).single()

        shows.upsertAll(listOf(a, show(sourceId, "b", cast = "Jane Doe")))

        val b = shows.forSource(sourceId).single { it.seriesId == "b" }
        assertThat(shows.seriesWithPerson(personKey("Jane Doe"), limit = 10).map { it.id }).containsExactly(b.id)
    }

    @Test
    fun `a new show listed twice in one refresh is one row, as its last listing`() = runTest {
        val sourceId = source(SourceKind.XTREAM)

        shows.upsertAll(
            listOf(
                show(sourceId, "a", "First", cast = "Jane Doe"),
                show(sourceId, "b"),
                show(sourceId, "a", "Second", cast = "John Roe"),
            ),
        )

        val rows = shows.forSource(sourceId)
        assertThat(rows.map { it.seriesId }).containsExactly("a", "b")
        val a = rows.single { it.seriesId == "a" }
        assertThat(a.name).isEqualTo("Second")
        assertThat(shows.seriesWithPerson(personKey("John Roe"), limit = 10).map { it.id }).containsExactly(a.id)
        assertThat(shows.seriesWithPerson(personKey("Jane Doe"), limit = 10)).isEmpty()
    }

    @Test
    fun `a show saved with an id that isn't stored yet is added under that id, credits and all`() = runTest {
        val sourceId = source(SourceKind.XTREAM)

        shows.upsertAll(listOf(show(sourceId, "a", cast = "Jane Doe").copy(id = 7)))

        assertThat(shows.byId(7)?.seriesId).isEqualTo("a")
        assertThat(shows.seriesWithPerson(personKey("Jane Doe"), limit = 10).map { it.id }).containsExactly(7L)
    }
}
