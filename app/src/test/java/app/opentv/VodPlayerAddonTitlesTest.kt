/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamLink
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.MediaKey
import app.opentv.ui.vod.VodPlayerViewModel
import app.opentv.ui.vod.chooseVersion
import app.opentv.ui.vod.playAddonStream
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Titles kept from an add-on (ADR 0011) have no stream of their own, so wherever the player is opened on
 * one, by Continue Watching, the Next Episode card or anything else that goes straight to playing, it
 * asks which add-on stream to play instead of trying to play nothing.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the view model doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodPlayerAddonTitlesTest {

    @get:Rule
    val testGraph = TestGraph()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val playback = FakePlayback()

    private fun testing(block: suspend CoroutineScope.() -> Unit) = testGraph.runOnMain(block)

    @Test
    fun `an add-on film from Continue Watching asks for a stream, and plays the one picked from where it stopped`() =
        testing {
            val film = graph.addonTitles.keepFilm(FILM)
            graph.viewerLibrary.recordProgress("movie:${film.id}", positionMillis = 600_000, EPISODE_MILLIS)
            val vm = testGraph.track(VodPlayerViewModel(app, graph))

            vm.attach(MediaKey.Movie(film.id), playback)

            assertThat(playback.played).isEmpty()
            assertThat(vm.addonPick.value).isEqualTo(MediaKey.Movie(film.id))

            val headers = mapOf("Referer" to "https://example.com/")
            vm.playAddonStream(StremioStream(StreamLink.Direct(STREAM, headers), "Lost Film 1080p", "Films"))

            assertThat(playback.played).containsExactly(
                MediaKey.AddonStream(MediaKey.Movie(film.id), STREAM, headers, addonName = "Films") to 600_000L,
            )
            assertThat(vm.addonPick.value).isNull()
        }

    @Test
    fun `an add-on episode opened from Continue Watching asks for that episode's stream`() = testing {
        val (pilot, _) = keptEpisodes()
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        vm.attach(pilot.key, playback)

        assertThat(playback.played).isEmpty()
        assertThat(vm.addonPick.value).isEqualTo(pilot.key)
    }

    @Test
    fun `while it asks, nothing overwrites where the title stopped`() = testing {
        val film = graph.addonTitles.keepFilm(FILM)
        graph.viewerLibrary.recordProgress("movie:${film.id}", positionMillis = 600_000, EPISODE_MILLIS)
        val vm = testGraph.track(VodPlayerViewModel(app, graph))
        vm.attach(MediaKey.Movie(film.id), playback)

        vm.poll()
        vm.saveProgress()

        assertThat(graph.viewerLibrary.resumeFrom("movie:${film.id}")).isEqualTo(600_000L)
    }

    @Test
    fun `the Next Episode card after an add-on episode offers the next one, and asks for its stream`() = testing {
        val (pilot, second) = keptEpisodes()
        val vm = testGraph.track(VodPlayerViewModel(app, graph))
        vm.attach(MediaKey.AddonStream(pilot.key, STREAM), playback)

        assertThat(vm.nextEpisode.value?.title).isEqualTo("Second")
        vm.playToEnd(playback)

        assertThat(playback.played.map { it.first }).containsExactly(MediaKey.AddonStream(pilot.key, STREAM))
        assertThat(vm.addonPick.value).isEqualTo(second.key)
        assertThat(playback.stopped).isTrue()
    }

    @Test
    fun `a film watched through an add-on resumes where it stopped once the Provider carries it too`() = testing {
        val kept = graph.addonTitles.keepFilm(FILM)
        graph.viewerLibrary.recordProgress("movie:${kept.id}", positionMillis = FORTY_PERCENT, EPISODE_MILLIS)
        val carried = providerCopyOfFilm()
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        // As its Continue Watching card, and the page's Resume, open it.
        vm.attach(MediaKey.Movie(kept.id), playback)

        assertThat(playback.played).containsExactly(MediaKey.Movie(carried.id) to FORTY_PERCENT)
        assertThat(vm.addonPick.value).isNull()
    }

    @Test
    fun `the add-on copy of a film the Provider carries stays a Version to choose, through the picker`() = testing {
        val kept = graph.addonTitles.keepFilm(FILM)
        val carried = providerCopyOfFilm()
        val vm = testGraph.track(VodPlayerViewModel(app, graph))
        vm.attach(MediaKey.Movie(carried.id), playback)

        assertThat(vm.qualityChoices.value.map { it.key }).contains(MediaKey.Movie(kept.id))
        vm.chooseVersion(MediaKey.Movie(kept.id))

        assertThat(vm.addonPick.value).isEqualTo(MediaKey.Movie(kept.id))
    }

    @Test
    fun `an episode watched through an add-on resumes where it stopped on the Provider's copy of the show`() = testing {
        val (pilot, _) = keptEpisodes()
        graph.viewerLibrary.recordProgress(pilot.key.stored, positionMillis = FORTY_PERCENT, EPISODE_MILLIS)
        val carriedPilot = providerCopyOfShow()
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        vm.attach(pilot.key, playback)

        assertThat(playback.played).containsExactly(carriedPilot.key to FORTY_PERCENT)
    }

    @Test
    fun `a Provider's episode with no stream address of its own plays, rather than asking add-ons`() = testing {
        val pilot = providerCopyOfShow().copy(streamUrl = "")
        graph.database.episodes().upsertAll(listOf(pilot))
        val vm = testGraph.track(VodPlayerViewModel(app, graph))

        vm.attach(pilot.key, playback)

        assertThat(vm.addonPick.value).isNull()
        assertThat(playback.played.map { it.first }).containsExactly(pilot.key)
    }

    /** The Provider's own copy of [FILM], grouped with the kept one as one title (ADR 0007). */
    private suspend fun providerCopyOfFilm(): Movie {
        graph.database.sources().insert(PROVIDER)
        graph.database.movies().upsertAll(
            listOf(
                Movie(
                    sourceId = PROVIDER.id,
                    streamId = "1",
                    name = "EN - Lost Film",
                    categoryId = null,
                    posterUrl = null,
                    rating = null,
                    year = 1999,
                    plot = null,
                    durationSeconds = null,
                    containerExtension = "mp4",
                    streamUrl = "http://example.com/movie/u/p/1.mp4",
                    tmdbId = FILM.tmdbId,
                ),
            ),
        )
        return checkNotNull(graph.database.movies().byProviderId(PROVIDER.id, "1"))
    }

    /** The Provider's own copy of [SHOW], with its pilot, grouped with the kept one; its pilot is returned. */
    private suspend fun providerCopyOfShow(): Episode {
        graph.database.sources().insert(PROVIDER)
        graph.database.series().upsertAll(
            listOf(
                Series(
                    sourceId = PROVIDER.id,
                    seriesId = "7",
                    name = "EN - Lost Show",
                    categoryId = null,
                    posterUrl = null,
                    rating = null,
                    year = 2010,
                    plot = null,
                    tmdbId = SHOW.tmdbId,
                ),
            ),
        )
        graph.database.episodes().upsertAll(
            listOf(
                Episode(
                    sourceId = PROVIDER.id,
                    seriesId = "7",
                    episodeId = "70",
                    season = 1,
                    episodeNumber = 1,
                    title = "Pilot",
                    plot = null,
                    durationSeconds = 2_400,
                    stillUrl = null,
                    streamUrl = "http://example.com/series/u/p/70.mp4",
                ),
            ),
        )
        return graph.database.episodes().forSeries(PROVIDER.id, "7").single()
    }

    private suspend fun keptEpisodes(): Pair<Episode, Episode> {
        val show: Series = graph.addonTitles.keepShow(SHOW)
        graph.database.episodes().upsertAll(
            listOf(1 to "Pilot", 2 to "Second").map { (number, title) ->
                Episode(
                    sourceId = show.sourceId,
                    seriesId = show.seriesId,
                    episodeId = "${show.seriesId}:1:$number",
                    season = 1,
                    episodeNumber = number,
                    title = title,
                    plot = null,
                    durationSeconds = 2_400,
                    stillUrl = null,
                    streamUrl = "",
                )
            },
        )
        val stored = graph.database.episodes().forSeries(show.sourceId, show.seriesId).sortedBy { it.episodeNumber }
        return stored[0] to stored[1]
    }

    private companion object {
        const val STREAM = "https://example.com/stream.mkv"
        const val FORTY_PERCENT = EPISODE_MILLIS * 2 / 5

        val PROVIDER = Source(id = 50, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com")

        val FILM = AddonTitle(
            tmdbId = "101",
            imdbId = "tt0000101",
            name = "Lost Film",
            year = 1999,
            posterUrl = null,
            backdropUrl = null,
            overview = null,
        )

        val SHOW = AddonTitle(
            tmdbId = "201",
            imdbId = "tt0000201",
            name = "Lost Show",
            year = 2010,
            posterUrl = null,
            backdropUrl = null,
            overview = null,
            isMovie = false,
        )
    }
}
