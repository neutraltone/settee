/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Episode
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.vod.EpisodeRow
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
class EpisodeRowTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val episode = Episode(
        id = 7,
        sourceId = 1,
        seriesId = "s",
        episodeId = "e",
        season = 1,
        episodeNumber = 3,
        title = "The one with the badge",
        plot = null,
        durationSeconds = 2_400,
        stillUrl = "https://example.com/still.jpg",
        streamUrl = "https://example.com/e.mkv",
    )

    private fun show(status: WatchStatus, ep: Episode = episode) = compose.setTvContent {
        EpisodeRow(ep, status, seriesArtUrl = "https://example.com/poster.jpg", onPlay = { _, _, _ -> })
    }

    @Test
    fun `a Watched episode shows the watched badge`() {
        show(WatchStatus.Watched)

        compose.onNodeWithContentDescription("Watched", useUnmergedTree = true).assertExists()
        captureScreen("episode_row_watched")
    }

    @Test
    fun `an In Progress episode shows the in-progress badge and how far through it is`() {
        show(WatchStatus.InProgress(progress = 0.25f))

        compose.onNodeWithContentDescription("In progress", useUnmergedTree = true).assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)), useUnmergedTree = true)
            .assertExists()
        captureScreen("episode_row_in_progress")
    }

    @Test
    fun `an Unwatched episode shows no badge and no progress`() {
        show(WatchStatus.Unwatched)

        compose.onNode(hasContentDescription("Watched"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasContentDescription("In progress"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f)), useUnmergedTree = true)
            .assertDoesNotExist()
        captureScreen("episode_row_unwatched")
    }

    @Test
    fun `an episode with its own thumbnail shows it without the episode number on top`() {
        show(WatchStatus.Unwatched)

        compose.onNode(hasContentDescription("Thumbnail for episode 3"), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("3"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `an episode with no thumbnail shows the series artwork with its episode number on top`() {
        show(WatchStatus.Unwatched, episode.copy(stillUrl = null))

        compose.onNode(hasContentDescription("Thumbnail for episode 3"), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("3"), useUnmergedTree = true).assertExists()
        captureScreen("episode_row_series_artwork")
    }

    @Test
    fun `a focused episode is highlighted`() {
        show(WatchStatus.InProgress(progress = 0.6f))

        compose.onNode(hasClickAction()).requestFocus()

        compose.onNode(hasClickAction()).assertIsFocused()
        captureScreen("episode_row_focused")
    }
}
