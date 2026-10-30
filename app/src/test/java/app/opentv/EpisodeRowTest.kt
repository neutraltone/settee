/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Episode
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.vod.EpisodeRow
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
        EpisodeRow(ep, status, seriesArtUrl = "https://example.com/poster.jpg", onPlay = {})
    }

    @Test
    fun `a Watched episode is labelled Watched`() {
        show(WatchStatus.Watched)

        compose.onNodeWithText("Watched", useUnmergedTree = true).assertExists()
        captureScreen("episode_row_watched")
    }

    @Test
    fun `an In Progress episode is labelled with the minutes left and shows how far through it is`() {
        show(WatchStatus.InProgress(progress = 0.425f, remainingMillis = 23 * 60_000L))

        compose.onNodeWithText("23 min left", useUnmergedTree = true).assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.425f, 0f..1f)), useUnmergedTree = true)
            .assertExists()
        captureScreen("episode_row_in_progress")
    }

    @Test
    fun `an In Progress episode with no known duration is labelled In progress`() {
        show(WatchStatus.InProgress(progress = 0f, remainingMillis = null))

        compose.onNodeWithText("In progress", useUnmergedTree = true).assertExists()
        captureScreen("episode_row_in_progress_unknown_duration")
    }

    @Test
    fun `an Unwatched episode shows no badge and no progress`() {
        show(WatchStatus.Unwatched)

        compose.onNode(hasText("Watched"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("In progress"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("min left", substring = true), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f)), useUnmergedTree = true)
            .assertDoesNotExist()
        captureScreen("episode_row_unwatched")
    }

    @Test
    fun `the badge sits in the top-right corner of a 16 by 9 thumbnail big enough to read from the sofa`() {
        show(WatchStatus.Watched)

        val thumbnail = compose.onNode(hasContentDescription("Thumbnail for episode 3"), useUnmergedTree = true)
            .assertWidthIsEqualTo(160.dp)
            .assertHeightIsEqualTo(90.dp)
            .getUnclippedBoundsInRoot()
        val badge = compose.onNodeWithText("Watched", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badgeBox = compose.onNode(hasAnyChild(hasText("Watched")), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertThat(badgeBox.right).isAtMost(thumbnail.right)
        assertThat(badgeBox.top).isAtLeast(thumbnail.top)
        assertThat(thumbnail.right - badgeBox.right).isLessThan(8.dp)
        assertThat(badgeBox.top - thumbnail.top).isLessThan(8.dp)
        assertThat(badge.left).isAtLeast(thumbnail.left)
    }

    @Test
    fun `the badge doesn't cover the episode number on the series artwork`() {
        show(WatchStatus.InProgress(progress = 0.425f, remainingMillis = 23 * 60_000L), episode.copy(stillUrl = null))

        val number = compose.onNode(hasText("3"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        val badge = compose.onNode(hasAnyChild(hasText("23 min left")), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertThat(badge.bottom).isAtMost(number.top)
        captureScreen("episode_row_series_artwork_in_progress")
    }

    @Test
    fun `the badge can't take focus, so the row is still one stop for the D-pad`() {
        show(WatchStatus.Watched)

        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(1)
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
        show(WatchStatus.InProgress(progress = 0.6f, remainingMillis = 960_000))

        compose.onNode(hasClickAction()).requestFocus()

        compose.onNode(hasClickAction()).assertIsFocused()
        captureScreen("episode_row_focused")
    }
}
