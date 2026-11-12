/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.remote.TmdbCheck
import app.opentv.data.remote.TmdbClient
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TMDB takes the viewer's TMDB Key in either of the two forms its site hands out: the "API Key"
 * (v3), as an `api_key` parameter, or the "API Read Access Token" (v4), as a Bearer header. Every
 * kind of call has to send whichever the viewer saved, the way TMDB wants it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TmdbClientTest {

    private val settings = AppSettings.get(ApplicationProvider.getApplicationContext())
    private val panel = FakePanel(
        "/3/search/movie" to """{"results": [{"id": 11}]}""",
        "/3/search/tv" to """{"results": [{"id": 22}]}""",
        "/3/movie/11" to """{"id": 11, "imdb_id": "tt0000011", "overview": "A film."}""",
        "/3/tv/22" to """{"id": 22, "overview": "A show."}""",
        "/3/tv/22/external_ids" to """{"imdb_id": "tt0000022"}""",
    )
    private val tmdb = TmdbClient(panel.http, settings)

    @After
    fun forgetKey() = settings.setTmdbApiKey("")

    /** Every request that went to TMDB. */
    private fun toTmdb() = panel.requests.filter { it.url.host == "api.themoviedb.org" }

    private fun assertSentAsApiKey(key: String) {
        assertThat(toTmdb()).isNotEmpty()
        toTmdb().forEach {
            assertThat(it.url.queryParameter("api_key")).isEqualTo(key)
            assertThat(it.header("Authorization")).isNull()
        }
    }

    private fun assertSentAsBearer(token: String) {
        assertThat(toTmdb()).isNotEmpty()
        toTmdb().forEach {
            assertThat(it.header("Authorization")).isEqualTo("Bearer $token")
            assertThat(it.url.queryParameter("api_key")).isNull()
        }
    }

    @Test
    fun `an API Key fills in a film as an api_key parameter`() {
        settings.setTmdbApiKey(API_KEY)

        val meta = tmdb.movieMeta("A Film", year = null, tmdbId = null)

        assertThat(meta?.overview).isEqualTo("A film.")
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token fills in a film as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        val meta = tmdb.movieMeta("A Film", year = null, tmdbId = null)

        assertThat(meta?.overview).isEqualTo("A film.")
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `the background pass sends a Read Access Token as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = null)

        assertThat(meta.getOrNull()?.overview).isEqualTo("A film.")
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `an API Key fills in a series as an api_key parameter`() {
        settings.setTmdbApiKey(API_KEY)

        val meta = tmdb.seriesMeta("A Show", year = null, tmdbId = null)

        assertThat(meta?.overview).isEqualTo("A show.")
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token fills in a series as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        val meta = tmdb.seriesMeta("A Show", year = null, tmdbId = null)

        assertThat(meta?.overview).isEqualTo("A show.")
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `an API Key finds a film's IMDb match as an api_key parameter`() {
        settings.setTmdbApiKey(API_KEY)

        assertThat(tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = null)).isEqualTo("tt0000011")
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token finds a series' IMDb match as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        assertThat(tmdb.imdbId("A Show", year = null, isMovie = false, tmdbId = null)).isEqualTo("tt0000022")
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `with no key TMDB is never asked`() {
        tmdb.movieMeta("A Film", year = null, tmdbId = null)
        tmdb.seriesMeta("A Show", year = null, tmdbId = null)
        tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = null)

        assertThat(toTmdb()).isEmpty()
    }

    @Test
    fun `a key TMDB accepts checks out`() {
        panel.replies["/3/authentication"] = """{"success": true}"""

        assertThat(tmdb.check(API_KEY)).isEqualTo(TmdbCheck.Accepted)
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token is checked as a Bearer header`() {
        panel.replies["/3/authentication"] = """{"success": true}"""

        assertThat(tmdb.check(READ_TOKEN)).isEqualTo(TmdbCheck.Accepted)
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `a key TMDB turns down is a wrong key`() {
        panel.replies["/3/authentication"] = "!401"

        assertThat(tmdb.check(API_KEY)).isEqualTo(TmdbCheck.Rejected)
    }

    @Test
    fun `no answer from TMDB is not taken for a wrong key`() {
        panel.replies["/3/authentication"] = "!"

        assertThat(tmdb.check(API_KEY)).isInstanceOf(TmdbCheck.Unreachable::class.java)
    }

    @Test
    fun `TMDB having trouble is not taken for a wrong key`() {
        panel.replies["/3/authentication"] = "!503"

        assertThat(tmdb.check(API_KEY)).isInstanceOf(TmdbCheck.Unreachable::class.java)
    }

    @Test
    fun `checking a key doesn't save it`() {
        panel.replies["/3/authentication"] = """{"success": true}"""

        tmdb.check(API_KEY)

        assertThat(settings.tmdbApiKey.value).isEmpty()
    }

    private companion object {
        /** Placeholders in the shape of each (a v3 key is 32 hex digits, a v4 token a JWT), built so gitleaks sees no secret. */
        val API_KEY = "f".repeat(32)
        val READ_TOKEN = listOf("eyJhbGci", "eyJzdWIi", "c2lnbmF0").joinToString(".")
    }
}
