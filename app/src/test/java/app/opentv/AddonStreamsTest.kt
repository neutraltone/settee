/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.AppDispatchers
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioAddon
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.StremioClient
import app.opentv.data.repo.AddonOutcome
import app.opentv.data.repo.AddonStreamResults
import app.opentv.data.repo.AddonStreams
import app.opentv.player.DecoderSize
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Test

/**
 * What a film's add-ons offer, ranked and labelled, against fake add-ons at placeholder addresses.
 * The IMDb id is made up; no add-on here is a real one.
 */
class AddonStreamsTest {

    private val film = "tt0000001"

    // What each add-on's host answers, by host.
    private val replies = mutableMapOf<String, String>()
    private val held = mutableMapOf<String, CountDownLatch>()
    private val network = FakeJsonHttp { request: Request ->
        held[request.url.host]?.await(10, TimeUnit.SECONDS)
        replies[request.url.host] ?: "404"
    }
    private val addons = mutableListOf<StremioAddon>()

    /** A device whose decoders go up to 1080p, so 4K can't play. */
    private val fullHdDevice = FakeDecoders("video/avc" to DecoderSize(1920, 1080))

    private fun addonStreams(decoders: app.opentv.player.DecoderList = ANY_SIZE_DECODERS) =
        AddonStreams(StremioClient(network.http), decoders, AppDispatchers()) { addons.toList() }

    private fun addon(
        host: String,
        name: String,
        reply: String,
        resources: List<String>? = listOf("stream"),
        types: List<String>? = listOf("movie", "series"),
    ) {
        addons += StremioAddon("https://$host/manifest.json", name, resources, types)
        replies[host] = reply
    }

    private fun streams(vararg entries: String) = """{"streams": [${entries.joinToString()}]}"""

    private fun direct(url: String, name: String = "", title: String = "", extra: String = "") =
        """{"url": "$url", "name": "$name", "title": "$title"$extra}"""

    private fun torrent(hash: String, name: String) = """{"infoHash": "$hash", "fileIdx": 2, "name": "$name"}"""

    private fun results(decoders: app.opentv.player.DecoderList = ANY_SIZE_DECODERS): AddonStreamResults =
        runBlocking { withTimeout(10_000) { addonStreams(decoders).forFilm(film).last() } }

    @Test
    fun `a direct stream comes labelled with its add-on, Quality and size`() {
        addon(
            "films.example.com",
            "Films",
            streams(direct("https://example.com/a.mkv", name = "Films\n1080p", title = "A film 2.1 GB")),
        )

        val stream = results().streams.single()

        assertThat(stream.stream.link).isEqualTo(StreamLink.Direct("https://example.com/a.mkv"))
        assertThat(stream.stream.addonName).isEqualTo("Films")
        assertThat(stream.stream.title).isEqualTo("A film 2.1 GB")
        assertThat(stream.qualityLabel).isEqualTo("FHD")
        assertThat(stream.sizeBytes).isEqualTo(2_254_857_830L)
    }

    @Test
    fun `the size an add-on gives in bytes beats the one in its title`() {
        val hinted = direct(
            "https://example.com/a.mkv",
            title = "4K 9 GB",
            extra = """, "behaviorHints": {"videoSize": 1000, "filename": "a.mkv"}""",
        )
        addon("films.example.com", "Films", streams(hinted))

        val stream = results().streams.single()

        assertThat(stream.sizeBytes).isEqualTo(1000L)
        assertThat(stream.stream.filename).isEqualTo("a.mkv")
        assertThat(stream.qualityLabel).isEqualTo("4K")
    }

    @Test
    fun `a stream that needs headers carries them`() {
        val needing = direct(
            "https://example.com/a.mkv",
            extra = """, "behaviorHints": {"bingeGroup": "films-1080p", "proxyHeaders": {"request":
                {"Referer": "https://example.com/", "User-Agent": "Player/2"}}}""",
        )
        addon("films.example.com", "Films", streams(needing))

        val stream = results().streams.single().stream

        assertThat(stream.link).isEqualTo(
            StreamLink.Direct(
                "https://example.com/a.mkv",
                mapOf("Referer" to "https://example.com/", "User-Agent" to "Player/2"),
            ),
        )
        assertThat(stream.bingeGroup).isEqualTo("films-1080p")
    }

    @Test
    fun `streams that open YouTube or another app are left out`() {
        addon(
            "films.example.com",
            "Films",
            streams(
                """{"ytId": "abc123", "name": "Trailer"}""",
                """{"externalUrl": "https://example.com/watch", "name": "Elsewhere"}""",
                direct("https://example.com/a.mkv", title = "Plays here"),
            ),
        )

        assertThat(results().streams.map { it.stream.title }).containsExactly("Plays here")
    }

