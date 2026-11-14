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
import app.opentv.data.model.Series
import app.opentv.data.repo.VodTitle
import app.opentv.ui.vod.PersonContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A person's page: everything in the library with them in it, as covers that open each title's page. */
// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PersonContentTest {

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
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/m.mkv",
    )

    private val show = Series(
        id = 3,
        sourceId = 1,
        seriesId = "s",
        name = "The Long Way Round",
        categoryId = null,
        posterUrl = "https://example.com/show.jpg",
        rating = null,
        year = 2024,
        plot = null,
    )

    private var openedMovie: Movie? = null
    private var openedSeries: Series? = null

    private fun showPage(titles: List<VodTitle>?) {
        compose.setTvContent {
            PersonContent(
                name = "Somebody Famous",
                titles = titles,
                onOpenMovie = { openedMovie = it },
                onOpenSeries = { openedSeries = it },
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `a person's page opens on their first title, with their films before their shows`() {
        showPage(listOf(VodTitle.OfMovie(film), VodTitle.OfSeries(show)))

        compose.onNodeWithText("Somebody Famous").assertIsDisplayed()
        compose.onNode(hasText("The Quiet Road") and hasClickAction()).assertIsFocused()
        captureScreen("person")

        press(Key.DirectionRight)
        press(Key.DirectionCenter)

        assertThat(openedSeries).isEqualTo(show)
    }

    private lateinit var nav: NavHostController

    /**
     * The page in a NavHost, as the app has it, so Back from a title opened on it builds it again. Its
     * titles load each time it's built, as [app.opentv.ui.vod.PersonScreen]'s do, from [library].
     */
    private fun showPageInApp(library: () -> List<VodTitle>) {
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "person") {
                composable("person") {
                    var titles by remember { mutableStateOf<List<VodTitle>?>(null) }
                    LaunchedEffect(Unit) { titles = library() }
                    PersonContent(
                        name = "Somebody Famous",
                        titles = titles,
                        onOpenMovie = { nav.navigate("title") },
                        onOpenSeries = { nav.navigate("title") },
                    )
                }
                composable("title") { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun backToPage() {
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun cover(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test
    fun `Back from a title opened on the page puts the highlight back on that title`() {
        showPageInApp { listOf(VodTitle.OfMovie(film), VodTitle.OfSeries(show)) }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.runOnIdle { assertThat(nav.currentDestination?.route).isEqualTo("title") }

        backToPage()

        cover("The Long Way Round").assertIsFocused()
        captureScreen("person_back_from_title")
    }

    @Test
    fun `Back to the page when the title opened has gone puts the highlight on the first`() {
        var library = listOf(VodTitle.OfMovie(film), VodTitle.OfSeries(show))
        showPageInApp { library }
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        library = listOf(VodTitle.OfMovie(film))

        backToPage()

        cover("The Quiet Road").assertIsFocused()
    }

    @Test
    fun `Back from a title further down the page scrolls back to it`() {
        val films = (1L..40L).map { VodTitle.OfMovie(film.copy(id = it, streamId = "m$it", name = "Film $it")) }
        showPageInApp { films }
        repeat(DOWN_PRESSES) { press(Key.DirectionDown) }
        val picked = compose.onNode(isFocused()).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString()
        press(Key.DirectionCenter)

        backToPage()

        cover(picked).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun `OK on a film opens its page`() {
        showPage(listOf(VodTitle.OfMovie(film)))

        press(Key.DirectionCenter)

        assertThat(openedMovie).isEqualTo(film)
    }

    @Test
    fun `a person with nothing in the library says so`() {
        showPage(emptyList())

        compose.onNodeWithText("Nothing in your library has Somebody Famous in it yet.").assertIsDisplayed()
        captureScreen("person_empty")
    }

    @Test
    fun `while the titles load the page shows only the name`() {
        showPage(null)

        compose.onNodeWithText("Somebody Famous").assertIsDisplayed()
        compose.onNode(hasText("Nothing in your library", substring = true)).assertDoesNotExist()
    }

    private companion object {
        /** Enough rows down that the first screen of covers has scrolled away. */
        const val DOWN_PRESSES = 4
    }
}
