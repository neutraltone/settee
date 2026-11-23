/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.options.ItemOptionsHost
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The series page with its real options panel, view model and database, where
 * [SeriesDetailContentTest] stands the panel in: what each panel offers, and what choosing does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatchUpPanelTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = testGraph.graph
    private val library get() = graph.viewerLibrary

    private val series = Series(
        id = 1,
        sourceId = 1,
        seriesId = "s",
        name = "The Long Way Round",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2024,
        plot = null,
        backdropUrl = null,
    )

    private val episodes = listOf(1, 2).flatMap { season -> (1..3).map { testEpisode(season, it) } }

    @Before
    fun setUp() = runBlocking {
        graph.database.episodes().upsertAll(episodes)
    }

    private fun show() {
        compose.setTvContent {
            ItemOptionsHost {
                SeriesDetailContent(
                    series = series,
                    episodes = episodes,
                    statuses = EpisodeStatuses(),
                    mainAction = null,
                    status = null,
                    favourite = false,
                    onPlayEpisode = {},
                    onSetFavourite = {},
                    onOpenPerson = {},
                )
            }
        }
        // The page places its highlight a moment after it appears, which would take it off a row
        // the test had already moved to.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    /** Highlights the row or pill reading [text]; a season's episodes are listed once its pill has the Highlight. */
    private fun row(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasClickAction()).requestFocus()

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.onAllNodes(hasText(text)).onFirst().assertExists()
        return compose.onNodeWithText(text)
    }

    private fun statusOf(ep: Episode) = runBlocking { library.episodeStatuses().first()[ep.id] }

    /** Settles, then checks [ep] reached [status]. */
    private fun assertBecomes(ep: Episode, status: WatchStatus) {
        compose.waitForIdle()
        assertThat(statusOf(ep)).isEqualTo(status)
    }

    @Test
    fun `Mark watched up to here in an episode's panel marks it and the ones before it`() {
        show()

        row("Season 2")
        row("S2 E1").performKeyInput { pressKey(Key.Menu) }
        waitFor("Mark watched up to here").performClick()

        assertBecomes(testEpisode(2, 1), WatchStatus.Watched)
        assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Watched)
        assertThat(statusOf(testEpisode(2, 2))).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `an episode with nothing unwatched before it doesn't offer Mark watched up to here`() {
        show()

        row("S1 E1").performKeyInput { pressKey(Key.Menu) }

        waitFor("Mark watched")
        compose.onNodeWithText("Mark watched up to here").assertDoesNotExist()
    }

    @Test
    fun `a season pill's panel marks the season Watched, then offers only to mark it Unwatched`() {
        show()

        row("Season 1").performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Mark season watched")
        compose.onNodeWithText("Mark season unwatched").assertDoesNotExist()
        compose.onNodeWithText("Mark season watched").performClick()

        assertBecomes(testEpisode(1, 3), WatchStatus.Watched)
        assertThat(statusOf(testEpisode(2, 1))).isEqualTo(WatchStatus.Unwatched)

        row("Season 1").performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Mark season unwatched")
        compose.onNodeWithText("Mark season watched").assertDoesNotExist()
        compose.onNodeWithText("Mark season unwatched").performClick()

        assertBecomes(testEpisode(1, 3), WatchStatus.Unwatched)
    }
}
