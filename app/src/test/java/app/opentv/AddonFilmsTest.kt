/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.AddonTitle
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.mediaKey
import app.opentv.data.repo.movies
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
 * Films the Provider doesn't carry (#671): found through TMDB once an add-on is set up, and kept as
 * titles of the built-in Add-ons source once opened. TMDB is faked; IMDb ids are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonFilmsTest {

    private val network = FakeJsonHttp { request: Request ->
        when (request.url.encodedPath) {
            "/3/search/multi" -> SEARCH
            "/3/movie/101" -> """{"imdb_id": "tt0000101"}"""
            "/3/movie/102" -> """{"imdb_id": null}"""
            "/3/movie/105" -> """{"imdb_id": "tt0000105"}"""
            else -> "404"
        }
    }

    @get:Rule
    val testGraph = TestGraph(name = "addon-films-test", http = network.http)

    private val graph = testGraph.graph.apply { tmdbKey = "key" }
    private val db = testGraph.database

    @After
    fun close() {
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    private fun addAddon() = graph.settings.addStremioAddon(
        StremioAddon(
            "https://example.com/manifest.json",
            "Example",
            listOf("stream"),
            listOf("movie"),
            listOf("movie"),
        ),
    )

    @Test
    fun `without an add-on, search asks TMDB nothing and finds nothing more`(): Unit = runBlocking {
        assertThat(graph.addonTitles.search("lost")).isEmpty()
        assertThat(network.asked).isEmpty()
    }

    @Test
    fun `with an add-on, search finds films with an IMDb id the Provider doesn't carry`(): Unit = runBlocking {
        db.sources().insert(PROVIDER)
        db.movies().upsertAll(listOf(providerFilm(tmdbId = "105")))
        addAddon()

        val found = graph.addonTitles.search("lost")

        assertThat(found).containsExactly(
            AddonTitle(
                tmdbId = "101",
                imdbId = "tt0000101",
                name = "Lost Film",
                year = 1999,
                posterUrl = "https://image.tmdb.org/t/p/w500/lost.jpg",
                backdropUrl = "https://image.tmdb.org/t/p/w1280/lost-wide.jpg",
                overview = "A film nobody carries.",
            ),
        )
    }

    @Test
    fun `opening a film keeps it under the Add-ons source by its IMDb id, with no stream URL`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepFilm(LOST)

        val source = db.sources().byId(kept.sourceId)
        assertThat(source?.name).isEqualTo("Add-ons")
        assertThat(source?.kind).isEqualTo(SourceKind.ADDONS)
        assertThat(kept.streamId).isEqualTo("tt0000101")
        assertThat(kept.tmdbId).isEqualTo("101")
        assertThat(kept.streamUrl).isEmpty()
        assertThat(kept.plot).isEqualTo("A film nobody carries.")
    }

    @Test
    fun `opening it again finds the film kept the first time`(): Unit = runBlocking {
        val first = graph.addonTitles.keepFilm(LOST)
        val second = graph.addonTitles.keepFilm(LOST.copy(name = "Lost Film (renamed)"))

        assertThat(second.id).isEqualTo(first.id)
        assertThat(db.sources().everySource().filter { it.kind == SourceKind.ADDONS }).hasSize(1)
    }

    @Test
    fun `the Add-ons source isn't listed with the Providers`(): Unit = runBlocking {
        db.sources().insert(PROVIDER)
        graph.addonTitles.keepFilm(LOST)

        assertThat(graph.sourceRepository.all().map { it.name }).containsExactly("Provider")
        assertThat(graph.sourceRepository.enabled().map { it.name }).containsExactly("Provider")
        assertThat(graph.sourceRepository.observeAll().first().map { it.name }).containsExactly("Provider")
    }

    @Test
    fun `a Provider can't be given the Add-ons source's name`(): Unit = runBlocking {
        val id = graph.sourceRepository.save(PROVIDER.copy(id = 0, name = "add-ons"))

        assertThat(db.sources().byId(id)?.name).isEqualTo("add-ons 2")
    }

    @Test
    fun `removing every add-on leaves the kept films in place`(): Unit = runBlocking {
        addAddon()
        val kept = graph.addonTitles.keepFilm(LOST)

        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }

        assertThat(db.movies().byId(kept.id)).isEqualTo(kept)
    }

    @Test
    fun `a kept film remembers where it stopped and comes back in Continue Watching`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepFilm(LOST)

        graph.viewerLibrary.recordProgress(kept.mediaKey, positionMillis = DURATION / 4, durationMillis = DURATION)

        assertThat(graph.viewerLibrary.resumeFrom(kept.mediaKey)).isEqualTo(DURATION / 4)
        val status = graph.viewerLibrary.titleStatus.movie(kept.id).first() as CoverStatus.InProgress
        assertThat(status.progress).isEqualTo(0.25f)
        assertThat(graph.viewerLibrary.continueWatching().first())
            .containsExactly(ContinueWatchingCard.Movie(kept.id, 0.25f))
    }

    @Test
    fun `a kept film counts as Watched past 93 percent`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepFilm(LOST)

        graph.viewerLibrary.recordProgress(
            kept.mediaKey,
            positionMillis = DURATION * 94 / 100,
            durationMillis = DURATION,
        )

        assertThat(graph.viewerLibrary.titleStatus.movie(kept.id).first()).isEqualTo(CoverStatus.Watched)
        assertThat(graph.viewerLibrary.continueWatching().first()).isEmpty()
    }

    @Test
    fun `a kept film can be a Favourite`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepFilm(LOST)

        graph.viewerLibrary.favourites.set(kept, favourite = true)

        assertThat(graph.viewerLibrary.favourites.movies().first().map { it.id }).containsExactly(kept.id)
    }

    @Test
    fun `a kept film the Provider also carries is one title with two Versions`(): Unit = runBlocking {
        db.sources().insert(PROVIDER)
        val carried = providerFilm(tmdbId = "101")
        db.movies().upsertAll(listOf(carried))
        val kept = graph.addonTitles.keepFilm(LOST)

        testGraph.firstWhenIdle(graph.titleIndex.movies) { it?.of(kept.id) != null }

        assertThat(graph.titleIndex.sameTitle(StreamKind.MOVIE, kept.id, carried.id)).isTrue()
        // Play on its page plays the Provider's copy: the add-on's plays through the picker.
        assertThat(graph.vodQualities.planFor(MediaKey.Movie(kept.id)).start.key).isEqualTo(MediaKey.Movie(carried.id))
    }

    @Test
    fun `a kept film nobody else carries plays as itself, through the picker`(): Unit = runBlocking {
        val kept = graph.addonTitles.keepFilm(LOST)

        assertThat(graph.vodQualities.planFor(MediaKey.Movie(kept.id)).start.key).isEqualTo(MediaKey.Movie(kept.id))
    }

    private fun providerFilm(tmdbId: String) = Movie(
        id = 1,
        sourceId = PROVIDER.id,
        streamId = "1",
        name = "EN - Carried Film",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2001,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "http://example.com/movie/u/p/1.mp4",
        tmdbId = tmdbId,
    )

    private companion object {
        const val DURATION = 6_000_000L

        val LOST = AddonTitle(
            tmdbId = "101",
            imdbId = "tt0000101",
            name = "Lost Film",
            year = 1999,
            posterUrl = null,
            backdropUrl = null,
            overview = "A film nobody carries.",
        )

        val PROVIDER = Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com")

        // A film with an IMDb id, one without, a show and a person (both left for later), and a film
        // the Provider already carries.
        val SEARCH = """
            {"results": [
              {"media_type": "movie", "id": 101, "title": "Lost Film", "release_date": "1999-04-01",
               "poster_path": "/lost.jpg", "backdrop_path": "/lost-wide.jpg", "overview": "A film nobody carries."},
              {"media_type": "movie", "id": 102, "title": "Unlisted Film", "release_date": "2003-01-01"},
              {"media_type": "tv", "id": 103, "name": "Lost Show", "first_air_date": "2010-01-01"},
              {"media_type": "person", "id": 104, "name": "Someone"},
              {"media_type": "movie", "id": 105, "title": "Carried Film", "release_date": "2001-01-01"}
            ]}
        """.trimIndent()
    }
}
