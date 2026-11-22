/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.StremioStream
import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.StremioClient
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

/** What a Stremio add-on's answers come to, against [FakeJsonHttp]. Placeholder addresses only. */
class StremioClientTest {

    private val manifest = "https://example.com/addon/manifest.json"

    /** JSON that parses but isn't an object: what an address answering something else might send. */
    private val notObjects = listOf("[]", "null", "\"x\"")

    private fun client(reply: String) = StremioClient(FakeJsonHttp { reply }.http)

    private fun streams(reply: String) = client(reply).streams(manifest, "Films", "movie", "tt0113277")

    @Test
    fun `a manifest with an id and a name gives the add-on's name`() {
        val name = client("""{"id": "org.example.films", "name": "Films"}""").fetchManifest(manifest)

        assertThat(name).isEqualTo("Films")
    }

    @Test
    fun `a web page is not an add-on`() {
        val failure = assertThrows(AddonFailure.NotAnAddon::class.java) {
            client("<!doctype html><html><body>Sign in to the Wi-Fi</body></html>").fetchManifest(manifest)
        }

        assertThat(failure.status).isNull()
    }

    @Test
    fun `a 404 is not an add-on, and keeps its status`() {
        val failure = assertThrows(AddonFailure.NotAnAddon::class.java) {
            client("404").fetchManifest(manifest)
        }

        assertThat(failure.status).isEqualTo(404)
    }

    @Test
    fun `a 403 is not an add-on either, not trouble to wait out`() {
        val failure = assertThrows(AddonFailure.NotAnAddon::class.java) {
            client("403").fetchManifest(manifest)
        }

        assertThat(failure.status).isEqualTo(403)
    }

    @Test
    fun `a 503 is the add-on's server in trouble`() {
        val failure = assertThrows(AddonFailure.Http::class.java) {
            client("503").fetchManifest(manifest)
        }

        assertThat(failure.status).isEqualTo(503)
    }

    @Test
    fun `a manifest with no id is not an add-on`() {
        assertThrows(AddonFailure.NotAnAddon::class.java) {
            client("""{"name": "Films"}""").fetchManifest(manifest)
        }
    }

    // Every test from here down is a reply from #364. Each used to throw from a cast outside the
    // client's error handling, so adding the add-on crashed the app instead of saying what was wrong.

    @Test
    fun `JSON that isn't an object is not an add-on, not a crash`() {
        for (reply in notObjects) {
            val failure = assertThrows(reply, AddonFailure.NotAnAddon::class.java) {
                client(reply).fetchManifest(manifest)
            }

            assertWithMessage(reply).that(failure.status).isNull()
        }
    }

    @Test
    fun `a manifest whose name or id isn't text is not an add-on, not a crash`() {
        val replies = listOf(
            """{"id": "org.example.films", "name": {}}""",
            """{"id": "org.example.films", "name": ["Films"]}""",
            """{"id": {}, "name": "Films"}""",
        )
        for (reply in replies) {
            val failure = assertThrows(reply, AddonFailure.NotAnAddon::class.java) {
                client(reply).fetchManifest(manifest)
            }

            assertWithMessage(reply).that(failure.status).isNull()
        }
    }

    @Test
    fun `a streams reply that isn't an object holding a list comes to no streams, not a crash`() {
        for (reply in notObjects + listOf("""{"streams": {}}""", """{"streams": "x"}""")) {
            assertWithMessage(reply).that(streams(reply)).isEmpty()
        }
    }

    @Test
    fun `a stream entry that isn't an object, or whose url isn't text, is skipped, not a crash`() {
        val reply = """
            {"streams": [
                1,
                "x",
                null,
                {"url": {}},
                {"url": ["https://example.com/heat.mkv"]},
                {"url": "https://example.com/heat.mkv"}
            ]}
        """.trimIndent()

        assertThat(streams(reply)).containsExactly(
            StremioStream(url = "https://example.com/heat.mkv", title = "Stream", addonName = "Films"),
        )
    }

    @Test
    fun `a stream whose title or name isn't text is labelled by the other, or as a stream, not a crash`() {
        val reply = """
            {"streams": [
                {"url": "https://example.com/heat.mkv", "title": {}, "name": "1080p"},
                {"url": "https://example.com/heat-4k.mkv", "title": ["4K"], "name": []}
            ]}
        """.trimIndent()

        assertThat(streams(reply)).containsExactly(
            StremioStream(url = "https://example.com/heat.mkv", title = "1080p", addonName = "Films"),
            StremioStream(url = "https://example.com/heat-4k.mkv", title = "Stream", addonName = "Films"),
        ).inOrder()
    }
}
