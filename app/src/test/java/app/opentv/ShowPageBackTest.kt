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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.TitleCover
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back to a show's page from an episode played, a person or a show opened on it (#429). The page sits
 * in a NavHost, as the app has it, so it's disposed while the other screen covers it and built again on
 * Back.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ShowPageBackTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val series = Series(
        id = 1,
        sourceId = 1,
        seriesId = "s",
        name = "The Long Way Round",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = 2024,
        plot = "Two friends and a lot of road.",
        backdropUrl = "https://example.com/backdrop.jpg",
        cast = "Lead Actor, Second Lead",
    )

    private val episodes = listOf(1, 2).flatMap { season -> (1..5).map { testEpisode(season, it) } }

    private fun likeThis(count: Int) = (1..count).map { i ->
        TitleCover(id = 100L + i, name = "Show $i", title = "Show $i", posterUrl = "https://example.com/$i.jpg")
    }

    private val likeThis = likeThis(6)
    private val manyLikeThis = likeThis(40)

    private lateinit var nav: NavHostController

    /**
     * The page in a NavHost beside a stand-in for the player, a person's page and another show's. Its
     * More like this is read each time it's built, after it shows, as
     * [app.opentv.ui.vod.SeriesDetailScreen] reads it, from [suggestions].
     */
    private fun showPageInApp(mainAction: SeriesMainAction, suggestions: () -> List<TitleCover>) {
        compose.setTvContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "show") {
                composable("show") {
                    var read by remember { mutableStateOf<List<TitleCover>?>(null) }
                    LaunchedEffect(Unit) { read = suggestions() }
                    SeriesDetailContent(
                        series = series,
                        episodes = episodes,
                        statuses = EpisodeStatuses(),
                        mainAction = mainAction,
                        status = null,
                        favourite = false,
                        onPlayEpisode = { nav.navigate("elsewhere") },
                        onSetFavourite = {},
                        onOpenPerson = { nav.navigate("elsewhere") },
                        suggestions = read.orEmpty(),
                        onOpenSeries = { nav.navigate("elsewhere") },
                        suggestionsKnown = read != null,
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

    private fun pressInPanel(key: Key) {
        compose.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(key) }
        settle()
    }

    private fun assertLeft() = compose.runOnIdle { assertThat(nav.currentDestination?.route).isEqualTo("elsewhere") }

    private fun backToPage() {
        compose.runOnIdle { nav.popBackStack() }
        settle()
    }

    private fun clickable(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun episodeRow(season: Int, episode: Int) = clickable("S$season E$episode")

    /** From the main button onto More like this's pill, by way of the episodes. */
    private fun goToLikeThisPill() {
        press(Key.DirectionDown)
        press(Key.DirectionUp)
        press(Key.DirectionRight)
        clickable("More like this").assertIsFocused()
    }

    @Test
    fun `Back from a show opened from More like this highlights its cover, with More like this selected`() {
        showPageInApp(SeriesMainAction.Resume(testEpisode(2, 1))) { likeThis }
        goToLikeThisPill()
        press(Key.DirectionDown)
        repeat(RIGHT_PRESSES) { press(Key.DirectionRight) }
        clickable("Show 3").assertIsFocused()
        press(Key.DirectionCenter)
        assertLeft()

        backToPage()

        clickable("Show 3").assertIsFocused().assertIsDisplayed()
        clickable("More like this").assertIsSelected()
        clickable("Season 2").assertIsNotSelected()
        captureScreen("series_detail_back_from_like_this")
    }

    @Test
    fun `Back from a show far down More like this scrolls back to its cover`() {
        showPageInApp(SeriesMainAction.Resume(testEpisode(2, 1))) { manyLikeThis }
        goToLikeThisPill()
        press(Key.DirectionDown)
        repeat(DOWN_PRESSES) { press(Key.DirectionDown) }
        val name = compose.onNode(isFocused()).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString()
        press(Key.DirectionCenter)
        assertLeft()

        backToPage()

        clickable(name).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun `Back to the page when the show opened has left More like this puts the highlight on the main button`() {
        var suggestions = likeThis
        showPageInApp(SeriesMainAction.Resume(testEpisode(2, 1))) { suggestions }
        goToLikeThisPill()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertLeft()
        suggestions = likeThis.drop(1)

        backToPage()

        clickable("Resume S2 E1").assertIsFocused()
    }

    @Test
    fun `Back from an episode played puts the highlight back on it, in its season`() {
        showPageInApp(SeriesMainAction.Resume(testEpisode(1, 4))) { emptyList() }
        press(Key.DirectionDown)
        episodeRow(1, 4).assertIsFocused()
        repeat(3) { press(Key.DirectionDown) }
        episodeRow(2, 2).assertIsFocused()
        press(Key.DirectionCenter)
        assertLeft()

        backToPage()

        episodeRow(2, 2).assertIsFocused().assertIsDisplayed()
        clickable("Season 2").assertIsSelected()
        clickable("Season 1").assertIsNotSelected()
        captureScreen("series_detail_back_from_episode")
    }

    @Test
    fun `Back from playing the main button's episode puts the highlight back on the main button`() {
        showPageInApp(SeriesMainAction.Resume(testEpisode(1, 4))) { emptyList() }
        press(Key.DirectionDown)
        // Up the season to its pill, then up to the buttons.
        repeat(5) { press(Key.DirectionUp) }
        clickable("Resume S1 E4").assertIsFocused()
        press(Key.DirectionCenter)
        assertLeft()

        backToPage()

        clickable("Resume S1 E4").assertIsFocused()
    }

    @Test
    fun `Back from a person picked in the Cast panel puts the highlight back on Cast, with the panel closed`() {
        showPageInApp(SeriesMainAction.Resume(testEpisode(2, 1))) { emptyList() }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        clickable("Cast").assertIsFocused()
        press(Key.DirectionCenter)
        pressInPanel(Key.DirectionDown)
        pressInPanel(Key.DirectionCenter)
        assertLeft()

        backToPage()

        clickable("Cast").assertIsFocused()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private companion object {
        /** Along the first row of More like this to its third cover. */
        const val RIGHT_PRESSES = 2

        /** Enough rows down that the grid's first rows have scrolled away. */
        const val DOWN_PRESSES = 4
    }
}
