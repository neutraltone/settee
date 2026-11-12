/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Series
import app.opentv.data.remote.TmdbMeta
import app.opentv.data.repo.SeriesTmdb
import app.opentv.data.repo.SeriesTmdbPass
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TMDB filling in series, with the viewer's TMDB Key: the background pass asks about each series
 * once and again six months on, and opening a series asks at once when its details are missing or
 * that old. Nothing TMDB filled in is ever cleared without a key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SeriesTmdbTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val panel = test.panel

    @After
    fun close() {
        test.settings.setTmdbApiKey("")
        test.close()
    }

    private fun series(
        id: String,
        added: Long = 1,
        plot: String? = null,
        tmdbAt: Long? = null,
        backdrop: String? = null,
        cast: String? = null,
    ) = Series(
        sourceId = PANEL_SOURCE.id,
        seriesId = id,
        name = "Show $id",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = plot,
        addedMillis = added,
        backdropUrl = backdrop,
        cast = cast,
        // A genre counts as the provider having answered, so opening it doesn't ask the panel.
        genre = "Drama",
        tmdbAnsweredAt = tmdbAt,
    )

    /** A series with everything TMDB would fill already there. */
    private fun complete(id: String, tmdbAt: Long?) =
        series(id, plot = "A show.", tmdbAt = tmdbAt, backdrop = "http://example.com/b.jpg", cast = "Someone")

    private suspend fun save(vararg series: Series) {
        test.db.sources().insert(PANEL_SOURCE)
        test.db.series().upsertAll(series.toList())
    }

    private suspend fun stored(id: String): Series =
        test.db.series().forSource(PANEL_SOURCE.id).single { it.seriesId == id }

    // ---- The background pass --------------------------------------------------------------------

    private var asked = 0

    private fun pass(ready: Boolean = true, answer: (Series) -> Result<TmdbMeta?>) = SeriesTmdbPass(
        seriesDao = test.db.series(),
        tmdb = object : SeriesTmdb {
            override val ready = ready

            override suspend fun lookUp(series: Series): Result<TmdbMeta?> {
                asked++
                return answer(series)
            }
        },
        pauseMillis = 0,
        clock = { NOW },
    )

    @Test
    fun `the background pass fills in a series from TMDB, and dates the answer`() = runTest {
        save(series("7"))

        pass { Result.success(TmdbMeta(overview = "A show.")) }.fillAll(PANEL_SOURCE)

        assertThat(stored("7").plot).isEqualTo("A show.")
        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW)
    }

    @Test
    fun `a series TMDB has no match for isn't asked about again`() = runTest {
        save(series("7"))
        val noMatch = pass { Result.success(null) }

        noMatch.fillAll(PANEL_SOURCE)
        noMatch.fillAll(PANEL_SOURCE)

        assertThat(asked).isEqualTo(1)
    }

    @Test
    fun `a series TMDB answered about over six months ago is asked again`() = runTest {
        save(series("old", tmdbAt = NOW - SEVEN_MONTHS), series("recent", tmdbAt = NOW - FIVE_MONTHS))
        val seen = mutableListOf<String>()

        pass {
            seen += it.seriesId
            Result.success(null)
        }.fillAll(PANEL_SOURCE)

        assertThat(seen).containsExactly("old")
        assertThat(stored("old").tmdbAnsweredAt).isEqualTo(NOW)
    }

    @Test
    fun `asking TMDB again leaves the provider's own synopsis`() = runTest {
        save(series("7", plot = "The provider's.", tmdbAt = NOW - SEVEN_MONTHS))

        pass { Result.success(TmdbMeta(overview = "TMDB's.", cast = "Someone")) }.fillAll(PANEL_SOURCE)

        assertThat(stored("7").plot).isEqualTo("The provider's.")
        assertThat(stored("7").cast).isEqualTo("Someone")
    }

    @Test
    fun `a series TMDB couldn't be reached about is asked again next time, still dated as before`() = runTest {
        save(series("7", tmdbAt = NOW - SEVEN_MONTHS))
        val offline = pass { Result.failure(Exception("offline")) }

        offline.fillAll(PANEL_SOURCE)
        offline.fillAll(PANEL_SOURCE)

        assertThat(asked).isEqualTo(2)
        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW - SEVEN_MONTHS)
    }

    @Test
    fun `without a key the pass asks nothing and clears nothing`() = runTest {
        save(series("7", plot = "A show.", tmdbAt = NOW - SEVEN_MONTHS))

        pass(ready = false) { Result.success(TmdbMeta(overview = "TMDB's.")) }.fillAll(PANEL_SOURCE)

        assertThat(asked).isEqualTo(0)
        assertThat(stored("7").plot).isEqualTo("A show.")
        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW - SEVEN_MONTHS)
    }

    @Test
    fun `the pass works through series a batch at a time until none is left`() = runTest {
        save(*(1..5).map { series("$it", added = it.toLong()) }.toTypedArray())

        SeriesTmdbPass(
            seriesDao = test.db.series(),
            tmdb = object : SeriesTmdb {
                override val ready = true

                override suspend fun lookUp(series: Series): Result<TmdbMeta?> {
                    asked++
                    return Result.success(null)
                }
            },
            count = 2,
            pauseMillis = 0,
            clock = { NOW },
        ).fillAll(PANEL_SOURCE)

        assertThat(asked).isEqualTo(5)
    }

    // ---- Opening a series ----------------------------------------------------------------------

    /** TMDB answering a search for any show with one whose synopsis is [overview]. */
    private fun tmdbAnswers(overview: String) {
        test.settings.setTmdbApiKey(KEY)
        panel.replies["/3/search/tv"] = """{"results": [{"id": 22}]}"""
        panel.replies["/3/tv/22"] = """{"id": 22, "overview": "$overview", "backdrop_path": "/b.jpg"}"""
    }

    private fun askedTmdb() = panel.requests.any { it.url.host == "api.themoviedb.org" }

    @Test
    fun `opening a series with details missing asks TMDB for them`() = runTest {
        save(series("7"))
        tmdbAnswers("A show.")

        val opened = test.catalog.seriesDetail(stored("7").id, now = NOW)

        assertThat(opened?.plot).isEqualTo("A show.")
        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW)
    }

    @Test
    fun `opening a series TMDB answered about recently asks nothing`() = runTest {
        save(complete("7", tmdbAt = NOW - FIVE_MONTHS))
        tmdbAnswers("A show.")

        test.catalog.seriesDetail(stored("7").id, now = NOW)

        assertThat(askedTmdb()).isFalse()
    }

    @Test
    fun `opening a series TMDB answered about over six months ago asks again`() = runTest {
        save(complete("7", tmdbAt = NOW - SEVEN_MONTHS))
        tmdbAnswers("A show.")

        test.catalog.seriesDetail(stored("7").id, now = NOW)

        assertThat(askedTmdb()).isTrue()
        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW)
    }

    @Test
    fun `opening a series filled in by the provider, never asked of TMDB, asks it`() = runTest {
        save(complete("7", tmdbAt = null))
        tmdbAnswers("A show.")

        test.catalog.seriesDetail(stored("7").id, now = NOW)

        assertThat(askedTmdb()).isTrue()
    }

    @Test
    fun `opening a series without a key asks nothing and keeps what TMDB filled in`() = runTest {
        save(complete("7", tmdbAt = NOW - SEVEN_MONTHS))

        val opened = test.catalog.seriesDetail(stored("7").id, now = NOW)

        assertThat(askedTmdb()).isFalse()
        assertThat(opened?.plot).isEqualTo("A show.")
    }

    // ---- A Catalogue Refresh ------------------------------------------------------------------

    @Test
    fun `a refresh of the show list keeps when TMDB last answered`() = runTest {
        panel.replies["get_series"] = """[{"series_id": 7, "name": "Show 7"}]"""
        test.syncVod()
        test.db.series().upsertAll(listOf(stored("7").copy(tmdbAnsweredAt = NOW)))

        test.syncVod()

        assertThat(stored("7").tmdbAnsweredAt).isEqualTo(NOW)
    }

    @Test
    fun `a refresh of the film list keeps when TMDB last answered`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 7, "name": "Film 7", "container_extension": "mp4"}]"""
        test.syncVod()
        val film = test.db.movies().forSource(PANEL_SOURCE.id).single()
        test.db.movies().upsertAll(listOf(film.copy(tmdbLookedUp = true, tmdbAnsweredAt = NOW)))

        test.syncVod()

        assertThat(test.db.movies().forSource(PANEL_SOURCE.id).single().tmdbAnsweredAt).isEqualTo(NOW)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val NOW = 400 * DAY
        const val FIVE_MONTHS = 152 * DAY
        const val SEVEN_MONTHS = 213 * DAY

        /** A placeholder, low-entropy so gitleaks sees no secret. */
        val KEY = "c".repeat(32)
    }
}
