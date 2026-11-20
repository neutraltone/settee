/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Movie
import app.opentv.data.model.StremioAddon
import app.opentv.data.model.StremioStream
import app.opentv.ui.AddonStreams
import app.opentv.ui.VodViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A film's Add-on sources: what the add-ons' answers come to, against a fake TMDB and fake add-ons
 * at placeholder addresses. TMDB maps the film's id 949 to IMDb's tt0113277.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodAddonStreamsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    // What each add-on's host answers; TMDB knows the film unless told to drop the connection.
    private val addonReplies = mutableMapOf<String, String>()
    private var tmdbDown = false
    private val network = FakeJsonHttp { request: Request ->
        when (request.url.host) {
            "api.themoviedb.org" -> when {
                tmdbDown -> "!"
                request.url.encodedPath == "/3/movie/949" -> """{"imdb_id": "tt0113277"}"""
                else -> "404"
            }
            else -> addonReplies[request.url.host] ?: "404"
        }
    }

    private val graph = ServiceLocator.Graph(app, http = network.http, settingsFile = "vod-addon-streams-test")
        .apply { tmdbKey = "key" }

    private val heat = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Heat",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 1995,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
        tmdbId = "949",
    )

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
        graph.database.close()
        Dispatchers.resetMain()
    }

    private fun addon(host: String, name: String, reply: String) {
        graph.settings.addStremioAddon(StremioAddon("https://$host/manifest.json", name))
        addonReplies[host] = reply
    }

    private fun streams(vararg urls: String) =
        """{"streams": [${urls.joinToString { """{"url": "$it", "title": "1080p"}""" }}]}"""

    @Test
    fun `when every add-on fails, the lookup failed rather than found nothing, saying why the first did`() =
        runBlocking {
            addon("films.example.com", "Films", "503")
            addon("more.example.com", "More films", "!")

            val found = VodViewModel(app, graph).addonStreams(heat)

            assertThat(found).isEqualTo(
                AddonStreams.Failed(
                    ViewerError("The add-on is having problems. That's at its end, so try again later.", "HTTP 503"),
                ),
            )
        }

    @Test
    fun `one add-on failing doesn't sink another's streams`() = runBlocking {
        addon("broken.example.com", "Broken", "!")
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))

        val found = VodViewModel(app, graph).addonStreams(heat)

        assertThat(found).isEqualTo(
            AddonStreams.Found(
                listOf(StremioStream(url = "https://example.com/heat.mkv", title = "1080p", addonName = "Films")),
            ),
        )
    }

    @Test
    fun `a film TMDB can't match to IMDb finds no match, without asking the add-ons`() = runBlocking {
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))

        val found = VodViewModel(app, graph).addonStreams(heat.copy(tmdbId = "1"))

        assertThat(found).isEqualTo(AddonStreams.NoneFound)
        assertThat(network.hosts()).doesNotContain("films.example.com")
    }

    @Test
    fun `when TMDB doesn't answer, the lookup failed and says so, without blaming the add-ons`() = runBlocking {
        addon("films.example.com", "Films", streams("https://example.com/heat.mkv"))
        tmdbDown = true

        val found = VodViewModel(app, graph).addonStreams(heat)

        assertThat(found).isEqualTo(
            AddonStreams.Failed(
                ViewerError(
                    "Couldn't look this film up to ask your add-ons. Check this device is online, then try again.",
                    "IO",
                ),
            ),
        )
        assertThat(network.hosts()).doesNotContain("films.example.com")
    }
}
