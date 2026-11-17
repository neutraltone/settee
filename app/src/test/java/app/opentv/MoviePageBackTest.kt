/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
 * Back to a film's page from a person or a film opened on it (#429). The page sits in a NavHost, as the
 * app has it, so it's disposed while the other screen covers it and built again on Back.
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

    private lateinit var nav: NavHostController

    /**
     * [movie]'s page in a NavHost beside a stand-in for wherever it leads. More like this is read each
     * time the page is built, after it shows, as [app.opentv.ui.vod.MovieDetailScreen] reads it, from
     * [similar].
     */
    private fun showPageInApp(movie: Movie, similar: () -> List<Movie>) {
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "film") {
                composable("film") {
                    var row by remember { mutableStateOf<List<Movie>?>(null) }
                    LaunchedEffect(Unit) { row = similar() }
                    MovieDetailContent(
                        movie = movie,
                        resumes = false,
                        status = null,
                        favourite = false,
                        findAddonStreams = null,
                        similar = row,
                        onOpenMovie = { nav.navigate("elsewhere") },
                        onOpenPerson = { nav.navigate("elsewhere") },
                        onPlay = { nav.navigate("elsewhere") },
                        onPlayAddonStream = {},
                        onSetFavourite = {},
                    )
                }
                composable("elsewhere") { Box(Modifier.fillMaxSize()) }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    private fun openHighlighted() {
        press(Key.DirectionCenter)
        compose.runOnIdle { assertThat(nav.currentDestination?.route).isEqualTo("elsewhere") }
    }

    private fun backToPage() {
        compose.runOnIdle { nav.popBackStack() }
        settle()
    }

    private fun clickable(text: String) = compose.onNode(hasText(text) and hasClickAction())

    @Test
    fun `Back from a person opened from the cast puts the highlight back on their name`() {
        showPageInApp(film) { emptyList() }
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        clickable("Second Billing").assertIsFocused()
        openHighlighted()

        backToPage()

        clickable("Second Billing").assertIsFocused().assertIsDisplayed()
        captureScreen("movie_detail_back_from_person")
    }

    @Test
    fun `Back from the director who is also in the cast puts the highlight back on the director, not the cast`() {
        showPageInApp(film) { emptyList() }
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        openHighlighted()

        backToPage()

        // The director's pill is the lower of the two with that name.
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
        val inCast = compose.onAllNodes(hasText("Someone Else") and hasClickAction())
            .fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        assertThat(compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString())
            .isEqualTo("Someone Else")
        assertThat(focused.top).isGreaterThan(inCast)
    }

    @Test
    fun `Back from a film opened from More like this puts the highlight back on its cover`() {
        showPageInApp(uncredited) { manySimilar }
        press(Key.DirectionDown)
        repeat(RIGHT_PRESSES) { press(Key.DirectionRight) }
        clickable("Like 107").assertIsFocused()
        openHighlighted()

        backToPage()

        clickable("Like 107").assertIsFocused().assertIsDisplayed()
        captureScreen("movie_detail_back_from_like_this")
    }

    @Test
    fun `Back to the page when the film opened has left More like this puts the highlight on Play`() {
        var similar = manySimilar
        showPageInApp(uncredited) { similar }
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        openHighlighted()
        similar = manySimilar.filterNot { it.name == "Like 102" }

        backToPage()

        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("A long drive home, and everything said on the way.").assertIsDisplayed()
    }

    @Test
    fun `Back from playing the film puts the highlight back on Play`() {
        showPageInApp(film) { manySimilar }
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        openHighlighted()

        backToPage()

        compose.onNodeWithText("Play").assertIsFocused()
    }

    private companion object {
        /** Far enough along the row that the cover was scrolled to. */
        const val RIGHT_PRESSES = 6
    }
}
