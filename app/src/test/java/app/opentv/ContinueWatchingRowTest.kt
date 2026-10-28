/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import app.opentv.ui.VodViewModel.ResumeItem
import app.opentv.ui.VodViewModel.SeasonEpisode
import app.opentv.ui.vod.ContinueWatchingRow
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
class ContinueWatchingRowTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val inProgress = ResumeItem(
        mediaKey = "ep:24",
        title = "The Show",
        playerTitle = "S2E4 · The one with the card",
        posterUrl = "https://example.com/backdrop.jpg",
        streamUrl = "https://example.com/2-4.mkv",
        progress = 0.25f,
        episode = SeasonEpisode(season = 2, episode = 4),
    )

    private var played: Triple<String, String, String>? = null

    private fun show(vararg items: ResumeItem) = compose.setTvContent {
        ContinueWatchingRow(items.toList()) { key, url, title -> played = Triple(key, url, title) }
    }

    @Test
    fun `a series card shows the series name, which episode and how far through it is`() {
        show(inProgress)

        compose.onNodeWithText("The Show", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("S2 · E4", useUnmergedTree = true).assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.25f, 0f..1f)), useUnmergedTree = true)
            .assertExists()
        captureScreen("continue_watching_series")
    }

    @Test
    fun `an Up Next card has an empty progress bar`() {
        show(inProgress.copy(mediaKey = "ep:25", progress = 0f, episode = SeasonEpisode(season = 2, episode = 5)))

        compose.onNodeWithText("S2 · E5", useUnmergedTree = true).assertExists()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f)), useUnmergedTree = true)
            .assertExists()
        captureScreen("continue_watching_up_next")
    }

    @Test
    fun `selecting a card plays its episode, titled as the series page titles it`() {
        show(inProgress)

        compose.onNode(hasClickAction()).requestFocus()
        captureScreen("continue_watching_focused")
        compose.onNode(hasClickAction()).performClick()

        assertThat(played).isEqualTo(Triple("ep:24", "https://example.com/2-4.mkv", "S2E4 · The one with the card"))
    }
}
