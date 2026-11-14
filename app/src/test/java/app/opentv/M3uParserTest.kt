/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.M3uParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These tests are built from the shapes real providers actually emit, including the broken
 * ones. Every "malformed" case here is something that should cost the user one channel, never
 * the whole playlist.
 */
class M3uParserTest {

    @Test
    fun `parses a well formed playlist`() {
        val playlist = """
            #EXTM3U url-tvg="http://example.com/xmltv.php?username=u&password=p"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://img/bbc1.png" group-title="UK",BBC One HD
            http://example.com:8080/live/u/p/1.m3u8
            #EXTINF:-1 tvg-id="itv1.uk" tvg-logo="http://img/itv.png" group-title="UK",ITV 1
            http://example.com:8080/live/u/p/2.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 7)

        assertThat(result.channels).hasSize(2)
        assertThat(result.declaredEpgUrl)
            .isEqualTo("http://example.com/xmltv.php?username=u&password=p")

        val first = result.channels.first()
        assertThat(first.name).isEqualTo("BBC One HD")
        assertThat(first.epgChannelId).isEqualTo("bbc1.uk")
        assertThat(first.categoryId).isEqualTo("UK")
        assertThat(first.logoUrl).isEqualTo("http://img/bbc1.png")
        assertThat(first.sourceId).isEqualTo(7)
        assertThat(first.streamUrl).isEqualTo("http://example.com:8080/live/u/p/1.m3u8")
    }

    @Test
    fun `a URL with no EXTINF is skipped without losing the rest`() {
        val playlist = """
            #EXTM3U
            http://example.com/orphan.m3u8
            #EXTINF:-1 tvg-id="good",Good Channel
            http://example.com/good.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.skippedEntries).isEqualTo(1)
        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Good Channel")
    }

    @Test
    fun `falls back to tvg-name when the display name after the comma is empty`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `duplicate tvg-ids do not collide`() {
        // Providers routinely list SD and HD variants under one tvg-id. The unique index on
        // (sourceId, streamId) would otherwise abort the entire import.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel SD
            http://example.com/sd.m3u8
            #EXTINF:-1 tvg-id="dup",Channel HD
            http://example.com/hd.m3u8
        """.trimIndent()

        val result = M3uParser.parse(playlist, sourceId = 1)

        assertThat(result.channels).hasSize(1)
        assertThat(result.channels.single().name).isEqualTo("Channel SD")
    }

    @Test
    fun `channels without a tvg-id still get a stable id derived from the url`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,No Id Channel
            http://example.com/noid.m3u8
        """.trimIndent()

        val first = M3uParser.parse(playlist, sourceId = 1).channels.single()
        val second = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(first.streamId).startsWith("url:")
        // Stability matters: an unstable id means favourites are lost on every refresh.
        assertThat(first.streamId).isEqualTo(second.streamId)
        assertThat(first.epgChannelId).isNull()
    }

    @Test
    fun `unknown directives between EXTINF and the url are ignored`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",Channel A
            #EXTVLCOPT:http-user-agent=SomePlayer/1.0
            #EXTHTTP:{"User-Agent":"SomePlayer/1.0"}
            http://example.com/a.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(1)
        assertThat(channels.single().streamUrl).isEqualTo("http://example.com/a.m3u8")
    }

    @Test
    fun `channel numbers come from tvg-chno when present`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" tvg-chno="101",Channel A
            http://example.com/a.m3u8
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels.single().number).isEqualTo(101)
    }

    @Test
    fun `an m3u_plus playlist's films and episodes are left out of its live channels`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1",BBC One
            http://example.com:8080/live/u/p/1.ts
            #EXTINF:-1 tvg-id="" group-title="Films",Drive (2011)
            http://example.com:8080/movie/u/p/30.mkv
            #EXTINF:-1 tvg-id="itv1",ITV 1
            http://example.com:8080/live/u/p/2.ts
            #EXTINF:-1 tvg-id="" group-title="Shows",Taskmaster S01 E01
            http://example.com:8080/series/u/p/40.mp4
            #EXTINF:-1 tvg-id="ch4",Channel 4
            http://example.com:8080/u/p/3
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels.map { it.name }).containsExactly("BBC One", "ITV 1", "Channel 4").inOrder()
        assertThat(channels.map { it.sortIndex }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `films and episodes are left out whatever the case of their path`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Drive (2011)
            http://example.com/Movie/u/p/30.mkv
            #EXTINF:-1,BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1,Taskmaster S01 E01
            http://example.com/SERIES/u/p/40.mp4
            #EXTINF:-1,Heat (1995)
            HTTP://EXAMPLE.COM/MOVIE/U/P/31.MKV
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels.map { it.name }).containsExactly("BBC One")
    }

    @Test
    fun `a film sharing a live channel's tvg-id does not crowd the channel out`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1",BBC One Documentary
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1 tvg-id="bbc1",BBC One
            http://example.com/live/u/p/1.ts
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("BBC One")
    }

    @Test
    fun `a playlist of only films and episodes has no live channels`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="VOD | Action",Drive (2011)
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1 group-title="SERIES | Comedy",Taskmaster S01 E01
            http://example.com/series/u/p/40.mp4
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).channels).isEmpty()
    }

    @Test
    fun `films and episodes left out are not counted as malformed entries`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Drive (2011)
            http://example.com/movie/u/p/30.mkv
            #EXTINF:-1,Taskmaster S01 E01
            http://example.com/series/u/p/40.mp4
            #EXTINF:-1,BBC One
            http://example.com/live/u/p/1.ts
        """.trimIndent()

        assertThat(M3uParser.parse(playlist, sourceId = 1).skippedEntries).isEqualTo(0)
    }

    @Test
    fun `a live stream whose URL mentions movie or series elsewhere is still a channel`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Movie Channel
            http://example.com/live/movie/1.m3u8
            #EXTINF:-1,Series Channel
            http://example.com/live/u/p/series/2.ts
            #EXTINF:-1,Movie Host
            http://movie.example.com/live/3.ts
            #EXTINF:-1,Query
            http://example.com/play?path=/movie/4
            #EXTINF:-1,Movies Hub
            http://example.com/movies/5.m3u8
            #EXTINF:-1,Bare Movie
            http://example.com/movie
            #EXTINF:-1,Query With No Path
            http://example.com:8080?stream=/movie/6
            #EXTINF:-1,Fragment With No Path
            http://example.com#/series/7
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels.map { it.name })
            .containsExactly(
                "Movie Channel",
                "Series Channel",
                "Movie Host",
                "Query",
                "Movies Hub",
                "Bare Movie",
                "Query With No Path",
                "Fragment With No Path",
            ).inOrder()
    }

    @Test
    fun `an empty playlist yields no channels rather than throwing`() {
        val result = M3uParser.parse("#EXTM3U\n", sourceId = 1)

        assertThat(result.channels).isEmpty()
        assertThat(result.skippedEntries).isEqualTo(0)
    }
}
