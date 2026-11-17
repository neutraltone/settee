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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.TitleCover
import app.opentv.ui.vod.SeriesDetailContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Back to a show's page from an episode played, a person or a show opened on it (#429). Apart from
 * [SeriesDetailContentTest], and with its own screenshots of the page as Back finds it, because only a
 * page in a NavHost is disposed and built again, which is what loses where the Highlight was.
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

    private val page = PageBack(compose)

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

    /**
     * The page, whose episodes, people and suggestions all lead off it. Its More like this is read each
     * time it's built, after it shows, as [app.opentv.ui.vod.SeriesDetailScreen] reads it, from
     * [suggestions].
     */
    private fun showPage(mainAction: SeriesMainAction, suggestions: () -> List<TitleCover>) = page.show { leave ->
        var read by remember { mutableStateOf<List<TitleCover>?>(null) }
        LaunchedEffect(Unit) { read = suggestions() }
        SeriesDetailContent(
            series = series,
            episodes = episodes,
            statuses = EpisodeStatuses(),
            mainAction = mainAction,
            status = null,
            favourite = false,
            onPlayEpisode = { leave() },
            onSetFavourite = {},
            onOpenPerson = { leave() },
            suggestions = read.orEmpty(),
            onOpenSeries = { leave() },
            suggestionsKnown = read != null,
        )
    }

    private fun episodeRow(season: Int, episode: Int) = page.clickable("S$season E$episode")

    /** From the main button onto More like this's pill, by way of the episodes. */
    private fun goToLikeThisPill() {
        page.press(Key.DirectionDown)
        page.press(Key.DirectionUp)
        page.press(Key.DirectionRight)
        page.clickable("More like this").assertIsFocused()
    }

    @Test
    fun `Back from a show opened from More like this highlights its cover, with More like this selected`() {
        showPage(SeriesMainAction.Resume(testEpisode(2, 1))) { likeThis }
        goToLikeThisPill()
        page.press(Key.DirectionDown)
        repeat(RIGHT_PRESSES) { page.press(Key.DirectionRight) }
        page.clickable("Show 3").assertIsFocused()
        page.openHighlighted()

        page.back()

        page.clickable("Show 3").assertIsFocused().assertIsDisplayed()
        page.clickable("More like this").assertIsSelected()
        page.clickable("Season 2").assertIsNotSelected()
        captureScreen("series_detail_back_from_like_this")
    }

    @Test
    fun `Back from a show far down More like this scrolls back to its cover`() {
        showPage(SeriesMainAction.Resume(testEpisode(2, 1))) { manyLikeThis }
        goToLikeThisPill()
        page.press(Key.DirectionDown)
        repeat(DOWN_PRESSES) { page.press(Key.DirectionDown) }
        val name = page.highlightedText()
        page.openHighlighted()

        page.back()

        page.clickable(name).assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun `after Back to a cover far down, More like this shown again starts at its first cover`() {
        showPage(SeriesMainAction.Resume(testEpisode(2, 1))) { manyLikeThis }
        goToLikeThisPill()
        page.press(Key.DirectionDown)
        repeat(DOWN_PRESSES) { page.press(Key.DirectionDown) }
        page.openHighlighted()
        page.back()

        // Back from the grid to its pill, along to Season 2 and back onto More like this.
        Espresso.pressBack()
        page.settle()
        page.clickable("More like this").assertIsFocused()
        page.press(Key.DirectionLeft)
        page.press(Key.DirectionRight)
        page.press(Key.DirectionDown)

        page.clickable("Show 1").assertIsFocused()
    }

    @Test
    fun `Back to the page when the show opened has left More like this puts the highlight on the main button`() {
        var suggestions = likeThis
        showPage(SeriesMainAction.Resume(testEpisode(2, 1))) { suggestions }
        goToLikeThisPill()
        page.press(Key.DirectionDown)
        page.openHighlighted()
        suggestions = likeThis.drop(1)

        page.back()

        page.clickable("Resume S2 E1").assertIsFocused()
    }

    @Test
    fun `Back from an episode played puts the highlight back on it, in its season`() {
        showPage(SeriesMainAction.Resume(testEpisode(1, 4))) { emptyList() }
        page.press(Key.DirectionDown)
        episodeRow(1, 4).assertIsFocused()
        repeat(3) { page.press(Key.DirectionDown) }
        episodeRow(2, 2).assertIsFocused()
        page.openHighlighted()

        page.back()

        episodeRow(2, 2).assertIsFocused().assertIsDisplayed()
        page.clickable("Season 2").assertIsSelected()
        page.clickable("Season 1").assertIsNotSelected()
        captureScreen("series_detail_back_from_episode")
    }

    @Test
    fun `Back from playing the main button's episode puts the highlight back on the main button`() {
        showPage(SeriesMainAction.Resume(testEpisode(1, 4))) { emptyList() }
        page.press(Key.DirectionDown)
        // Up the season to its pill, then up to the buttons.
        repeat(5) { page.press(Key.DirectionUp) }
        page.clickable("Resume S1 E4").assertIsFocused()
        page.openHighlighted()

        page.back()

        page.clickable("Resume S1 E4").assertIsFocused()
    }

    @Test
    fun `Back from a person picked in the Cast panel puts the highlight back on Cast, with the panel closed`() {
        showPage(SeriesMainAction.Resume(testEpisode(2, 1))) { emptyList() }
        page.press(Key.DirectionRight)
        page.press(Key.DirectionRight)
        page.clickable("Cast").assertIsFocused()
        page.press(Key.DirectionCenter)
        page.pressInPanel(Key.DirectionDown)
        page.pressInPanel(Key.DirectionCenter)
        page.assertLeft()

        page.back()

        page.clickable("Cast").assertIsFocused()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private companion object {
        /** Along the first row of More like this to its third cover. */
        const val RIGHT_PRESSES = 2

        /** Enough rows down that the grid's first rows have scrolled away. */
        const val DOWN_PRESSES = 4
    }
}
