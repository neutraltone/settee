/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.remote.SubtitleSearch
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The OpenSubtitles client, against [FakeOpenSubtitles]. Placeholder key, account and token only. */
class OpenSubtitlesClientTest {

    private val site = FakeOpenSubtitles()
    private val client = OpenSubtitlesClient(site.http, apiKey = "k", appVersion = "0.5.0")

    private fun searched() = site.requests.single { it.url.encodedPath.endsWith("/subtitles") }.url

    @Test
    fun `a film Settee has matched is searched by its TMDB id`() {
        client.search(SubtitleSearch.Film(title = "Heat", year = 1995, tmdbId = "949"))

        val url = searched()
        assertThat(url.queryParameter("tmdb_id")).isEqualTo("949")
        assertThat(url.queryParameter("query")).isNull()
        assertThat(url.queryParameter("type")).isEqualTo("movie")
    }

    @Test
    fun `a film without a TMDB id is searched by title and year`() {
        client.search(SubtitleSearch.Film(title = "Heat", year = 1995, tmdbId = null))

        val url = searched()
        assertThat(url.queryParameter("query")).isEqualTo("Heat")
        assertThat(url.queryParameter("year")).isEqualTo("1995")
        assertThat(url.queryParameter("tmdb_id")).isNull()
    }

    @Test
    fun `an episode Settee has matched is searched by its show's TMDB id, season and episode`() {
        client.search(SubtitleSearch.Episode(show = "Taxi", showTmdbId = "1001", season = 2, episode = 5))

        val url = searched()
        assertThat(url.queryParameter("parent_tmdb_id")).isEqualTo("1001")
        assertThat(url.queryParameter("season_number")).isEqualTo("2")
        assertThat(url.queryParameter("episode_number")).isEqualTo("5")
        assertThat(url.queryParameter("query")).isNull()
        assertThat(url.queryParameter("type")).isEqualTo("episode")
    }

    @Test
    fun `an episode without a TMDB id is searched by show name, season and episode`() {
        client.search(SubtitleSearch.Episode(show = "Taxi", showTmdbId = null, season = 2, episode = 5))

        val url = searched()
        assertThat(url.queryParameter("query")).isEqualTo("Taxi")
        assertThat(url.queryParameter("season_number")).isEqualTo("2")
        assertThat(url.queryParameter("episode_number")).isEqualTo("5")
        assertThat(url.queryParameter("parent_tmdb_id")).isNull()
    }

    @Test
    fun `every request carries Settee's key and its User-Agent`() {
        client.search(SubtitleSearch.Film(title = "Heat", year = null, tmdbId = null))
        client.signIn("viewer", "secret")

        assertThat(site.requests).hasSize(2)
        site.requests.forEach {
            assertThat(it.header("Api-Key")).isEqualTo("k")
            assertThat(it.header("User-Agent")).isEqualTo("Settee v0.5.0")
        }
    }

    @Test
    fun `results carry language, release, download count and the hearing impaired and forced markers`() {
        site.replies["GET /subtitles"] = """
            {"data": [
              {"id": "1", "attributes": {"language": "en", "release": "Heat.1995.1080p", "download_count": 900,
                "hearing_impaired": true, "foreign_parts_only": false, "files": [{"file_id": 11}]}},
              {"id": "2", "attributes": {"language": "fr", "release": "Heat.1995.BluRay", "download_count": 40,
                "hearing_impaired": false, "foreign_parts_only": true, "files": [{"file_id": 22}]}},
              {"id": "3", "attributes": {"language": "de", "release": "Heat", "download_count": 7,
                "files": [{"file_id": 33}]}},
              {"id": "4", "attributes": {"language": "es", "release": "No file"}}
            ]}
        """.trimIndent()

        val results = client.search(SubtitleSearch.Film(title = "Heat", year = 1995, tmdbId = "949")).getOrThrow()

        assertThat(results.map { it.fileId }).containsExactly(11L, 22L, 33L).inOrder()
        assertThat(results[0].language).isEqualTo("en")
        assertThat(results[0].release).isEqualTo("Heat.1995.1080p")
        assertThat(results[0].downloads).isEqualTo(900)
        assertThat(results.map { it.kind })
            .containsExactly(SubtitleKind.HEARING_IMPAIRED, SubtitleKind.FORCED, SubtitleKind.STANDARD)
            .inOrder()
    }

    @Test
    fun `downloading asks for a link, then fetches the file from it`() {
        site.replies["POST /download"] =
            """{"link": "https://dl.opensubtitles.example/file/11.srt", "file_name": "Heat.srt", "remaining": 4}"""
        site.replies["GET /file/11.srt"] = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

        val file = client.download(fileId = 11, token = "t").getOrThrow()

        assertThat(file.text).contains("Hello")
        assertThat(file.remaining).isEqualTo(4)
        val asked = site.requests.first { it.url.encodedPath.endsWith("/download") }
        assertThat(asked.header("Authorization")).isEqualTo("Bearer t")
        assertThat(site.bodies.first()).contains("\"file_id\":11")
    }

    @Test
    fun `reaching the daily limit says so, with when the counter resets`() {
        site.replies["POST /download"] =
            """!406 {"message": "You have downloaded your allowed 5 subtitles", "remaining": 0,
               "reset_time_utc": "2026-11-14T00:00:00.000Z"}"""

        val failure = client.download(fileId = 11, token = null).exceptionOrNull()

        assertThat(failure).isInstanceOf(OpenSubtitlesFailure.DownloadLimit::class.java)
        failure as OpenSubtitlesFailure.DownloadLimit
        assertThat(failure.signedIn).isFalse()
        assertThat(failure.resetsAt).isEqualTo(java.time.Instant.parse("2026-11-14T00:00:00Z").toEpochMilli())
    }

    @Test
    fun `signing in returns the token and the account's allowance`() {
        site.replies["POST /login"] =
            """{"user": {"allowed_downloads": 20, "remaining_downloads": 18}, "token": "t",
               "base_url": "api.opensubtitles.com"}"""

        val session = client.signIn("viewer", "secret").getOrThrow()

        assertThat(session.token).isEqualTo("t")
        assertThat(session.remaining).isEqualTo(18)
        assertThat(site.bodies.single()).contains("\"username\":\"viewer\"")
    }

    @Test
    fun `a wrong username or password is told apart from not reaching OpenSubtitles`() {
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""
        assertThat(client.signIn("viewer", "wrong").exceptionOrNull())
            .isInstanceOf(OpenSubtitlesFailure.SignInRejected::class.java)

        site.replies["POST /login"] = "!"
        assertThat(client.signIn("viewer", "secret").exceptionOrNull())
            .isInstanceOf(OpenSubtitlesFailure.Unreachable::class.java)
    }

    @Test
    fun `a token OpenSubtitles no longer accepts is reported as such`() {
        site.replies["GET /infos/user"] = """!401 {"message": "invalid token"}"""

        assertThat(client.account(token = "old").exceptionOrNull())
            .isInstanceOf(OpenSubtitlesFailure.TokenRejected::class.java)
    }

    @Test
    fun `the account's downloads left today come from its user info`() {
        site.replies["GET /infos/user"] = """{"data": {"allowed_downloads": 20, "remaining_downloads": 7}}"""

        assertThat(client.account(token = "t").getOrThrow().remaining).isEqualTo(7)
    }
}
