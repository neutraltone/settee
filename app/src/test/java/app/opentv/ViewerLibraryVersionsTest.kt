/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.Profile
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.ContinueWatchingCard
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.ViewerLibrary
import app.opentv.data.repo.VodTitle
import app.opentv.data.repo.WatchStatus
import app.opentv.data.repo.mediaKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A title's watch state reads across its Versions, while what's stored stays on the Version that made it
 * (#181, ADR 0007).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewerLibraryVersionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private var now = 1_000_000L
    private val library = ViewerLibrary(db, MutableStateFlow(1L), clock = { now })

    private val netflix = movie(1, "NF - Superbad (2007)")
    private val english = movie(2, "EN - Superbad (2007)")

    @Before
    fun catalogue() = runTest {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.profiles().insert(Profile(id = 1, name = "Me", createdAtMillis = 1))
        db.movies().upsertAll(listOf(netflix, english, movie(3, "EN - Juno (2007)", tmdbId = "7326")))
    }

    @After
    fun close() = db.close()

    @Test
    fun `a film is Watched when any of its Versions is`() = runTest {
        played(netflix.mediaKey, fraction = 1.0)
        later()
        played(english.mediaKey, fraction = 0.25)

        assertThat(library.titleStatus.movie(english.id).first()).isEqualTo(CoverStatus.Watched)
    }

    @Test
    fun `a film's status comes from the Version played most recently`() = runTest {
        played(netflix.mediaKey, fraction = 0.5)
        later()
        played(english.mediaKey, fraction = 0.25)

        val status = library.titleStatus.movie(netflix.id).first() as CoverStatus.InProgress

        assertThat(status.progress).isEqualTo(0.25f)
    }

    @Test
    fun `Continue Watching shows a film once, at the Version played most recently`() = runTest {
        played(netflix.mediaKey, fraction = 0.5)
        later()
        played(english.mediaKey, fraction = 0.25)

        assertThat(library.continueWatching().first())
            .containsExactly(ContinueWatchingCard.Movie(english.id, 0.25f))
    }

    @Test
    fun `an episode Watched in one Version shows Watched on the show`() = runTest {
        val (first, second) = twoVersionsOfAShow()
        played(first[0].mediaKey, fraction = 1.0)

        val statuses = library.episodeStatuses().first()

        assertThat(statuses.anyOf(listOf(first[0].id, second[0].id))).isEqualTo(WatchStatus.Watched)
        assertThat(statuses.anyOf(listOf(first[1].id, second[1].id))).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `Up Next moves past an episode Watched in another Version`() = runTest {
        val (first, second) = twoVersionsOfAShow()
        played(second[0].mediaKey, fraction = 1.0)

        val action = library.seriesMainAction(first + second).first()

        assertThat(action).isInstanceOf(SeriesMainAction.Play::class.java)
        assertThat(action!!.episode.episodeNumber).isEqualTo(2)
    }

    @Test
    fun `starring one Version makes the title a Favourite`() = runTest {
        library.favourites.set(netflix, true)

        assertThat(library.favourites.isFavourite(VodTitle.OfMovie(english))).isTrue()
        assertThat(
            library.favourites.isFavourite(VodTitle.OfMovie(movie(3, "EN - Juno (2007)", tmdbId = "7326"))),
        ).isFalse()
    }

    @Test
    fun `unstarring a title clears every Version, and sync carries the same keys`() = runTest {
        library.favourites.set(netflix, true)
        library.favourites.set(english, true)
        later()

        library.favourites.set(english, false)

        assertThat(library.favourites.isFavourite(VodTitle.OfMovie(netflix))).isFalse()
        assertThat(library.favourites.export().map { it.id to it.removed })
            .containsExactly("1" to true, "2" to true)
    }

    @Test
    fun `a title starred in two Versions is one Favourite`() = runTest {
        library.favourites.set(netflix, true)
        later()
        library.favourites.set(english, true)

        val titles = library.favourites.titles().first()

        assertThat(titles.map { (it as VodTitle.OfMovie).movie.id }).containsExactly(english.id)
    }

    /** Two Versions of a two-episode show, each Version's episodes in order. */
    private suspend fun twoVersionsOfAShow(): Pair<List<Episode>, List<Episode>> {
        db.series().upsertAll(listOf(series(1, "NF - Dragons (2022)"), series(2, "EN - Dragons (2022)")))
        db.episodes().upsertAll(
            listOf(
                testEpisode(1, 1).copy(id = 11, seriesId = "s1", episodeId = "a"),
                testEpisode(1, 2).copy(id = 12, seriesId = "s1", episodeId = "b"),
                testEpisode(1, 1).copy(id = 21, seriesId = "s2", episodeId = "c"),
                testEpisode(1, 2).copy(id = 22, seriesId = "s2", episodeId = "d"),
            ),
        )
        return db.episodes().forSeries(1, "s1") to db.episodes().forSeries(1, "s2")
    }

    private suspend fun played(mediaKey: String, fraction: Double) {
        library.recordProgress(mediaKey, positionMillis = (DURATION * fraction).toLong(), durationMillis = DURATION)
    }

    private fun later() {
        now += 60_000
    }

    private fun movie(id: Long, name: String, tmdbId: String = "8363") = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id",
        tmdbId = tmdbId,
    )

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "s$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2022,
        plot = null,
        tmdbId = "94997",
    )

    private companion object {
        const val DURATION = 6_000_000L
    }
}
