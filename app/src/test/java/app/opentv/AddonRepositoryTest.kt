/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.StremioAddon
import app.opentv.data.remote.AddonFailure
import app.opentv.data.repo.AddonRepository
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Adding a Stremio add-on by the address the viewer typed or pasted, against fake add-ons at
 * placeholder addresses: what's fetched, what's kept, and why one is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonRepositoryTest {

    /** What each address answers, by full URL; anything else is a 404. */
    private val replies = mutableMapOf<String, String>()
    private val network = FakeJsonHttp { request: Request -> replies[request.url.toString()] ?: "404" }

    @get:Rule
    val testGraph = TestGraph(name = "addon-repository-test", http = network.http)

    private val graph = testGraph.graph
    private val addons get() = graph.addonRepository

    private val films = """{"id": "org.example.films", "name": "Films", "resources": ["stream"], "types": ["movie"]}"""

    private fun add(typed: String) = runBlocking { addons.add(typed) }

    @Test
    fun `a stremio install link is read as https`() {
        replies["https://example.com/films/manifest.json"] = films

        val outcome = add("stremio://example.com/films/manifest.json")

        assertThat(outcome).isEqualTo(AddonRepository.Outcome.Accepted("Films"))
        assertThat(network.asked.map { it.url.toString() }).containsExactly("https://example.com/films/manifest.json")
        assertThat(graph.settings.stremioAddons.value.map { it.manifestUrl })
            .containsExactly("https://example.com/films/manifest.json")
    }

    @Test
    fun `an address typed without a scheme is read as https`() {
        replies["https://example.com/films/manifest.json"] = films

        val outcome = add("example.com/films/manifest.json")

        assertThat(outcome).isEqualTo(AddonRepository.Outcome.Accepted("Films"))
        assertThat(network.asked.map { it.url.toString() }).containsExactly("https://example.com/films/manifest.json")
    }

    @Test
    fun `an address without manifest json, with or without a trailing slash, finds the manifest`() {
        replies["https://example.com/films/manifest.json"] = films

        val outcomes = listOf(add("https://example.com/films/"), add("example.com/films"))

        assertThat(outcomes).containsExactly(
            AddonRepository.Outcome.Accepted("Films"),
            AddonRepository.Outcome.Accepted("Films"),
        )
        assertThat(network.asked.map { it.url.toString() }.distinct())
            .containsExactly("https://example.com/films/manifest.json")
    }

    @Test
    fun `adding the same add-on twice keeps one entry, where it was`() {
        replies["https://example.com/films/manifest.json"] = films
        replies["https://example.org/shows/manifest.json"] = """{"id": "org.example.shows", "name": "Shows"}"""

        add("https://example.com/films/manifest.json")
        add("example.org/shows")
        add("stremio://example.com/films/manifest.json")

        assertThat(graph.settings.stremioAddons.value.map { it.name }).containsExactly("Films", "Shows").inOrder()
    }

    @Test
    fun `the add-on keeps what its manifest says it serves, named or described`() {
        replies["https://example.com/films/manifest.json"] = """
            {"id": "org.example.films", "name": "Films", "types": ["movie"],
             "resources": ["catalog", {"name": "stream", "types": ["series"], "idPrefixes": ["tt"]}]}
        """.trimIndent()

        add("example.com/films")

        val stored = graph.settings.stremioAddons.value.single()
        assertThat(stored.resources).containsExactly("catalog", "stream")
        assertThat(stored.types).containsExactly("movie", "series")
    }

    @Test
    fun `what can't be a web address is refused as that, without asking anything`() {
        val outcomes = listOf(add("ftp://example.com/films/manifest.json"), add("example com/films"))

        assertThat(outcomes.map { (it as AddonRepository.Outcome.Refused).failure })
            .comparingElementsUsing(Correspondence.transforming<Any, Class<*>>({ it.javaClass }, "is a"))
            .containsExactly(AddonFailure.BadAddress::class.java, AddonFailure.BadAddress::class.java)
        assertThat(network.asked).isEmpty()
        assertThat(graph.settings.stremioAddons.value).isEmpty()
    }

    @Test
    fun `an add-on stored before Settee read what it serves is read again once, the first time it's used`() {
        graph.settings.addStremioAddon(StremioAddon("https://example.com/films/manifest.json", "Films"))
        replies["https://example.com/films/manifest.json"] = films

        val first = runBlocking { addons.addons() }
        val second = runBlocking { addons.addons() }

        assertThat(first.single().resources).containsExactly("stream")
        assertThat(first.single().types).containsExactly("movie")
        assertThat(second).isEqualTo(first)
        assertThat(graph.settings.stremioAddons.value).isEqualTo(first)
        assertThat(network.asked).hasSize(1)
    }

    @Test
    fun `an old add-on that can't be read again is still used as it is`() {
        val films = StremioAddon("https://example.com/films/manifest.json", "Films")
        graph.settings.addStremioAddon(films)
        replies["https://example.com/films/manifest.json"] = "!"

        assertThat(runBlocking { addons.addons() }).containsExactly(films)
    }
}
