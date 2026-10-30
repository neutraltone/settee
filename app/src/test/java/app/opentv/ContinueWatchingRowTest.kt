/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.repo.MediaKey
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
        key = MediaKey.Episode(24),
        title = "The Show",
        posterUrl = "https://example.com/backdrop.jpg",
        progress = 0.25f,
        episode = SeasonEpisode(season = 2, episode = 4),
    )

    private var played: MediaKey? = null
    private var optionsAskedFor: ResumeItem? = null
    private var optionsAsked = 0

    private fun show(vararg items: ResumeItem) = compose.setTvContent {
        ContinueWatchingRow(
            items.toList(),
            onResume = { played = it },
            onOptions = {
                optionsAskedFor = it
                optionsAsked++
            },
        )
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
    fun `an Up Next card never played shows no progress bar`() {
        show(
            inProgress.copy(
                key = MediaKey.Episode(25),
                progress = null,
                episode = SeasonEpisode(season = 2, episode = 5),
            ),
        )

        compose.onNodeWithText("S2 · E5", useUnmergedTree = true).assertExists()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .assertDoesNotExist()
        captureScreen("continue_watching_up_next")
    }

    @Test
    fun `selecting a card plays its episode, titled as the series page titles it`() {
        show(inProgress)

        compose.onNode(hasClickAction()).requestFocus()
        captureScreen("continue_watching_focused")
        compose.onNode(hasClickAction()).performClick()

        assertThat(played).isEqualTo(MediaKey.Episode(24))
    }

    @Test
    fun `a New Episode card says so on its thumbnail`() {
        show(
            inProgress.copy(
                key = MediaKey.Episode(31),
                progress = null,
                episode = SeasonEpisode(3, 1),
                newEpisode = true,
            ),
        )

        compose.onNodeWithText("New episode", useUnmergedTree = true).assertExists()
        captureScreen("continue_watching_new_episode")
    }

    @Test
    fun `an ordinary Up Next card has no New Episode badge`() {
        show(inProgress.copy(key = MediaKey.Episode(25), progress = null, episode = SeasonEpisode(2, 5)))

        compose.onNodeWithText("New episode", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `Menu on a card asks for its options without playing it`() {
        show(inProgress)

        val card = compose.onNode(hasClickAction())
        card.requestFocus()
        card.performKeyInput { pressKey(Key.Menu) }

        assertThat(optionsAskedFor).isEqualTo(inProgress)
        assertThat(played).isNull()
    }

    @Test
    fun `holding Select on a card asks for its options without playing it`() {
        show(inProgress)

        val card = compose.onNode(hasClickAction())
        card.requestFocus()
        card.performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }

        assertThat(optionsAskedFor).isEqualTo(inProgress)
        assertThat(optionsAsked).isEqualTo(1)
        assertThat(played).isNull()
    }

    @Test
    fun `a quick press of Select still plays the card`() {
        show(inProgress)

        val card = compose.onNode(hasClickAction())
        card.requestFocus()
        card.performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(played).isNotNull()
        assertThat(optionsAskedFor).isNull()
    }

    @Test
    fun `a long press on a card by touch asks for its options`() {
        show(inProgress)

        compose.onNode(hasClickAction()).performTouchInput { longClick() }

        assertThat(optionsAskedFor).isEqualTo(inProgress)
        assertThat(played).isNull()
    }

    @Test
    fun `when the highlighted card is removed the highlight moves to the one taking its place`() {
        val other = inProgress.copy(key = MediaKey.Movie(5), title = "A Film", episode = null)
        var items by mutableStateOf(listOf(inProgress, other))
        compose.setTvContent { ContinueWatchingRow(items, onResume = {}, onOptions = {}) }
        compose.onNode(hasText("The Show") and hasClickAction()).requestFocus()

        items = listOf(other)

        compose.onNode(hasText("A Film") and hasClickAction()).assertIsFocused()
    }
}
