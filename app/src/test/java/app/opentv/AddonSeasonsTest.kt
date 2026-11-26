/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.mediaKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Provider's show gets the seasons TMDB knows of and the Provider doesn't carry, as episodes of an
 * Add-ons copy of the show (#683, ADR 0011). TMDB is faked ([tmdbReply]: show 201 has aired Seasons 1
 * and 2); IMDb ids are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonSeasonsTest {

    private var tmdbDown = false

    private val network = FakeJsonHttp { request: Request -> if (tmdbDown) "!" else tmdbReply(request) }

    @get:Rule
    val testGraph = TestGraph(name = "addon-seasons-test", http = network.http)

    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val db = testGraph.database

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    @Test
    fun `a Provider show gets the seasons TMDB lists that it doesn't carry, from an Add-ons copy`(): Unit =
        runBlocking {
            addShowStreams()
            val show = providerShow(seasons = listOf(1))

            graph.addonSeasons.fill(show)

            assertThat(addonEpisodes().map { it.season to it.episodeNumber }).containsExactly(2 to 1)
            assertThat(addonEpisodes().single().episodeId).isEqualTo("tt0000201:2:1")
        }

    @Test
    fun `without an add-on that has show streams, the Provider's seasons are all there is`(): Unit = runBlocking {
        graph.settings.addStremioAddon(
            StremioAddon("https://films.example.com/manifest.json", "Films", streamTypes = listOf("movie")),
        )
        val show = providerShow(seasons = listOf(1))

        graph.addonSeasons.fill(show)

        assertThat(db.sources().addons()).isNull()
        assertThat(network.asked).isEmpty()
    }

    @Test
    fun `a show without a TMDB id gets nothing more`(): Unit = runBlocking {
        addShowStreams()
        val show = providerShow(seasons = listOf(1), tmdbId = null)

        graph.addonSeasons.fill(show)

        assertThat(db.sources().addons()).isNull()
    }

    @Test
    fun `TMDB not answering leaves the Provider's seasons as they are`(): Unit = runBlocking {
        addShowStreams()
        val show = providerShow(seasons = listOf(1))
        tmdbDown = true

        assertThat(graph.addonSeasons.fill(show).isFailure).isTrue()

        assertThat(addonEpisodes()).isEmpty()
        assertThat(db.episodes().forSeries(show.sourceId, show.seriesId).map { it.season }).containsExactly(1)
    }

    @Test
    fun `a Provider carrying every season TMDB lists gains no add-on copy`(): Unit = runBlocking {
        addShowStreams()
        val show = providerShow(seasons = listOf(1, 2))

        graph.addonSeasons.fill(show)

        assertThat(db.sources().addons()).isNull()
    }

    @Test
    fun `asking for the add-on copy's episodes again never adds the seasons the Provider carries`(): Unit =
        runBlocking {
            addShowStreams()
            graph.addonSeasons.fill(providerShow(seasons = listOf(1)))

            val addons = checkNotNull(db.sources().addons())
            assertThat(graph.catalogRepository.ensureEpisodes(addons, "tt0000201")).isTrue()

            assertThat(addonEpisodes().map { it.season }).containsExactly(2)
        }

    @Test
    fun `a season the Provider adds later keeps the progress watched on the add-on copy`(): Unit = runBlocking {
        addShowStreams()
        val show = providerShow(seasons = listOf(1))
        graph.addonSeasons.fill(show)
        val watched = addonEpisodes().single()
        graph.viewerLibrary.recordProgress(watched.mediaKey, positionMillis = DURATION / 4, durationMillis = DURATION)

        db.episodes().upsertAll(listOf(providerEpisode(show.sourceId, season = 2)))
        graph.addonSeasons.fill(show)

        assertThat(addonEpisodes().map { it.id }).containsExactly(watched.id)
        assertThat(graph.viewerLibrary.resumeFrom(watched.mediaKey)).isEqualTo(DURATION / 4)
    }

    private fun addShowStreams() = graph.settings.addStremioAddon(
        StremioAddon("https://shows.example.com/manifest.json", "Shows", streamTypes = listOf("series")),
    )

    /** "Lost Show" from a Provider with one episode in each of [seasons], TMDB id 201 unless [tmdbId] says. */
    private suspend fun providerShow(seasons: List<Int>, tmdbId: String? = "201"): Series {
        val sourceId = db.sources().all().firstOrNull()?.id
            ?: db.sources().insert(Source(name = "Provider", kind = SourceKind.M3U, url = "https://example.com/list"))
        val series = Series(
            sourceId = sourceId,
            seriesId = "77",
            name = "Lost Show",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = 2010,
            plot = null,
            tmdbId = tmdbId,
        )
        db.series().upsertAll(listOf(series))
        db.episodes().upsertAll(seasons.map { providerEpisode(sourceId, it) })
        return checkNotNull(db.series().byProviderId(sourceId, "77"))
    }

    private fun providerEpisode(sourceId: Long, season: Int) = Episode(
        sourceId = sourceId,
        seriesId = "77",
        episodeId = "p$season",
        season = season,
        episodeNumber = 1,
        title = "Provider $season",
        plot = null,
        durationSeconds = null,
        stillUrl = null,
        streamUrl = "https://example.com/$season.mkv",
    )

    private suspend fun addonEpisodes(): List<Episode> {
        val addons = db.sources().addons() ?: return emptyList()
        return db.episodes().forSeries(addons.id, "tt0000201")
            .sortedWith(compareBy(Episode::season, Episode::episodeNumber))
    }

    private companion object {
        const val DURATION = 2_700_000L
    }
}
