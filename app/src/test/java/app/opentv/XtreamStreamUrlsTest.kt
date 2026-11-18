/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

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
        val url = api.catchupUrl(source, "9", NOV_18_2030_UTC, 60).toHttpUrl()

        assertThat(url.pathSegments)
            .containsExactly("timeshift", AWKWARD_USER, AWKWARD, "60", "2026-11-18:20-30", "9.ts").inOrder()
    }

    @Test
    fun `a plain login gives the same URLs as before`() {
        assertThat(api.liveStreamUrl(PANEL_SOURCE, "9")).isEqualTo("http://example.com/live/u/p/9.m3u8")
        assertThat(api.vodStreamUrl(PANEL_SOURCE, "7", null)).isEqualTo("http://example.com/movie/u/p/7.mp4")
        assertThat(api.seriesStreamUrl(PANEL_SOURCE, "5", "mkv")).isEqualTo("http://example.com/series/u/p/5.mkv")
        assertThat(api.catchupUrl(PANEL_SOURCE.copy(serverTimeZone = "UTC"), "9", NOV_18_2030_UTC, 60))
            .isEqualTo("http://example.com/timeshift/u/p/60/2026-11-18:20-30/9.ts")
    }

    @Test
    fun `a path in the server address stays in front of the stream's`() {
        val source = PANEL_SOURCE.copy(url = "http://example.com:8080/sub", serverTimeZone = "UTC")

        assertThat(api.liveStreamUrl(source, "9")).isEqualTo("http://example.com:8080/sub/live/u/p/9.m3u8")
        assertThat(api.vodStreamUrl(source, "7", null)).isEqualTo("http://example.com:8080/sub/movie/u/p/7.mp4")
        assertThat(api.seriesStreamUrl(source, "5", null)).isEqualTo("http://example.com:8080/sub/series/u/p/5.mp4")
        assertThat(api.catchupUrl(source, "9", NOV_18_2030_UTC, 60))
            .isEqualTo("http://example.com:8080/sub/timeshift/u/p/60/2026-11-18:20-30/9.ts")
    }

    private companion object {
        const val AWKWARD_USER = "u #?/%"
        const val AWKWARD = "p#1/ ?%"
        val AWKWARD_SOURCE = PANEL_SOURCE.copy(username = AWKWARD_USER, password = AWKWARD)

        /** 18 Nov 2026, 20:30 UTC. */
        const val NOV_18_2030_UTC = 1_795_033_800_000L
    }
}
