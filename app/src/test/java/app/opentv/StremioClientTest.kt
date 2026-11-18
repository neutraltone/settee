/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.remote.AddonFailure
import app.opentv.data.remote.StremioClient
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** What a Stremio add-on's answers come to, against [FakeJsonHttp]. Placeholder addresses only. */
class StremioClientTest {

    private val manifest = "https://example.com/addon/manifest.json"

    private fun client(reply: String) = StremioClient(FakeJsonHttp { reply }.http)

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
}
