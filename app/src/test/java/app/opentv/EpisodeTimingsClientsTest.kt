/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.EpisodeTimings
import app.opentv.data.remote.IntroDbClient
import app.opentv.data.remote.TheIntroDbClient
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * TheIntroDB and IntroDB, read the way each answers. The bodies are trimmed from real answers fetched
 * on 17 Nov 2026.
 */
class EpisodeTimingsClientsTest {

    private val asked = mutableListOf<Request>()

    /** A client whose every request gets [code] and [body]; `"!"` fails it as a dropped connection would. */
    private fun answering(body: String, code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request().also { asked += it }
        if (body == "!") throw java.io.IOException("Connection dropped")
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Answer")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    @Test
    fun `TheIntroDB is asked by TMDB id, season and episode, without the stream's length`() {
        TheIntroDbClient(answering("{}")).timings(tmdbId = "1396", season = 3, episode = 7)

        val url = asked.single().url
        assertThat(url.host).isEqualTo("api.theintrodb.org")
        assertThat(url.queryParameter("tmdb_id")).isEqualTo("1396")
        assertThat(url.queryParameter("season")).isEqualTo("3")
        assertThat(url.queryParameter("episode")).isEqualTo("7")
        // Asked with the length, it picked another cut for Moon Knight and put the credits 107 s early.
        assertThat(url.queryParameter("duration_ms")).isNull()
    }

    @Test
    fun `TheIntroDB's intro and credits are read, with credits that run to the end`() {
        val body = """{"tmdb_id":1396,"type":"tv","season":1,"episode":1,
            "intro":[{"start_ms":228665,"end_ms":246498}],"credits":[{"start_ms":3432884,"end_ms":null}]}"""

        val timings = TheIntroDbClient(answering(body)).timings("1396", 1, 1).getOrThrow()

        assertThat(timings).isEqualTo(
            EpisodeTimings(
                show = "tmdb:1396",
                season = 1,
                episode = 1,
                introStartMillis = 228_665,
                introEndMillis = 246_498,
                creditsStartMillis = 3_432_884,
                creditsEndMillis = null,
                source = EpisodeTimings.THE_INTRO_DB,
            ),
        )
    }

    @Test
    fun `an intro TheIntroDB starts at null starts at the beginning, and one of no length is none`() {
        val fromTheTop = """{"intro":[{"start_ms":null,"end_ms":23000}],"credits":[]}"""
        val empty = """{"intro":[{"start_ms":0,"end_ms":0}],"credits":[{"start_ms":3312700,"end_ms":null}]}"""

        val top = TheIntroDbClient(answering(fromTheTop)).timings("1", 1, 1).getOrThrow()
        val none = TheIntroDbClient(answering(empty)).timings("1", 1, 1).getOrThrow()

        assertThat(top?.introStartMillis).isEqualTo(0)
        assertThat(top?.introEndMillis).isEqualTo(23_000)
        assertThat(none?.introStartMillis).isNull()
        assertThat(none?.introEndMillis).isNull()
    }

    @Test
    fun `credits TheIntroDB times in two parts have a scene between them`() {
        val body = """{"credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}]}"""

        val timings = TheIntroDbClient(answering(body)).timings("1", 1, 1).getOrThrow()

        assertThat(timings?.creditsStartMillis).isEqualTo(5_801_777)
        assertThat(timings?.creditsEndMillis).isEqualTo(6_371_111)
        assertThat(timings?.sceneAfterCredits).isTrue()
    }

    @Test
    fun `an episode TheIntroDB doesn't have is an answer, not a failure`() {
        val body = """{"error":"media not found for provided tmdb_id","code":"media_not_found"}"""

        val result = TheIntroDbClient(answering(body, code = 404)).timings("1", 1, 1)

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `TheIntroDB not answering, or answering with a page that isn't its JSON, is a failure`() {
        assertThat(TheIntroDbClient(answering("!")).timings("1", 1, 1).isFailure).isTrue()
        assertThat(TheIntroDbClient(answering("{}", code = 429)).timings("1", 1, 1).isFailure).isTrue()
        assertThat(
            TheIntroDbClient(
                answering("<!DOCTYPE html><title>Attention Required!</title>"),
            ).timings("1", 1, 1).isFailure,
        )
            .isTrue()
    }

    @Test
    fun `IntroDB is asked by the series' IMDb id, season and episode`() {
        IntroDbClient(answering("{}")).timings(imdbId = "tt0903747", season = 1, episode = 1)

        val url = asked.single().url
        assertThat(url.host).isEqualTo("api.introdb.app")
        assertThat(url.queryParameter("imdb_id")).isEqualTo("tt0903747")
        assertThat(url.queryParameter("season")).isEqualTo("1")
        assertThat(url.queryParameter("episode")).isEqualTo("1")
    }

    @Test
    fun `IntroDB's intro, recap, outro and what it has confirmed aren't there are read`() {
        val body = """{"imdb_id":"tt0903747","season":3,"episode":7,
            "intro":{"start_ms":222000,"end_ms":239000,"confidence":1,"submission_count":2},
            "recap":null,
            "outro":{"start_ms":2786000,"end_ms":2848000,"confidence":1,"submission_count":1},
            "post_credits":null,"no_intro":false,"no_recap":true,"no_outro":false}"""

        val timings = IntroDbClient(answering(body)).timings("tt0903747", 3, 7).getOrThrow()

        assertThat(timings).isEqualTo(
            EpisodeTimings(
                show = "imdb:tt0903747",
                season = 3,
                episode = 7,
                introStartMillis = 222_000,
                introEndMillis = 239_000,
                creditsStartMillis = 2_786_000,
                creditsEndMillis = 2_848_000,
                noRecap = true,
                source = EpisodeTimings.INTRO_DB,
            ),
        )
    }

    @Test
    fun `IntroDB's post-credits segment means a scene after the credits`() {
        val body = """{"outro":{"start_ms":3000000,"end_ms":3060000},
            "post_credits":{"start_ms":3070000,"end_ms":3100000}}"""

        val timings = IntroDbClient(answering(body)).timings("tt1", 1, 1).getOrThrow()

        assertThat(timings?.sceneAfterCredits).isTrue()
    }

    @Test
    fun `IntroDB confirming there are no credits is kept`() {
        val body = """{"intro":null,"recap":null,"outro":null,"post_credits":null,
            "no_intro":false,"no_recap":false,"no_outro":true}"""

        val timings = IntroDbClient(answering(body)).timings("tt1", 1, 1).getOrThrow()

        assertThat(timings?.noCredits).isTrue()
    }

    @Test
    fun `an IntroDB answer with nothing timed or confirmed is no timings`() {
        val body = """{"intro":null,"recap":null,"outro":null,"post_credits":null,
            "no_intro":false,"no_recap":false,"no_outro":false}"""

        assertThat(IntroDbClient(answering(body)).timings("tt1", 1, 1).getOrThrow()).isNull()
        assertThat(IntroDbClient(answering("{}", code = 404)).timings("tt1", 1, 1).getOrThrow()).isNull()
    }

    @Test
    fun `IntroDB not answering is a failure`() {
        assertThat(IntroDbClient(answering("!")).timings("tt1", 1, 1).isFailure).isTrue()
        assertThat(IntroDbClient(answering("{}", code = 503)).timings("tt1", 1, 1).isFailure).isTrue()
    }
}
