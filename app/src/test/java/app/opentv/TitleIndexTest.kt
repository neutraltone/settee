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
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val stick = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088))
    private val index = TitleIndex(db, stick)

    @After
    fun close() {
        index.close()
        db.close()
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
}
