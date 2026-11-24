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
    fun `a channel with commas in its name keeps the whole name`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="news",News, Weather & Sport
            http://example.com/news.m3u8
            #EXTINF:-1,Live: Rovers vs United, 15:00
            http://example.com/event.m3u8
        """.trimIndent()

        val names = M3uParser.parse(playlist, sourceId = 1).channels.map { it.name }

        assertThat(names).containsExactly("News, Weather & Sport", "Live: Rovers vs United, 15:00").inOrder()
    }

    @Test
    fun `a comma inside a quoted attribute does not cut the name`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="ns" group-title="News, Sport" tvg-name="Fallback, Name",Sport Extra
            http://example.com/ns.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Sport Extra")
        assertThat(channel.categoryId).isEqualTo("News, Sport")
    }

    @Test
    fun `a comma inside a quoted attribute does not stop the tvg-name fallback`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="x" group-title="News, Sport" tvg-name="Fallback Name",
            http://example.com/x.m3u8
        """.trimIndent()

        val channel = M3uParser.parse(playlist, sourceId = 1).channels.single()

        assertThat(channel.name).isEqualTo("Fallback Name")
    }

    @Test
    fun `channels sharing a tvg-id are all listed and all match its guide`() {
        // Regional variants share one guide id; the unique index on (sourceId, streamId) means
        // they can't all be tvg:<id>, so only the first keeps it.
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One London
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One Wales
            http://example.com/live/u/p/2.ts
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels.map { it.name }).containsExactly("BBC One London", "BBC One Wales").inOrder()
        assertThat(channels.map { it.epgChannelId }).containsExactly("bbc1.uk", "bbc1.uk")
        assertThat(channels.first().streamId).isEqualTo("tvg:bbc1.uk")
        assertThat(channels.map { it.streamId }.toSet()).hasSize(2)
    }

    @Test
    fun `a placeholder tvg-id on every channel still imports every channel`() {
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(20) { i ->
                appendLine("#EXTINF:-1 tvg-id=\"0\",Channel $i")
                appendLine("http://example.com/live/u/p/$i.ts")
            }
        }

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(20)
        assertThat(channels.map { it.streamId }.toSet()).hasSize(20)
        assertThat(channels.map { it.sortIndex }).isEqualTo((0 until 20).toList())
    }

    @Test
    fun `repeats sharing a tvg-id and a name are still told apart`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup",Channel
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 tvg-id="dup",Channel
            http://example.com/live/u/p/2.ts
            #EXTINF:-1 tvg-id="dup",Channel
            http://example.com/live/u/p/3.ts
        """.trimIndent()

        val channels = M3uParser.parse(playlist, sourceId = 1).channels

        assertThat(channels).hasSize(3)
        assertThat(channels.map { it.streamId }.toSet()).hasSize(3)
        assertThat(channels.first().streamId).isEqualTo("tvg:dup")
    }

    @Test
    fun `a repeat keeps its id across refreshes even when its URL changes`() {
        // A repeat's id comes from its tvg-id and name, not its URL, whose token can rotate.
        fun playlist(token: String) = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One London
            http://example.com/live/u/p/1.ts?token=$token
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One Wales
            http://example.com/live/u/p/2.ts?token=$token
        """.trimIndent()

        val before = M3uParser.parse(playlist("a"), sourceId = 1).channels.map { it.streamId }
        val after = M3uParser.parse(playlist("b"), sourceId = 1).channels.map { it.streamId }

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun `a repeat's id is not one another channel's tvg-id could give`() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a",B
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 tvg-id="a",B
            http://example.com/live/u/p/2.ts
        """.trimIndent()

        val repeat = M3uParser.parse(playlist, sourceId = 1).channels.last()

        assertThat(repeat.streamId).doesNotMatch("(tvg|url):.*")
    }

    @Test
    fun `playlists with one tvg-id per channel or none keep the stream ids they had`() {
        // Favourites, custom names, hidden flags, order and guide overrides are all keyed by
        // streamId: these literals are what the parser gave before repeats were kept, and must
        // never change.
        val oneEach = """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1.uk",BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 tvg-id="itv1.uk",ITV 1
            http://example.com/live/u/p/2.ts?token=abc
        """.trimIndent()
        val none = """
            #EXTM3U
            #EXTINF:-1,BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1 tvg-id="",ITV 1
            http://example.com/live/u/p/2.ts?token=abc
            #EXTINF:-1,ITV 1 again
            http://example.com/live/u/p/2.ts?token=abc
        """.trimIndent()

        assertThat(M3uParser.parse(oneEach, sourceId = 1).channels.map { it.streamId })
            .containsExactly("tvg:bbc1.uk", "tvg:itv1.uk").inOrder()
        // A URL listed twice is still one channel, as before.
        assertThat(M3uParser.parse(none, sourceId = 1).channels.map { it.streamId })
            .containsExactly("url:7a8bab84a10cac93", "url:777b40785c85f83").inOrder()
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
