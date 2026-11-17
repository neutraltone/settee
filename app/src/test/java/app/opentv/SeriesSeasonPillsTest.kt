/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.TitleCover
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where a show's episode list starts when the season pills change its season (#428). These use seasons
 * long enough that a season opened part way down would hide Episode 1.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesSeasonPillsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val episodes = (1..2).flatMap { s -> (1..12).map { testEpisode(s, it) } }

    private val likeThis = listOf("Harbour", "Lighthouse").mapIndexed { i, name ->
        TitleCover(id = 100L + i, name = name, title = name, posterUrl = "https://example.com/$i.jpg")
    }

    private fun show(initial: SeriesMainAction, suggestions: List<TitleCover> = emptyList()) {
        compose.setTvContent {
            SeriesDetailContent(
                series = testSeries(),
                episodes = episodes,
                statuses = EpisodeStatuses(),
                mainAction = initial,
                status = null,
                favourite = false,
                onPlayEpisode = {},
                onSetFavourite = {},
                onOpenPerson = {},
                suggestions = suggestions,
            )
        }
        compose.settleShowPage()
    }

    private fun press(key: Key) = compose.pressOnShowPage(key)

    private fun episodeRow(season: Int, episode: Int) = compose.episodeRow(season, episode)

    private fun pill(label: String) = compose.showPagePill(label)

    private fun back() {
        Espresso.pressBack()
        compose.settleShowPage()
    }

    private fun episodeRowShown(season: Int, episode: Int) = runCatching {
        episodeRow(season, episode).assertIsDisplayed()
    }.isSuccess

    /** Opens the page resuming S2 E9, so its list starts with E9 at the top, and goes up to Season 2's pill. */
    private fun onSeason2PillFromItsNinthEpisode(suggestions: List<TitleCover> = emptyList()) {
        show(SeriesMainAction.Resume(testEpisode(2, 9)), suggestions)
        press(Key.DirectionDown)
        episodeRow(2, 9).assertIsFocused()
        back()
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
    fun `Back from an episode to its season's pill leaves the list where it was`() {
        onSeason2PillFromItsNinthEpisode()

        assertWithMessage("S2 E9 still on screen").that(episodeRowShown(2, 9)).isTrue()
        assertWithMessage("S2 E1 scrolled back into view").that(episodeRowShown(2, 1)).isFalse()
    }

    @Test
    fun `back on a season from More like this, the pills list it from its first episode`() {
        onSeason2PillFromItsNinthEpisode(suggestions = likeThis)

        press(Key.DirectionRight)
        pill("More like this").assertIsFocused()
        press(Key.DirectionLeft)
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