    @Test
    fun `a torrent is read with its file and trackers, behind every direct link`() {
        addon(
            "films.example.com",
            "Films",
            streams(
                """{"infoHash": "ABCDEF0123", "fileIdx": 2, "name": "4K",
                    "sources": ["tracker:udp://example.com:80"]}""",
                direct("https://example.com/sd.mkv", name = "SD"),
            ),
        )

        val links = results().streams.map { it.stream.link }

        assertThat(links).containsExactly(
            StreamLink.Direct("https://example.com/sd.mkv"),
            StreamLink.Torrent("abcdef0123", 2, listOf("tracker:udp://example.com:80")),
        ).inOrder()
    }

    @Test
    fun `streams come highest Quality first, with what this device can't play last`() {
        addon(
            "films.example.com",
            "Films",
            streams(
                direct("https://example.com/hd.mkv", name = "720p"),
                direct("https://example.com/4k.mkv", name = "4K"),
                direct("https://example.com/plain.mkv", name = "Films"),
                direct("https://example.com/fhd.mkv", name = "1080p"),
            ),
        )

        val ranked = results(fullHdDevice).streams

        assertThat(ranked.map { (it.stream.link as StreamLink.Direct).url }).containsExactly(
            "https://example.com/fhd.mkv",
            "https://example.com/hd.mkv",
            "https://example.com/plain.mkv",
            "https://example.com/4k.mkv",
        ).inOrder()
        assertThat(ranked.last().playable).isFalse()
    }

    @Test
    fun `streams of the same Quality keep the viewer's add-on order`() {
        addon("first.example.com", "First", streams(direct("https://example.com/1.mkv", name = "1080p")))
        addon("second.example.com", "Second", streams(direct("https://example.com/2.mkv", name = "1080p")))

        assertThat(results().streams.map { it.stream.addonName }).containsExactly("First", "Second").inOrder()
    }

    @Test
    fun `the same link from two add-ons is shown once, as the first add-on's`() {
        val same = direct("https://example.com/same.mkv", name = "1080p")
        addon("first.example.com", "First", streams(same))
        addon("second.example.com", "Second", streams(same, torrent("abc", "720p")))

        assertThat(results().streams.map { it.stream.addonName }).containsExactly("First", "Second").inOrder()
        assertThat(results().streams.first().stream.addonName).isEqualTo("First")
    }

    @Test
    fun `one add-on failing doesn't hold back another's streams, and each says how it went`() {
        addon("broken.example.com", "Broken", "!")
        addon("empty.example.com", "Empty", streams())
        addon("films.example.com", "Films", streams(direct("https://example.com/a.mkv")))

        val found = results()

        assertThat(found.streams.map { it.stream.addonName }).containsExactly("Films")
        assertThat(found.outcomes.map { it::class }).containsExactly(
            AddonOutcome.Unreachable::class,
            AddonOutcome.AnsweredEmpty::class,
            AddonOutcome.Answered::class,
        ).inOrder()
        assertThat((found.outcomes.first() as AddonOutcome.Unreachable).failure)
            .isInstanceOf(AddonFailure.Unreachable::class.java)
        assertThat(found.finished).isTrue()
    }

    @Test
    fun `results arrive as each add-on answers, without waiting for the slowest`() = runBlocking {
        addon("slow.example.com", "Slow", streams(direct("https://example.com/slow.mkv")))
        addon("quick.example.com", "Quick", streams(direct("https://example.com/quick.mkv")))
        val slow = CountDownLatch(1).also { held["slow.example.com"] = it }
        val seen = Channel<AddonStreamResults>(Channel.UNLIMITED)

        val collecting = launch { addonStreams().forFilm(film).collect { seen.send(it) } }
        val first = withTimeout(10_000) {
            var next = seen.receive()
            while (next.streams.isEmpty()) next = seen.receive()
            next
        }
        slow.countDown()
        val last = withTimeout(10_000) {
            var next = first
            while (!next.finished) next = seen.receive()
            next
        }
        collecting.join()

        assertThat(first.streams.map { it.stream.addonName }).containsExactly("Quick")
        assertThat(first.finished).isFalse()
        assertThat(last.streams.map { it.stream.addonName }).containsExactly("Slow", "Quick").inOrder()
    }

    @Test
    fun `an add-on that doesn't offer film streams isn't asked`() {
        addon("series.example.com", "Series only", streams(), types = listOf("series"))
        addon("meta.example.com", "Catalogues", streams(), resources = listOf("catalog", "meta"))
        addon("unknown.example.com", "Not read yet", streams(), resources = null, types = null)

        val found = results()

        assertThat(network.hosts()).containsExactly("unknown.example.com")
        assertThat(found.outcomes.map { it.addonName }).containsExactly("Not read yet")
    }
}
