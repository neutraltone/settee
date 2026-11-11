/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Episode
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.design.SetteeColors
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

    private fun show(status: WatchStatus, ep: Episode = episode): MutableState<WatchStatus> {
        val state = mutableStateOf(status)
        compose.setTvContent {
            Column {
                EpisodeRow(ep, state.value, seriesArt = listOf(BACKDROP, POSTER), onPlay = {})
                // Somewhere else for the Highlight to go.
                Box(Modifier.size(10.dp).testTag("elsewhere").focusable())
            }
        }
        compose.waitUntil { artwork.hasLoaded }
        return state
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
    }

    @Test
    fun `an Unwatched episode shows no status and no progress`() {
        show(WatchStatus.Unwatched)

        compose.onNode(hasText("Watched"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("In progress"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("min left", substring = true), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f)), useUnmergedTree = true)
            .assertDoesNotExist()
        captureScreen("episode_row_unwatched")
    }

    @Test
    fun `the row is one stop for the D-pad`() {
        show(WatchStatus.Watched)

        compose.onAllNodes(isFocusable() and hasText("S1 E3"), useUnmergedTree = false).assertCountEquals(1)
        compose.onAllNodes(isFocusable(), useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `an episode with no still shows the show's artwork instead`() {
        show(WatchStatus.Unwatched, episode.copy(stillUrl = null))

        compose.onNode(hasContentDescription("Thumbnail for episode 3"), useUnmergedTree = true).assertExists()
        captureScreen("episode_row_series_artwork")
    }

    @Test
    fun `with no still and a dead backdrop, the thumbnail falls back to the poster rather than going blank`() {
        artwork.failingUrls += BACKDROP
        show(WatchStatus.Unwatched, episode.copy(stillUrl = null))

        val image = compose.onNode(hasContentDescription("Thumbnail for episode 3"), useUnmergedTree = true)
            .captureToImage()
            .toPixelMap()
        assertThat(image[image.width / 2, image.height / 2]).isNotEqualTo(SetteeColors.Raised)
    }

    @Test
    fun `a focused episode is highlighted, with its text dark on the coral`() {
        show(WatchStatus.InProgress(progress = 0.6f, remainingMillis = 960_000))

        compose.onNode(hasClickAction()).requestFocus()

        compose.onNode(hasClickAction()).assertIsFocused()
        assertThat(colourOf("The one with the badge")).isEqualTo(SetteeColors.OnAccent)
        assertThat(colourOf("S1 E3")).isEqualTo(SetteeColors.OnAccent)
        captureScreen("episode_row_focused")
    }

    @Test
    fun `a Watched episode's title steps back to quiet text, by colour rather than fading`() {
        show(WatchStatus.Watched)

        assertThat(colourOf("The one with the badge")).isEqualTo(SetteeColors.QuietText)
    }

    @Test
    fun `an Unwatched episode's title is in full text colour`() {
        show(WatchStatus.Unwatched)

        assertThat(colourOf("The one with the badge")).isEqualTo(SetteeColors.Text)
    }

    @Test
    fun `a Watched episode steps back again when the Highlight moves on`() {
        show(WatchStatus.Watched)
        compose.onNode(hasClickAction()).requestFocus()

        compose.onNodeWithTag("elsewhere").requestFocus()

        assertThat(colourOf("The one with the badge")).isEqualTo(SetteeColors.QuietText)
    }

    @Test
    fun `marking a Watched episode unwatched brings its title back to full colour`() {
        val status = show(WatchStatus.Watched)

        status.value = WatchStatus.Unwatched
        compose.waitForIdle()

        assertThat(colourOf("The one with the badge")).isEqualTo(SetteeColors.Text)
    }

    private fun colourOf(text: String): Color {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.color
    }

    private companion object {
        const val BACKDROP = "https://example.com/backdrop.jpg"
        const val POSTER = "https://example.com/poster.jpg"
    }
}
