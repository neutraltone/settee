/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.mediaKey
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.SeriesDetailScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page of a Provider's show with seasons TMDB knows of and the Provider doesn't carry (#683), with
 * its real view model: the Provider has Seasons 1 to 9, TMDB (faked) lists 1 to 11, and 10 and 11 come
 * after the Provider's, marked as from add-ons, playing through the add-on picker. IMDb ids are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AddonSeasonsPageTest {

    private val compose = createComposeRule()

    private val network = FakeJsonHttp { request: Request ->
        when {
            request.url.encodedPath.startsWith("/stream/series/tt0000301:10:1") ->
                """{"streams": [{"url": "https://example.com/s10e1.mkv", "title": "Ten 1080p"}]}"""
            else -> elevenSeasons(request)
        }
    }

    @get:Rule
    val testGraph = TestGraph(compose, http = network.http)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app = testGraph.app
    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val db = testGraph.database
    private val played = mutableListOf<MediaKey>()
    private val addonsMark get() = app.getString(R.string.settings_pane_addons)

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    @Test
    fun `TMDB's seasons the Provider lacks come after its own, marked as from add-ons, in D-pad order`() {
        openPage(providerShow())

        // From Play down to Season 1's first episode, up to its pill, then along the row: the pills further
        // along are only laid out as the row scrolls to them.
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        compose.showPagePill("Season 1").assertIsFocused()
        (2..11).forEach {
            press(Key.DirectionRight)
            compose.showPagePill("Season $it").assertIsFocused()
            val marked = compose.onAllNodes(hasText("Season $it") and hasContentDescription(addonsMark))
            marked.assertCountEquals(if (it >= 10) 1 else 0)
        }
        compose.episodeRow(11, 1).assertIsDisplayed()
        captureScreen("series_detail_addon_seasons")
    }

    @Test
    fun `an add-on season's episode resumes from its own progress, through the add-on picker`() {
        val show = providerShow()
        runBlocking { graph.addonSeasons.fill(show) }
        val tenth = runBlocking { addonEpisode(season = 10) }
        runBlocking { graph.viewerLibrary.recordProgress(tenth.mediaKey, DURATION / 4, DURATION) }
        openPage(show)

        compose.onNodeWithText(app.getString(R.string.vod_resume_episode, 10, 1)).assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        compose.onNode(hasText("Ten 1080p", substring = true) and isFocused()).assertExists()
        compose.onNode(hasText("Ten 1080p", substring = true) and isFocused())
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.settleShowPage()

        val picked =
            MediaKey.AddonStream(MediaKey.Episode(tenth.id), "https://example.com/s10e1.mkv", addonName = "Example")
        assertThat(played).containsExactly(picked)
        // Asked by the IMDb id it's kept under: TMDB was asked for it once, when the season was kept.
        assertThat(network.asked.count { it.url.encodedPath.endsWith("/external_ids") }).isEqualTo(1)
        assertThat(runBlocking { graph.viewerLibrary.resumeFrom(tenth.mediaKey) }).isEqualTo(DURATION / 4)
    }

    private fun press(key: Key) = compose.pressOnShowPage(key)

    private fun openPage(show: Series) {
        val viewModel = testGraph.viewModel { VodViewModel(app, graph) }
        compose.setTvContent {
            SeriesDetailScreen(
                seriesId = show.id,
                onPlayEpisode = { played += it },
                onOpenPerson = {},
                onBack = {},
                viewModel = viewModel,
            )
        }
        compose.settleShowPage()
    }

    /** A Provider's show, TMDB id 301, with one episode in each of Seasons 1 to 9, and an add-on for shows. */
    private fun providerShow(): Series = runBlocking {
        graph.settings.addStremioAddon(
            StremioAddon(
                "https://example.com/manifest.json",
                "Example",
                listOf("stream"),
                listOf("movie", "series"),
                listOf("movie", "series"),
            ),
        )
        val sourceId = db.sources().insert(
            Source(name = "Provider", kind = SourceKind.M3U, url = "https://example.com/l"),
        )
        val series = Series(
            sourceId = sourceId,
            seriesId = "88",
            name = "Long Show",
            categoryId = null,
            posterUrl = null,
            rating = null,
            year = 2001,
            plot = "It goes on.",
            tmdbId = "301",
        )
        db.series().upsertAll(listOf(series))
        db.episodes().upsertAll(
            (1..9).map { season ->
                Episode(
                    sourceId = sourceId,
                    seriesId = "88",
                    episodeId = "p$season",
                    season = season,
                    episodeNumber = 1,
                    title = "Provider $season",
                    plot = null,
                    durationSeconds = null,
                    stillUrl = null,
                    streamUrl = "https://example.com/$season.mkv",
                )
            },
        )
        checkNotNull(db.series().byProviderId(sourceId, "88"))
    }

    private suspend fun addonEpisode(season: Int): Episode {
        val addons = checkNotNull(db.sources().addons())
        return checkNotNull(db.episodes().byProviderId(addons.id, "tt0000301:$season:1"))
    }

    private companion object {
        const val DURATION = 2_700_000L

        /** TMDB's show 301: Seasons 1 to 11, one aired episode each. */
        fun elevenSeasons(request: Request): String = when (request.url.encodedPath) {
            "/3/tv/301/external_ids" -> """{"imdb_id": "tt0000301"}"""
            "/3/tv/301" -> if (request.url.queryParameter("append_to_response").orEmpty().startsWith("season/")) {
                (1..11).joinToString(",", "{", "}") { season ->
                    """"season/$season": {"episodes": [{"season_number": $season, "episode_number": 1,
                        "name": "Chapter $season", "air_date": "2005-01-01"}]}"""
                }
            } else {
                (1..11).joinToString(",", """{"name": "Long Show", "seasons": [""", "]}") {
                    """{"season_number": $it}"""
                }
            }
            else -> "404"
        }
    }
}
