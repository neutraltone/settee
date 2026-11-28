/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.SyncDevice.Companion.EPISODE_MILLIS
import app.opentv.SyncDevice.Companion.MOVIE_MILLIS
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.mediaKey
import app.opentv.data.repo.movies
import app.opentv.data.repo.series
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Progress and Favourites of titles kept from add-ons travel between devices (#673) by the Add-ons
 * source's name and the IMDb id, and a receiver that never kept the title keeps it from TMDB first,
 * add-ons or not. TMDB is faked through each device's panel; IMDb ids are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonSyncTest {

    private val deviceA = SyncDevice(rowIdOffset = 0)
    private val deviceB = SyncDevice(rowIdOffset = 100)

    @get:Rule(order = 0)
    val graphA = deviceA.testGraph

    @get:Rule(order = 1)
    val graphB = deviceB.testGraph

    init {
        listOf(deviceA, deviceB).forEach { device ->
            device.graph.tmdbKey = "key"
            device.panel.replies += TMDB
        }
    }

    @After
    fun close() = runBlocking {
        deviceB.awaitEpisodes()
        deviceB.graph.settings.stremioAddons.value.forEach { deviceB.graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    private fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private suspend fun syncAToB() {
        deviceB.syncFrom(deviceA)
        deviceB.awaitEpisodes()
    }

    @Test
    fun `an add-on film's progress and Favourite arrive on a TV that never kept it, with no add-ons there`() =
        testing { filmArrives() }

    @Test
    fun `an add-on film's progress and Favourite arrive on a TV that never kept it, with add-ons there`() = testing {
        deviceB.graph.settings.addStremioAddon(StremioAddon("https://example.com/manifest.json", "Example"))
        filmArrives()
    }

    private suspend fun filmArrives() {
        seedBoth()
        val film = deviceA.graph.addonTitles.keepFilm(LOST_FILM)
        deviceA.play("Alex", film.mediaKey, 1_200_000, MOVIE_MILLIS)
        deviceA.graph.viewerLibrary.favourites.set(film, favourite = true)

        syncAToB()

        val kept = checkNotNull(keptFilmOnB())
        assertThat(kept.name).isEqualTo("Lost Film")
        assertThat(kept.tmdbId).isEqualTo("101")
        assertThat(deviceB.resumeFrom("Alex", kept.mediaKey)).isEqualTo(1_200_000)
        assertThat(deviceB.graph.viewerLibrary.favourites.movies().first().map { it.name }).contains("Lost Film")
    }

    @Test
    fun `an add-on episode's progress arrives on a TV that never kept the show, its episodes fetched first`() =
        testing {
            seedBoth()
            val pilot = keptPilotOnA()
            deviceA.play("Alex", pilot.mediaKey, 900_000, EPISODE_MILLIS)

            syncAToB()

            val show = checkNotNull(keptShowOnB())
            val onB = checkNotNull(deviceB.db.episodes().byProviderId(show.sourceId, "tt0000201:1:1"))
            assertThat(deviceB.resumeFrom("Alex", onB.mediaKey)).isEqualTo(900_000)
            assertThat(deviceB.db.episodes().forSeries(show.sourceId, show.seriesId)).hasSize(2)
        }

    @Test
    fun `an add-on show starred as a Favourite arrives on a TV that never kept it`() = testing {
        seedBoth()
        val show = deviceA.graph.addonTitles.keepShow(LOST_SHOW)
        deviceA.asProfile("Alex")
        deviceA.graph.viewerLibrary.favourites.set(show, favourite = true)

        syncAToB()

        deviceB.asProfile("Alex")
        assertThat(deviceB.graph.viewerLibrary.favourites.series().first().map { it.name }).contains("Lost Show")
    }

    @Test
    fun `progress on titles both TVs kept travels back and forth by IMDb id`() = testing {
        seedBoth()
        val onA = deviceA.graph.addonTitles.keepFilm(LOST_FILM)
        val onB = deviceB.graph.addonTitles.keepFilm(LOST_FILM)
        assertThat(onB.id).isNotEqualTo(onA.id)
        deviceB.play("Alex", onB.mediaKey, 600_000, MOVIE_MILLIS)

        deviceA.syncFrom(deviceB)

        assertThat(deviceA.resumeFrom("Alex", onA.mediaKey)).isEqualTo(600_000)
    }

    @Test
    fun `TMDB down keeps nothing, and a later sync brings the title in`() = testing {
        seedBoth()
        val film = deviceA.graph.addonTitles.keepFilm(LOST_FILM)
        deviceA.play("Alex", film.mediaKey, 1_200_000, MOVIE_MILLIS)
        deviceB.panel.replies["/3/find/tt0000101"] = "!"

        syncAToB()
        assertThat(keptFilmOnB()).isNull()

        deviceB.panel.replies += TMDB
        syncAToB()
        assertThat(deviceB.resumeFrom("Alex", checkNotNull(keptFilmOnB()).mediaKey)).isEqualTo(1_200_000)
    }

    @Test
    fun `a title TMDB doesn't know is asked for once, not on every sync`() = testing {
        seedBoth()
        val film = deviceA.graph.addonTitles.keepFilm(LOST_FILM)
        deviceA.play("Alex", film.mediaKey, 1_200_000, MOVIE_MILLIS)
        deviceB.panel.replies["/3/find/tt0000101"] = """{"movie_results": [], "tv_results": []}"""

        syncAToB()
        syncAToB()

        assertThat(keptFilmOnB()).isNull()
        assertThat(deviceB.panel.asked.count { it == "/3/find/tt0000101" }).isEqualTo(1)
    }

    private suspend fun seedBoth() {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
    }

    private suspend fun keptPilotOnA() = deviceA.graph.addonTitles.keepShow(LOST_SHOW).let { show ->
        val source = checkNotNull(deviceA.db.sources().byId(show.sourceId))
        assertThat(deviceA.graph.catalogRepository.ensureEpisodes(source, show.seriesId)).isTrue()
        checkNotNull(deviceA.db.episodes().byProviderId(show.sourceId, "tt0000201:1:1"))
    }

    private suspend fun keptFilmOnB(): Movie? =
        deviceB.db.sources().addons()?.let { deviceB.db.movies().byProviderId(it.id, "tt0000101") }

    private suspend fun keptShowOnB(): Series? =
        deviceB.db.sources().addons()?.let { deviceB.db.series().byProviderId(it.id, "tt0000201") }

    private companion object {
        val LOST_FILM = AddonTitle("101", "tt0000101", "Lost Film", 1999, null, null, null, isMovie = true)
        val LOST_SHOW = AddonTitle("201", "tt0000201", "Lost Show", 2010, null, null, null, isMovie = false)

        /** TMDB, answering by path: a look-up by IMDb id, and the show's seasons in one reply for both asks. */
        val TMDB = mapOf(
            "/3/find/tt0000101" to """
                {"movie_results": [{"id": 101, "title": "Lost Film", "release_date": "1999-04-01",
                  "poster_path": "/film.jpg"}], "tv_results": []}
            """.trimIndent(),
            "/3/find/tt0000201" to """
                {"movie_results": [], "tv_results": [{"id": 201, "name": "Lost Show",
                  "first_air_date": "2010-01-01"}]}
            """.trimIndent(),
            "/3/tv/201" to """
                {"name": "Lost Show", "seasons": [{"season_number": 1}],
                 "season/1": {"episodes": [
                   {"season_number": 1, "episode_number": 1, "name": "Pilot", "air_date": "2010-01-01"},
                   {"season_number": 1, "episode_number": 2, "name": "Second", "air_date": "2010-01-08"}
                 ]}}
            """.trimIndent(),
        )
    }
}
