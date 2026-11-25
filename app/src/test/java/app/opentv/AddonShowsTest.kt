/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.mediaKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shows the Provider doesn't carry (#672): found through TMDB once an add-on is set up, kept as series
 * of the built-in Add-ons source once opened, and given their seasons and episodes by TMDB. TMDB is
 * faked; IMDb ids are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonShowsTest {

    private var tmdbDown = false

    private val network = FakeJsonHttp { request: Request -> if (tmdbDown) "!" else tmdbReply(request) }

    @get:Rule
    val testGraph = TestGraph(name = "addon-shows-test", http = network.http)

    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val db = testGraph.database

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    @Test
    fun `with an add-on, search finds shows with an IMDb id as well as films`(): Unit = runBlocking {
        graph.settings.addStremioAddon(
            StremioAddon("https://example.com/manifest.json", "Example", listOf("stream"), listOf("movie", "series")),
        )

        val found = graph.addonTitles.search("lost")

        assertThat(found.map { it.name to it.isMovie })
            .containsExactly("Lost Film" to true, "Lost Show" to false)
        assertThat(found.single { !it.isMovie }.imdbId).isEqualTo("tt0000201")
    }

    @Test
    fun `opening a show keeps it under the Add-ons source by its IMDb id, with its TMDB id`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepShow(LOST_SHOW)

        val source = db.sources().byId(kept.sourceId)
        assertThat(source?.kind).isEqualTo(SourceKind.ADDONS)
        assertThat(kept.seriesId).isEqualTo("tt0000201")
        assertThat(kept.tmdbId).isEqualTo("201")
        assertThat(graph.addonTitles.keepShow(LOST_SHOW).id).isEqualTo(kept.id)
    }

    @Test
    fun `a kept show's seasons and episodes come from TMDB, asked of add-ons by IMDb id, season and episode`(): Unit =
        runBlocking {
            val episodes = keptEpisodes()

            assertThat(episodes.map { Triple(it.season, it.episodeNumber, it.title) }).containsExactly(
                Triple(1, 1, "Pilot"),
                Triple(1, 2, "Second"),
                Triple(2, 1, "Return"),
            ).inOrder()
            val pilot = episodes.first()
            assertThat(pilot.episodeId).isEqualTo("tt0000201:1:1")
            assertThat(pilot.plot).isEqualTo("It starts.")
            assertThat(pilot.durationSeconds).isEqualTo(45 * 60)
            assertThat(pilot.stillUrl).isEqualTo("https://image.tmdb.org/t/p/w500/s1e1.jpg")
            assertThat(pilot.streamUrl).isEmpty()
        }

    @Test
    fun `opening it again keeps each episode's local id, so progress stays on it`(): Unit = runBlocking {
        val first = keptEpisodes()
        val again = keptEpisodes()

        assertThat(again.map { it.id }).isEqualTo(first.map { it.id })
    }

    @Test
    fun `TMDB not answering is a failed fetch, not a show with no episodes`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepShow(LOST_SHOW)
        tmdbDown = true

        val source = checkNotNull(db.sources().byId(kept.sourceId))
        assertThat(graph.catalogRepository.fetchEpisodes(source, kept.seriesId).isFailure).isTrue()
    }

    @Test
    fun `an add-on episode remembers where it stopped and is the show's one Continue Watching card`(): Unit =
        runBlocking {
            val (pilot, second) = keptEpisodes()

            graph.viewerLibrary.recordProgress(pilot.mediaKey, positionMillis = DURATION / 4, durationMillis = DURATION)

            assertThat(graph.viewerLibrary.resumeFrom(pilot.mediaKey)).isEqualTo(DURATION / 4)
            val card = graph.viewerLibrary.continueWatching().first().single() as ContinueWatchingCard.Series
            assertThat(card.episode.id).isEqualTo(pilot.id)
            assertThat(card.progress).isEqualTo(0.25f)

            graph.viewerLibrary.recordProgress(pilot.mediaKey, positionMillis = DURATION * 94 / 100, DURATION)

            val next = graph.viewerLibrary.continueWatching().first().single() as ContinueWatchingCard.Series
            assertThat(next.episode.id).isEqualTo(second.id)
            assertThat(next.progress).isNull()
        }

    private suspend fun keptEpisodes(): List<Episode> {
        val kept = graph.addonTitles.keepShow(LOST_SHOW)
        val source = checkNotNull(db.sources().byId(kept.sourceId))
        assertThat(graph.catalogRepository.ensureEpisodes(source, kept.seriesId)).isTrue()
        val stored = db.episodes().forSeries(source.id, kept.seriesId)
        return stored.sortedWith(compareBy(Episode::season, Episode::episodeNumber))
    }

    private companion object {
        const val DURATION = 2_700_000L

        val LOST_SHOW = AddonTitle(
            tmdbId = "201",
            imdbId = "tt0000201",
            name = "Lost Show",
            year = 2010,
            posterUrl = null,
            backdropUrl = null,
            overview = "A show nobody carries.",
            isMovie = false,
        )
    }
}

/**
 * TMDB as the add-on tests need it: a search finding a film and two shows (one with no IMDb id), and
 * the first show's seasons, with Specials and an episode not yet aired, which are left out.
 */
internal fun tmdbReply(request: Request): String = when (request.url.encodedPath) {
    "/3/search/multi" -> """
        {"results": [
          {"media_type": "movie", "id": 101, "title": "Lost Film", "release_date": "1999-04-01"},
          {"media_type": "tv", "id": 201, "name": "Lost Show", "first_air_date": "2010-01-01",
           "poster_path": "/show.jpg", "overview": "A show nobody carries."},
          {"media_type": "tv", "id": 202, "name": "Unlisted Show", "first_air_date": "2012-01-01"}
        ]}
    """.trimIndent()
    "/3/movie/101" -> """{"imdb_id": "tt0000101"}"""
    "/3/tv/201/external_ids" -> """{"imdb_id": "tt0000201"}"""
    "/3/tv/202/external_ids" -> """{"imdb_id": null}"""
    "/3/tv/201" -> if (request.url.queryParameter("append_to_response") == "season/1,season/2") {
        """
            {"season/1": {"episodes": [
               {"season_number": 1, "episode_number": 1, "name": "Pilot", "overview": "It starts.",
                "runtime": 45, "still_path": "/s1e1.jpg", "air_date": "2010-01-01"},
               {"season_number": 1, "episode_number": 2, "name": "Second", "air_date": "2010-01-08"}
             ]},
             "season/2": {"episodes": [
               {"season_number": 2, "episode_number": 1, "name": "Return", "air_date": "2011-01-01"},
               {"season_number": 2, "episode_number": 2, "name": "Not Yet", "air_date": "2099-01-01"}
             ]}}
        """.trimIndent()
    } else {
        """
            {"name": "Lost Show", "overview": "A show nobody carries.",
             "seasons": [{"season_number": 0}, {"season_number": 1}, {"season_number": 2}]}
        """.trimIndent()
    }
    else -> "404"
}
