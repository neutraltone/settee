/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Source
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Stream and catch-up URLs carry the login in their path, so a password with `#`, `?`, `/`, `%` or a
 * space has to be percent-encoded there, or the URL ends early, gains a segment or doesn't parse,
 * and nothing plays even though Test and sync, which send the login as query parameters, work.
 */
class XtreamStreamUrlsTest {

    private val api = XtreamApi(OkHttpClient())

    @Test
    fun `a live channel's URL carries a login of awkward characters as typed`() {
        val url = api.liveStreamUrl(AWKWARD_SOURCE, "9").toHttpUrl()

        assertThat(url.pathSegments).containsExactly("live", AWKWARD_USER, AWKWARD, "9.m3u8").inOrder()
    }

    @Test
    fun `a film's URL carries a login of awkward characters as typed`() {
        val url = api.vodStreamUrl(AWKWARD_SOURCE, "7", "mkv").toHttpUrl()

        assertThat(url.pathSegments).containsExactly("movie", AWKWARD_USER, AWKWARD, "7.mkv").inOrder()
    }

    @Test
    fun `an episode's URL carries a login of awkward characters as typed`() {
        val url = api.seriesStreamUrl(AWKWARD_SOURCE, "5", null).toHttpUrl()

        assertThat(url.pathSegments).containsExactly("series", AWKWARD_USER, AWKWARD, "5.mp4").inOrder()
    }

    @Test
    fun `a catch-up URL carries a login of awkward characters as typed`() {
        val source = AWKWARD_SOURCE.copy(serverTimeZone = "UTC")
        val url = api.catchupUrl(source, "9", CATCHUP_START, 60).toHttpUrl()

        assertThat(url.pathSegments)
            .containsExactly("timeshift", AWKWARD_USER, AWKWARD, "60", "2026-11-18:20-30", "9.ts").inOrder()
    }

    @Test
    fun `a plain login gives the same URLs as before`() {
        assertThat(everyKind(PANEL_SOURCE)).containsExactly(
            "http://example.com/live/u/p/9.m3u8",
            "http://example.com/movie/u/p/7.mp4",
            "http://example.com/series/u/p/5.mkv",
            "http://example.com/timeshift/u/p/60/2026-11-18:20-30/9.ts",
        ).inOrder()
    }

    @Test
    fun `a path in the server address stays in front of the stream's`() {
        assertThat(everyKind(PANEL_SOURCE.copy(url = "http://example.com:8080/sub"))).containsExactly(
            "http://example.com:8080/sub/live/u/p/9.m3u8",
            "http://example.com:8080/sub/movie/u/p/7.mp4",
            "http://example.com:8080/sub/series/u/p/5.mkv",
            "http://example.com:8080/sub/timeshift/u/p/60/2026-11-18:20-30/9.ts",
        ).inOrder()
    }

    /** One URL of each kind, a live channel, a film, an episode and a catch-up, from [source] on UTC. */
    private fun everyKind(source: Source): List<String> = source.copy(serverTimeZone = "UTC").let {
        listOf(
            api.liveStreamUrl(it, "9"),
            api.vodStreamUrl(it, "7", null),
            api.seriesStreamUrl(it, "5", "mkv"),
            api.catchupUrl(it, "9", CATCHUP_START, 60),
        )
    }

    private companion object {
        const val AWKWARD_USER = "u #?/%"
        const val AWKWARD = "p#1/ ?%"
        val AWKWARD_SOURCE = PANEL_SOURCE.copy(username = AWKWARD_USER, password = AWKWARD)

        /** 18 Nov 2026, 20:30 UTC: `2026-11-18:20-30` in a panel on UTC. */
        const val CATCHUP_START = 1_795_033_800_000L
    }
}
