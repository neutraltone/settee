/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.EpisodeTimings
import app.opentv.data.model.Series
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.TheIntroDbClient
import app.opentv.data.remote.TmdbClient
import app.opentv.data.repo.EpisodeTimingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EpisodeTimingsRepositoryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val database = Room.inMemoryDatabaseBuilder(
        app,
        OpenTvDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun close() = database.close()

    /** What each host answers, by host (TMDB by path too); `"!"` fails the request. */
    private val replies = mutableMapOf(
        "api.theintrodb.org" to """{"credits":[{"start_ms":3432884,"end_ms":null}]}""",
        "api.introdb.app" to """{"outro":{"start_ms":3431000,"end_ms":3500000}}""",
        "/3/tv/1396/external_ids" to """{"imdb_id":"tt0903747"}""",
        "/3/search/tv" to """{"results":[{"id":1396}]}""",
    )
    private val asked = mutableListOf<Request>()

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request().also { asked += it }
        val body = replies[request.url.encodedPath].takeIf { request.url.host == "api.themoviedb.org" }
            ?: replies[request.url.host]
            ?: "{}"
        if (body == "!") throw java.io.IOException("Offline")
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body == "404") 404 else 200)
            .message("Answer")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val show = Series(
        id = 5,
        sourceId = 1,
        seriesId = "s",
        name = "Breaking Bad",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2008,
        plot = null,
        tmdbId = "1396",
    )

    private var series: Series? = show
    private var now = 1_000_000_000L

    private fun repository(tmdbKey: String = "key") = EpisodeTimingsRepository(
        dao = database.episodeTimings(),
        theIntroDb = TheIntroDbClient(http),
        introDb = IntroDbClient(http),
        tmdb = TmdbClient(http, tmdbKey),
        seriesOf = { series },
        clock = { now },
    )

    private fun hosts() = asked.map { it.url.host }

    @Test
    fun `TheIntroDB's credits are used, without asking IntroDB`() = runTest {
        val known = repository().timingsFor(testEpisode(1, 1))

        assertThat(known.timings?.creditsStartMillis).isEqualTo(3_432_884)
        assertThat(known.timings?.source).isEqualTo(EpisodeTimings.THE_INTRO_DB)
        assertThat(hosts()).doesNotContain("api.introdb.app")
        assertThat(asked.first { it.url.host == "api.theintrodb.org" }.url.queryParameter("tmdb_id")).isEqualTo("1396")
    }

    @Test
    fun `without credits from TheIntroDB IntroDB is asked by the series' IMDb id, and TheIntroDB's intro kept`() =
        runTest {
            replies["api.theintrodb.org"] = """{"intro":[{"start_ms":228665,"end_ms":246498}],"credits":[]}"""

            val known = repository().timingsFor(testEpisode(1, 1))

            assertThat(
                asked.first {
                    it.url.host == "api.introdb.app"
                }.url.queryParameter("imdb_id"),
            ).isEqualTo("tt0903747")
            assertThat(known.timings?.creditsStartMillis).isEqualTo(3_431_000)
            assertThat(known.timings?.creditsEndMillis).isEqualTo(3_500_000)
            assertThat(known.timings?.introStartMillis).isEqualTo(228_665)
            assertThat(known.timings?.source).isEqualTo(EpisodeTimings.INTRO_DB)
        }

    @Test
    fun `a series without a TMDB id is found on TMDB by its name`() = runTest {
        series = show.copy(tmdbId = null)

        val known = repository().timingsFor(testEpisode(1, 1))

        assertThat(known.timings?.creditsStartMillis).isEqualTo(3_432_884)
    }

    @Test
    fun `timings looked up once are kept, and not asked for again`() = runTest {
        val timings = repository()
        timings.timingsFor(testEpisode(1, 1))
        asked.clear()

        val again = timings.timingsFor(testEpisode(1, 1))

        assertThat(again.timings?.creditsStartMillis).isEqualTo(3_432_884)
        assertThat(asked).isEmpty()
    }

    @Test
    fun `an episode neither database has timed is asked about again after a week, not before`() = runTest {
        replies["api.theintrodb.org"] = "404"
        replies["api.introdb.app"] = "{}"
        val timings = repository()
        assertThat(timings.timingsFor(testEpisode(1, 1)).timings?.creditsStartMillis).isNull()
        asked.clear()

        now += 6 * DAY_MILLIS
        timings.timingsFor(testEpisode(1, 1))
        assertThat(hosts()).doesNotContain("api.theintrodb.org")

        now += DAY_MILLIS
        timings.timingsFor(testEpisode(1, 1))
        assertThat(hosts()).contains("api.theintrodb.org")
    }

    @Test
    fun `when neither database can be reached nothing is kept, so the next start asks again`() = runTest {
        replies["api.theintrodb.org"] = "!"
        replies["api.introdb.app"] = "!"
        val timings = repository()

        assertThat(timings.timingsFor(testEpisode(1, 1)).timings).isNull()
        replies["api.theintrodb.org"] = """{"credits":[{"start_ms":3432884,"end_ms":null}]}"""

        assertThat(timings.timingsFor(testEpisode(1, 1)).timings?.creditsStartMillis).isEqualTo(3_432_884)
    }

    @Test
    fun `the show's other episodes come with the timings, for its usual credits length`() = runTest {
        val timings = repository()
        timings.timingsFor(testEpisode(1, 1))
        timings.timingsFor(testEpisode(1, 2))

        val known = timings.timingsFor(testEpisode(1, 3))

        assertThat(known.others.map { it.episode }).containsExactly(1, 2)
    }

    @Test
    fun `with no TMDB id, no way to look one up and no series nothing is asked`() = runTest {
        series = show.copy(tmdbId = null)
        assertThat(repository(tmdbKey = "").timingsFor(testEpisode(1, 1)).timings).isNull()

        series = null
        assertThat(repository().timingsFor(testEpisode(1, 1)).timings).isNull()

        assertThat(hosts()).containsNoneOf("api.theintrodb.org", "api.introdb.app")
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}
