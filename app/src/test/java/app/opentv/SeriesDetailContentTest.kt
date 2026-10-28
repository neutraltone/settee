/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.SeriesMainAction
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SeriesDetailContentTest {

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

    private val episodes = listOf(1, 2).flatMap { season -> (1..5).map { testEpisode(season, it) } }

    private var played: String? = null

    private val mainAction = mutableStateOf<SeriesMainAction?>(null)

    private fun show(initial: SeriesMainAction?) {
        mainAction.value = initial
        compose.setTvContent {
            SeriesDetailContent(
                series = series,
                episodes = episodes,
                statuses = EpisodeStatuses(),
                mainAction = mainAction.value,
                moreLike = emptyList(),
                onPlayEpisode = { key, _, _ -> played = key },
                onToggleFavourite = {},
                onOpenSeries = {},
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

    @Test
    fun `with an Up Next the main button plays it and starts highlighted`() {
        show(SeriesMainAction.Play(testEpisode(2, 5)))

        compose.onNodeWithText("Play S2 E5").assertIsFocused()
        captureScreen("series_detail_up_next")
    }

    @Test
    fun `with an episode In Progress the main button resumes it`() {
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Resume S2 E4").assertIsFocused()
        captureScreen("series_detail_resume")
    }

    @Test
    fun `with nothing to pick up the main button plays the first episode`() {
        show(SeriesMainAction.Play(testEpisode(1, 1)))

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
        captureScreen("series_detail_first_episode")
    }

    @Test
    fun `pressing the main button plays its episode`() {
        show(SeriesMainAction.Resume(testEpisode(2, 4)))

        compose.onNodeWithText("Resume S2 E4").performClick()

        assertThat(played).isEqualTo("ep:24")
    }

    @Test
    fun `before the episodes arrive the highlight waits on the favourite button`() {
        show(initial = null)

        compose.onNodeWithText("Favourite").assertIsFocused()
        compose.onNode(hasText("Play", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `episodes arriving after the viewer has moved on leave the highlight where it is`() {
        show(initial = null)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("S1E1"))
        compose.onNode(hasText("S1E1") and hasClickAction()).requestFocus()

        mainAction.value = SeriesMainAction.Play(testEpisode(1, 1))
        settle()

        compose.onNode(hasText("S1E1") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `episodes arriving while the highlight still waits on the favourite button move it to the main button`() {
        show(initial = null)

        mainAction.value = SeriesMainAction.Play(testEpisode(1, 1))
        settle()

        compose.onNodeWithText("Play S1 E1").assertIsFocused()
    }
}
