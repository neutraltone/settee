/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.SeriesMainAction
import app.opentv.ui.vod.SeriesDetailContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where a show's episode list starts when the season pills change its season (#428). The seasons share
 * one list, so these use seasons long enough that a season opened part way down would hide Episode 1.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SeriesSeasonPillsTest {

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
    )

    private val episodes = (1..2).flatMap { s -> (1..12).map { testEpisode(s, it) } }

    private fun show(initial: SeriesMainAction) {
        compose.setTvContent {
            SeriesDetailContent(
                series = series,
                episodes = episodes,
                statuses = EpisodeStatuses(),
                mainAction = initial,
                status = null,
                favourite = false,
                onPlayEpisode = {},
                onSetFavourite = {},
                onOpenPerson = {},
            )
        }
        settle()
    }

    // The page places its highlight a moment after it appears, once its rows are laid out.
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    /** Presses [key] on whatever has the Highlight, and lets the page settle. */
    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    private fun episodeRow(season: Int, episode: Int) = compose.onNode(
        hasText("S$season E$episode") and hasClickAction(),
    )

    private fun pill(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** Opens the page resuming S2 E9, so its list starts with E9 at the top, and goes up to Season 2's pill. */
    private fun onSeason2PillFromItsNinthEpisode() {
        show(SeriesMainAction.Resume(testEpisode(2, 9)))
        press(Key.DirectionDown)
        episodeRow(2, 9).assertIsFocused()
        Espresso.pressBack()
        settle()
        pill("Season 2").assertIsFocused()
    }

    @Test
    fun `a season picked on the pills lists from its first episode, so Down from its pill reaches Episode 1`() {
        onSeason2PillFromItsNinthEpisode()

        press(Key.DirectionLeft)
        pill("Season 1").assertIsFocused()
        press(Key.DirectionDown)

        episodeRow(1, 1).assertIsFocused()
    }

    @Test
    fun `back on the season the page opened on, the pills list it from its first episode too`() {
        onSeason2PillFromItsNinthEpisode()

        press(Key.DirectionLeft)
        press(Key.DirectionRight)
        pill("Season 2").assertIsFocused()
        press(Key.DirectionDown)

        episodeRow(2, 1).assertIsFocused()
    }

    @Test
    fun `Down from a long season's last episode still reaches the next season's first`() {
        show(SeriesMainAction.Resume(testEpisode(1, 12)))
        press(Key.DirectionDown)
        episodeRow(1, 12).assertIsFocused()

        press(Key.DirectionDown)

        episodeRow(2, 1).assertIsFocused()
        pill("Season 2").assertIsSelected()
    }
}
