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
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.ViewerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViewerLibraryFavouritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val activeProfile = MutableStateFlow(1L)
    private val library = ViewerLibrary(db.positions(), db.favourites(), activeProfile)

    @After
    fun close() = db.close()

    private fun channel(streamId: String, name: String, hidden: Boolean = false) = Channel(
        sourceId = 1,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
        hidden = hidden,
    )

    private suspend fun storedChannels(vararg channels: Channel): List<Channel> {
        db.channels().replaceCatalogue(sourceId = 1, incoming = channels.toList(), syncStamp = 1)
        return db.channels().forSource(1).sortedBy { it.streamId }
    }

    private suspend fun movie(streamId: String, name: String): Movie {
        db.movies().upsertAll(
            listOf(
                Movie(
                    sourceId = 1,
                    streamId = streamId,
                    name = name,
                    categoryId = null,
                    posterUrl = null,
                    rating = null,
                    year = null,
                    plot = null,
                    durationSeconds = null,
                    containerExtension = "mp4",
                    streamUrl = "http://example.com/movie/u/p/$streamId.mp4",
                ),
            ),
        )
        return db.movies().all().single { it.streamId == streamId }
    }

    private suspend fun series(seriesId: String, name: String): Series {
        db.series().upsertAll(
            listOf(
                Series(
                    sourceId = 1,
                    seriesId = seriesId,
                    name = name,
                    categoryId = null,
                    posterUrl = null,
                    rating = null,
                    year = null,
                    plot = null,
                ),
            ),
        )
        return db.series().all().single { it.seriesId == seriesId }
    }

    @Test
    fun `a movie the Profile favourites is one of its Favourite movies`() = runTest {
        val heat = movie("1", "Heat")
        movie("2", "Ronin")

        library.setFavourite(heat, favourite = true)

        assertThat(library.favouriteMovies().first().map { it.name }).containsExactly("Heat")
    }

    @Test
    fun `a movie the Profile unfavourites is no longer a Favourite`() = runTest {
        val heat = movie("1", "Heat")
        library.setFavourite(heat, favourite = true)

        library.setFavourite(heat, favourite = false)

        assertThat(library.favouriteMovies().first()).isEmpty()
    }

    @Test
    fun `switching Profile switches Favourites`() = runTest {
        val heat = movie("1", "Heat")
        val ronin = movie("2", "Ronin")
        library.setFavourite(heat, favourite = true)

        activeProfile.value = 2L
        library.setFavourite(ronin, favourite = true)

        assertThat(library.favouriteMovies().first().map { it.name }).containsExactly("Ronin")
        activeProfile.value = 1L
        assertThat(library.favouriteMovies().first().map { it.name }).containsExactly("Heat")
    }

    @Test
    fun `a series the Profile favourites is one of its Favourite series, and can be unfavourited`() = runTest {
        val severance = series("10", "Severance")
        val slowHorses = series("11", "Slow Horses")

        library.setFavourite(severance, favourite = true)
        library.setFavourite(slowHorses, favourite = true)
        library.setFavourite(slowHorses, favourite = false)

        assertThat(library.favouriteSeries().first().map { it.name }).containsExactly("Severance")
    }

    @Test
    fun `Favourite channels leave out channels hidden on this device`() = runTest {
        val (bbcOne, bbcTwo) = storedChannels(channel("1", "BBC One"), channel("2", "BBC Two", hidden = true))

        library.setFavourite(bbcOne, favourite = true)
        library.setFavourite(bbcTwo, favourite = true)

        assertThat(library.favouriteChannels().first().map { it.name }).containsExactly("BBC One")
    }

    @Test
    fun `a Favourite channel stays a Favourite when a catalogue refresh drops it and lists it again`() = runTest {
        val (bbcOne) = storedChannels(channel("1", "BBC One"))
        library.setFavourite(bbcOne, favourite = true)

        db.channels().replaceCatalogue(sourceId = 1, incoming = listOf(channel("2", "BBC Two")), syncStamp = 2)
        db.channels().replaceCatalogue(sourceId = 1, incoming = listOf(channel("1", "BBC One")), syncStamp = 3)

        val favourites = library.favouriteChannels().first()
        assertThat(favourites.map { it.name }).containsExactly("BBC One")
        assertThat(favourites.single().id).isNotEqualTo(bbcOne.id)
    }

    @Test
    fun `Favourite movies and series stay Favourites when their provider's catalogue is reloaded`() = runTest {
        library.setFavourite(movie("1", "Heat"), favourite = true)
        library.setFavourite(series("10", "Severance"), favourite = true)

        db.movies().deleteForSource(1)
        db.series().deleteForSource(1)
        movie("1", "Heat")
        series("10", "Severance")

        assertThat(library.favouriteMovies().first().map { it.name }).containsExactly("Heat")
        assertThat(library.favouriteSeries().first().map { it.name }).containsExactly("Severance")
    }
}
