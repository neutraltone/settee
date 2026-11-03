/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.options.ItemOptionsHost
import app.opentv.ui.vod.SeriesDetailContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
@OptIn(ExperimentalTestApi::class)
class CatchUpPanelTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
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

    // The graph is process-wide, so its database outlives each test.
    @Before
    fun startEmpty() = runBlocking {
        library.profileRemoval.forget(graph.settings.activeProfileId.value)
        graph.database.episodes().upsertAll(episodes)
    }

    @After
    fun leaveNothingBehind() = runBlocking {
        library.profileRemoval.forget(graph.settings.activeProfileId.value)
        graph.database.episodes().deleteForSource(series.sourceId)
    }

    private fun show() {
        compose.setTvContent {
            ItemOptionsHost {
                SeriesDetailContent(
                    series = series,
                    episodes = episodes,
                    statuses = EpisodeStatuses(),
                    mainAction = null,
                    favourite = false,
                    moreLike = emptyList(),
                    onPlayEpisode = {},
                    onSetFavourite = {},
                    onOpenSeries = {},
                    onOpenPerson = {},
                )
            }
        }
        // The page places its highlight a moment after it appears, which would take it off a row
        // the test had already moved to.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun row(text: String): SemanticsNodeInteraction {
        val row = hasText(text) and hasClickAction()
        compose.onNode(hasScrollAction()).performScrollToNode(row)
        return compose.onNode(row).requestFocus()
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntilAtLeastOneExists(hasText(text), TIMEOUT_MILLIS)
        return compose.onNodeWithText(text)
    }

    private fun statusOf(ep: Episode) = runBlocking { library.episodeStatuses().first()[ep.id] }

    /** Waits for [ep] to reach [status]: waitUntil alone leaves the view model's write stuck on the main looper. */
    private fun waitUntil(ep: Episode, status: WatchStatus) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (statusOf(ep) != status) {
            check(System.currentTimeMillis() < deadline) { "$ep never became $status" }
            compose.waitForIdle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `Mark watched up to here in an episode's panel marks it and the ones before it`() {
        show()

        row("S2E1").performKeyInput { pressKey(Key.Menu) }
        waitFor("Mark watched up to here").performClick()

        waitUntil(testEpisode(2, 1), WatchStatus.Watched)
        assertThat(statusOf(testEpisode(1, 1))).isEqualTo(WatchStatus.Watched)
        assertThat(statusOf(testEpisode(2, 2))).isEqualTo(WatchStatus.Unwatched)
    }

    @Test
    fun `an episode with nothing unwatched before it doesn't offer Mark watched up to here`() {
        show()

        row("S1E1").performKeyInput { pressKey(Key.Menu) }

        waitFor("Mark watched")
        compose.onNodeWithText("Mark watched up to here").assertDoesNotExist()
    }

    @Test
    fun `a season header's panel marks the season Watched, then offers only to mark it Unwatched`() {
        show()

        row("Season 1").performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Mark season watched")
        compose.onNodeWithText("Mark season unwatched").assertDoesNotExist()
        compose.onNodeWithText("Mark season watched").performClick()

        waitUntil(testEpisode(1, 3), WatchStatus.Watched)
        assertThat(statusOf(testEpisode(2, 1))).isEqualTo(WatchStatus.Unwatched)

        row("Season 1").performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Mark season unwatched")
        compose.onNodeWithText("Mark season watched").assertDoesNotExist()
        compose.onNodeWithText("Mark season unwatched").performClick()

        waitUntil(testEpisode(1, 3), WatchStatus.Unwatched)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
