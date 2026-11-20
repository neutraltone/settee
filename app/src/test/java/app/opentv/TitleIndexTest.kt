/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.TitleIndex
import app.opentv.data.repo.regroupGapMillis
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every film and show grouped into titles once, for what lists and covers show (#181, ADR 0007). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TitleIndexTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val filmListReads = AtomicInteger()
    private val showListReads = AtomicInteger()

    /** Where Room reports each query; drained by [listReads] so a late report isn't missed. */
    private val queryLog = Executors.newSingleThreadExecutor()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback(
            { sql, _ ->
                if (sql.startsWith(FILM_LIST)) filmListReads.incrementAndGet()
                if (sql.startsWith(SHOW_LIST)) showListReads.incrementAndGet()
            },
            queryLog,
        )
        .build()
    private val stick = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
    private val index = TitleIndex(db, stick)

    @After
    fun close() {
        index.close()
        db.close()
        queryLog.shutdown()
    }

    /** [reads] once every query run so far has been reported. */
    private fun listReads(reads: AtomicInteger): Int {
        queryLog.submit {}.get()
        return reads.get()
    }

    /** [block] with an index that waits [gapMillis] between groupings, closed after. */
    private fun withGap(gapMillis: Long, block: suspend (TitleIndex) -> Unit) = runBlocking {
        val paced = TitleIndex(db, stick, regroupGap = { gapMillis })
        try {
            block(paced)
        } finally {
            paced.close()
        }
    }

    @Test
    fun `a film's Versions in other categories are one title with it`(): Unit = runBlocking {
        db.movies().upsertAll(
            listOf(
                movie(1, "4K-EN - Superbad (2007)", "4k"),
                movie(2, "EN - Superbad (2007)", "en"),
                movie(3, "EN - Juno (2007)", "en", tmdbId = "7326"),
            ),
        )

        val titles = index.movies.filterNotNull().first()

        assertThat(titles.of(2)?.versionIds?.toList()).containsExactly(1L, 2L)
        assertThat(titles.of(3)?.versionIds?.toList()).containsExactly(3L)
    }

    @Test
    fun `a list keeps the first row of each title`(): Unit = runBlocking {
        db.movies().upsertAll(listOf(movie(1, "NF - Superbad (2007)", "nf"), movie(2, "EN - Superbad (2007)", "en")))

        val titles = index.movies.filterNotNull().first()

        assertThat(titles.distinct(listOf(2L, 1L)) { it }).containsExactly(2L)
    }

    @Test
    fun `a cover's badge counts every Version and what its category says`(): Unit = runBlocking {
        db.categories().upsertAll(listOf(Category("cam", 1, "CAM MOVIES", StreamKind.MOVIE)))
        db.movies().upsertAll(listOf(movie(1, "4K-EN - New Film (2026)", "4k"), movie(2, "New Film (2026)", "cam")))

        assertThat(index.movies.filterNotNull().first().badgeOf(2)).isEqualTo("CAM")
    }

    @Test
    fun `shows are grouped the same way`(): Unit = runBlocking {
        db.series().upsertAll(
            listOf(series(1, "4K-NF - The Diplomat (2023) (US)"), series(2, "EN - The Diplomat (2023) (US)")),
        )

        val titles = index.series.filterNotNull().first()

        assertThat(titles.of(1)?.versionIds?.toList()).containsExactly(1L, 2L)
        assertThat(titles.badgeOf(1)).isNull()
    }

    @Test
    fun `films filled in one after another, as the background pass does, don't each read every film again`() =
        withGap(STEADY_GAP_MILLIS) { paced ->
            db.movies().upsertAll((1L..2_000L).map { movie(it, "Film $it (2001)", "en", tmdbId = "$it") })
            paced.movies.filterNotNull().first()
            listReads(filmListReads)
            filmListReads.set(0)

            // Further apart than the settle, as a pass waiting on its provider saves.
            for (id in 1L..6L) {
                delay(600)
                db.movies().upsertAll(listOf(movie(id, "Film $id (2001)", "en", tmdbId = "$id").copy(genre = "Drama")))
            }

            assertThat(listReads(filmListReads)).isAtMost(1)
        }

    @Test
    fun `shows filled in one after another don't each read every show again`() = withGap(STEADY_GAP_MILLIS) { paced ->
        db.series().upsertAll((1L..2_000L).map { series(it, "Show $it (2001)") })
        paced.series.filterNotNull().first()
        listReads(showListReads)
        showListReads.set(0)

        for (id in 1L..6L) {
            delay(600)
            db.series().upsertAll(listOf(series(id, "Show $id (2001)").copy(genre = "Drama")))
        }

        assertThat(listReads(showListReads)).isAtMost(1)
    }

    @Test
    fun `a film added after the first grouping joins its title once the gap has passed`() =
        withGap(GAP_MILLIS) { paced ->
            db.movies().upsertAll(listOf(movie(1, "EN - Superbad (2007)", "en")))
            paced.movies.filterNotNull().first()

            db.movies().upsertAll(listOf(movie(2, "4K - Superbad (2007)", "4k")))

            val grouped = withTimeout(GAP_MILLIS * 3) { paced.movies.filterNotNull().first { it.of(2) != null } }
            assertThat(grouped.of(2)?.versionIds?.toList()).containsExactly(1L, 2L)
        }

    @Test
    fun `a catalogue saved after an empty first grouping, as on setup, is grouped without waiting out a gap`() =
        withGap(STEADY_GAP_MILLIS) { paced ->
            paced.movies.filterNotNull().first()

            db.movies().upsertAll(
                listOf(movie(1, "EN - Superbad (2007)", "en"), movie(2, "4K - Superbad (2007)", "4k")),
            )

            val grouped = withTimeout(GAP_MILLIS) { paced.movies.filterNotNull().first { it.of(2) != null } }
            assertThat(grouped.of(2)?.versionIds?.toList()).containsExactly(1L, 2L)
        }

    @Test
    fun `the gap between groupings keeps them to a twentieth of the time, between ten seconds and ten minutes`() {
        assertThat(regroupGapMillis(5)).isEqualTo(10_000)
        assertThat(regroupGapMillis(2_000)).isEqualTo(38_000)
        assertThat(regroupGapMillis(60_000)).isEqualTo(600_000)
    }

    private fun movie(id: Long, name: String, category: String, tmdbId: String = "8363") = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = category,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = tmdbId,
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "s$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        tmdbId = "203857",
    )

    private companion object {
        const val FILM_LIST = "SELECT id, sourceId, name, tmdbId, year, categoryId FROM movies"
        const val SHOW_LIST = "SELECT id, sourceId, name, tmdbId, year, categoryId FROM series"
        const val GAP_MILLIS = 2_000L

        /** Longer than the six saves take, as on a Stick, where one grouping takes many seconds. */
        const val STEADY_GAP_MILLIS = 10_000L
    }
}
