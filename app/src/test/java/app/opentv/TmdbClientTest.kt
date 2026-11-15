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
import org.robolectric.shadows.ShadowLog

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

        val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = null).getOrNull()

        assertThat(meta?.overview).isEqualTo("A film.")
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token fills in a film as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = null).getOrNull()

        assertThat(meta?.overview).isEqualTo("A film.")
        assertSentAsBearer(READ_TOKEN)
    }

    @Test
    fun `an API Key fills in a series as an api_key parameter`() {
        settings.setTmdbApiKey(API_KEY)

        val meta = tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = null).getOrNull()

        assertThat(meta?.overview).isEqualTo("A show.")
        assertSentAsApiKey(API_KEY)
    }

    @Test
    fun `a Read Access Token fills in a series as a Bearer header`() {
        settings.setTmdbApiKey(READ_TOKEN)

        val meta = tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = null).getOrNull()

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
        tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = null).getOrNull()
        tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = null).getOrNull()
        tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = null)

        assertThat(toTmdb()).isEmpty()
    }

    /** The path of every request that went to TMDB, without the key. */
    private fun pathsAsked() = toTmdb().map { it.url.encodedPath }

    @Test
    fun `a film a panel gave TMDB id 0 is searched for by its title`() {
        settings.setTmdbApiKey(API_KEY)

        val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = "0").getOrNull()

        assertThat(meta?.overview).isEqualTo("A film.")
        assertThat(meta?.tmdbId).isEqualTo("11")
        assertThat(pathsAsked()).contains("/3/search/movie")
        assertThat(pathsAsked()).doesNotContain("/3/movie/0")
    }

    @Test
    fun `a series a panel gave TMDB id 0 is searched for by its title`() {
        settings.setTmdbApiKey(API_KEY)

        val meta = tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = "0").getOrNull()

        assertThat(meta?.overview).isEqualTo("A show.")
        assertThat(pathsAsked()).contains("/3/search/tv")
        assertThat(pathsAsked()).doesNotContain("/3/tv/0")
    }

    @Test
    fun `a film a panel gave TMDB id 0 finds its IMDb match by its title`() {
        settings.setTmdbApiKey(API_KEY)

        assertThat(tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = "0")).isEqualTo("tt0000011")
        assertThat(pathsAsked()).contains("/3/search/movie")
        assertThat(pathsAsked()).doesNotContain("/3/movie/0")
    }

    @Test
    fun `a TMDB id of 0 with spaces round it, or only spaces, is searched for like 0`() {
        settings.setTmdbApiKey(API_KEY)

        for (id in listOf(" 0 ", "  ")) {
            panel.requests.clear()

            val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = id).getOrNull()

            assertThat(meta?.tmdbId).isEqualTo("11")
            assertThat(pathsAsked()).contains("/3/search/movie")
            assertThat(pathsAsked().filter { it.startsWith("/3/movie/") }).containsExactly("/3/movie/11")
        }
    }

    @Test
    fun `a film with a real TMDB id is looked up by it, not searched for`() {
        settings.setTmdbApiKey(API_KEY)
        panel.replies["/3/movie/603"] = """{"id": 603, "overview": "The real one."}"""

        val meta = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = "603").getOrNull()

        assertThat(meta?.overview).isEqualTo("The real one.")
        assertThat(pathsAsked()).containsExactly("/3/movie/603")
    }

    @Test
    fun `a title's recommendations come in TMDB's order with its details, in the one request`() {
        settings.setTmdbApiKey(API_KEY)
        panel.replies["/3/tv/603"] =
            """{"id": 603, "recommendations": {"results": [{"id": 9}, {"id": 4}, {"id": 12}]}}"""

        val meta = tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = "603").getOrNull()

        assertThat(meta?.recommendations).containsExactly("9", "4", "12").inOrder()
        assertThat(pathsAsked()).containsExactly("/3/tv/603")
        assertThat(toTmdb().single().url.queryParameter("append_to_response")).isEqualTo("credits,recommendations")
    }

    @Test
    fun `a real TMDB id TMDB doesn't know is no match, not searched for`() {
        settings.setTmdbApiKey(API_KEY)
        panel.replies["/3/movie/603"] = "!404"

        val answer = tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = "603")

        assertThat(answer.isSuccess).isTrue()
        assertThat(answer.getOrNull()).isNull()
        assertThat(pathsAsked()).containsExactly("/3/movie/603")
    }

    @Test
    fun `a TMDB request that fails logs nothing of the key`() {
        settings.setTmdbApiKey(API_KEY)
        // As a client whose exception message holds the request's URL, api_key and all.
        panel.replies["/3/search/movie"] = "!host"

        tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = null)

        val logged = ShadowLog.getLogsForTag("TmdbClient")
        assertThat(logged).isNotEmpty()
        logged.forEach {
            assertThat(it.msg).doesNotContain(API_KEY)
            assertThat(it.throwable?.message.orEmpty()).doesNotContain(API_KEY)
        }
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
        /**
         * Placeholders in the shape of each (a v3 key is 32 hex digits, a v4 token a JWT), built so
         * gitleaks sees no secret.
         */
        val API_KEY = "f".repeat(32)
        val READ_TOKEN = listOf("eyJhbGci", "eyJzdWIi", "c2lnbmF0").joinToString(".")
    }
}
