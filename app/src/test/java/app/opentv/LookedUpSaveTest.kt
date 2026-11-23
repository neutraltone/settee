/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a look-up found (opening a title, or a background pass) is saved onto the row as it's stored
 * when the answer comes back, not as it was read before asking: a refresh may have changed or deleted
 * it meanwhile (#366).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LookedUpSaveTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val movies = test.db.movies()
    private val series = test.db.series()

    @After
    fun close() = test.close()

    private val listed = Movie(
        sourceId = PANEL_SOURCE.id,
        streamId = "3",
        name = "Drive",
        categoryId = "1",
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "http://example.com/movie/u/p/3.mkv",
        addedMillis = 100_000,
        lastSeenMillis = 1,
    )

    private suspend fun stored(): Movie {
        test.db.sources().insert(PANEL_SOURCE)
        movies.upsertAll(listOf(listed))
        return movies.all().single()
    }

    @Test
    fun `a film's look-up lands on the row as a refresh left it, changing only what it filled in`() = testing {
        val id = stored().id
        val refreshed = movies.byId(id)!!.copy(
            name = "Drive Redux",
            posterUrl = "https://example.com/new.jpg",
            categoryId = "2",
            addedMillis = 300_000,
            lastSeenMillis = 2,
        )
        movies.upsertAll(listOf(refreshed))

        val saved = movies.saveLookedUp(id) { it.copy(plot = "A driver.", cast = "Ryan Gosling") }

        val expected = refreshed.copy(plot = "A driver.", cast = "Ryan Gosling")
        assertThat(movies.byId(id)).isEqualTo(expected)
        assertThat(saved).isEqualTo(expected)
    }

    @Test
    fun `a film deleted before its look-up is saved stays deleted`() = testing {
        val id = stored().id
        movies.deleteForSource(PANEL_SOURCE.id)

        val saved = movies.saveLookedUp(id) { it.copy(plot = "A driver.") }

        assertThat(saved).isNull()
        assertThat(movies.all()).isEmpty()
    }

    private val listedShow = Series(
        sourceId = PANEL_SOURCE.id,
        seriesId = "7",
        name = "Show",
        categoryId = "1",
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        addedMillis = 100_000,
        lastSeenMillis = 1,
    )

    private suspend fun storedShow(): Series {
        test.db.sources().insert(PANEL_SOURCE)
        series.upsertAll(listOf(listedShow))
        return series.forSource(PANEL_SOURCE.id).single()
    }

    @Test
    fun `a show's look-up lands on the row as a refresh left it, changing only what it filled in`() = testing {
        val id = storedShow().id
        val refreshed = series.byId(id)!!.copy(
            name = "Show Redux",
            posterUrl = "https://example.com/new.jpg",
            categoryId = "2",
            addedMillis = 300_000,
            lastSeenMillis = 2,
        )
        series.upsertAll(listOf(refreshed))

        val saved = series.saveLookedUp(id) { it.copy(plot = "A show.", genre = "Drama") }

        val expected = refreshed.copy(plot = "A show.", genre = "Drama")
        assertThat(series.byId(id)).isEqualTo(expected)
        assertThat(saved).isEqualTo(expected)
    }

    @Test
    fun `a show deleted before its look-up is saved stays deleted`() = testing {
        val id = storedShow().id
        series.deleteForSource(PANEL_SOURCE.id)

        val saved = series.saveLookedUp(id) { it.copy(plot = "A show.") }

        assertThat(saved).isNull()
        assertThat(series.forSource(PANEL_SOURCE.id)).isEmpty()
    }
}
