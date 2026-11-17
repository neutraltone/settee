/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.PageBack.Companion.isClickable
import app.opentv.data.model.Movie
import app.opentv.ui.vod.MovieDetailContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back to a film's page from a person or a film opened on it (#429). Apart from [MovieDetailContentTest],
 * and with its own screenshots of the page as Back finds it, because only a page in a NavHost is disposed
 * and built again, which is what loses where the Highlight was.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MoviePageBackTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val page = PageBack(compose)

    private val film = Movie(
        id = 7,
        sourceId = 1,
        streamId = "m",
        name = "The Quiet Road",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = 7.8,
        year = 2021,
        plot = "A long drive home, and everything said on the way.",
        durationSeconds = 112 * 60,
        containerExtension = "mkv",
        streamUrl = "https://example.com/m.mkv",
        backdropUrl = "https://example.com/backdrop.jpg",
        genre = "Drama",
        cast = "Somebody Famous, Second Billing, Someone Else, Fourth Billing",
        director = "Someone Else",
    )

    private val uncredited get() = film.copy(cast = null, director = null)

    private fun similarFilm(id: Long) = film.copy(id = id, streamId = "m$id", name = "Like $id")

    private val manySimilar = (1L..10L).map { similarFilm(100 + it) }

    /**
     * [movie]'s page. More like this is read each time the page is built, after it shows, as
     * [app.opentv.ui.vod.MovieDetailScreen] reads it, from [similar].
     */
    private fun showPage(movie: Movie, similar: () -> List<Movie>) = page.show { leave ->
        var row by remember { mutableStateOf<List<Movie>?>(null) }
        LaunchedEffect(Unit) { row = similar() }
        MovieDetailContent(
            movie = movie,
            resumes = false,
            status = null,
            favourite = false,
            findAddonStreams = null,
            similar = row,
            onOpenMovie = { leave() },
            onOpenPerson = { leave() },
            onPlay = leave,
            onPlayAddonStream = {},
            onSetFavourite = {},
        )
    }

    @Test
    fun `Back from a person opened from the cast puts the highlight back on their name`() {
        showPage(film) { emptyList() }
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        page.clickable("Second Billing").assertIsFocused()
        page.openHighlighted()

        page.back()

        page.clickable("Second Billing").assertIsFocused().assertIsDisplayed()
        captureScreen("movie_detail_back_from_person")
    }

    @Test
    fun `Back from the director who is also in the cast puts the highlight back on the director, not the cast`() {
        showPage(film) { emptyList() }
        page.press(Key.DirectionDown)
        page.press(Key.DirectionDown)
        page.openHighlighted()

        page.back()

        assertThat(page.highlightedText()).isEqualTo("Someone Else")
        // The director's pill is the lower of the two with that name.
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        val inCast = compose.onAllNodes(isClickable("Someone Else")).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        assertThat(focused.top).isGreaterThan(inCast)
    }

    @Test
    fun `Back from a film opened from More like this puts the highlight back on its cover`() {
        showPage(uncredited) { manySimilar }
        page.press(Key.DirectionDown)
        repeat(RIGHT_PRESSES) { page.press(Key.DirectionRight) }
        page.clickable("Like 107").assertIsFocused()
        page.openHighlighted()

        page.back()

        page.clickable("Like 107").assertIsFocused().assertIsDisplayed()
        captureScreen("movie_detail_back_from_like_this")
    }

    @Test
    fun `Back to the page when the film opened has left More like this puts the highlight on Play`() {
        var similar = manySimilar
        showPage(uncredited) { similar }
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        page.openHighlighted()
        similar = manySimilar.filterNot { it.name == "Like 102" }

        page.back()

        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("A long drive home, and everything said on the way.").assertIsDisplayed()
    }

    @Test
    fun `Back to the page when the person opened is no longer credited puts the highlight on Play`() {
        var movie = film
        page.show { leave ->
            MovieDetailContent(
                movie = movie,
                resumes = false,
                status = null,
                favourite = false,
                findAddonStreams = null,
                similar = emptyList(),
                onOpenMovie = {},
                onOpenPerson = { leave() },
                onPlay = {},
                onPlayAddonStream = {},
                onSetFavourite = {},
            )
        }
        page.press(Key.DirectionDown)
        page.press(Key.DirectionRight)
        page.openHighlighted()
        movie = film.copy(cast = "Somebody Famous")

        page.back()

        compose.onNodeWithText("Play").assertIsFocused()
    }

    @Test
    fun `Back from playing the film puts the highlight back on Play`() {
        showPage(film) { manySimilar }
        page.press(Key.DirectionDown)
        page.press(Key.DirectionUp)
        page.openHighlighted()

        page.back()

        compose.onNodeWithText("Play").assertIsFocused()
    }

    private companion object {
        /** Far enough along the row that the cover was scrolled to. */
        const val RIGHT_PRESSES = 6
    }
}
