/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.Backdrop
import app.opentv.ui.VodViewModel
import app.opentv.ui.vod.BackdropText
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What Movies' and Shows' backdrop shows for a title (#161), and that its text fits (#507). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VodBackdropTest {

    @get:Rule
    val testGraph = TestGraph()

    @get:Rule
    val compose = createComposeRule()

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.track(VodViewModel(app, graph)) }

    private fun movie(backdrop: String?) = Movie(
        id = 1,
        sourceId = 1,
        streamId = "1",
        name = "Heat",
        categoryId = "c",
        posterUrl = "https://example.com/poster.jpg",
        rating = null,
        year = null,
        plot = "A heist.",
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/1",
        backdropUrl = backdrop,
    )

    @Test
    fun `a film with only a cover has no backdrop art, rather than its cover across the screen`() = runBlocking {
        graph.database.movies().upsertAll(listOf(movie(backdrop = null)))

        val shown = viewModel.movieBackdrop(1).toList().last()

        assertThat(shown?.title).isEqualTo("Heat")
        assertThat(shown?.imageUrl).isNull()
    }

    @Test
    fun `a film's backdrop is its wide art`() = runBlocking {
        graph.database.movies().upsertAll(listOf(movie(backdrop = "https://example.com/wide.jpg")))

        assertThat(viewModel.movieBackdrop(1).toList().last()?.imageUrl).isEqualTo("https://example.com/wide.jpg")
    }

    @Test
    fun `a show part way through an episode says so`() = runBlocking {
        graph.database.series().upsertAll(
            listOf(Series(7, 1, "s", "The Show", "c", null, null, null, "A show.")),
        )
        graph.database.episodes().upsertAll(listOf(testEpisode(2, 4)))
        graph.viewerLibrary.recordProgress("ep:24", positionMillis = 600_000, durationMillis = 2_400_000)

        val status = viewModel.seriesBackdrop(7).toList().last()?.status

        assertThat(status).isInstanceOf(CoverStatus.InProgress::class.java)
    }

    @Test
    fun `a film's backdrop carries its year, its first two genres and its running time`() = runBlocking {
        graph.database.movies().upsertAll(
            listOf(
                movie(backdrop = null).copy(year = 1995, genre = "Crime, Drama, Thriller", durationSeconds = 10_200),
            ),
        )

        val shown = viewModel.movieBackdrop(1).toList().last()

        assertThat(shown?.year).isEqualTo(1995)
        assertThat(shown?.genres).containsExactly("Crime", "Drama").inOrder()
        assertThat(shown?.runtimeSeconds).isEqualTo(10_200)
    }

    @Test
    @Config(qualifiers = TV_1080P)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at a larger font size the backdrop's synopsis shows all three of its lines`() {
        val backdrop = Backdrop(
            title = "Heat",
            imageUrl = null,
            status = null,
            synopsis = SYNOPSIS,
            year = 1995,
            genres = listOf("Crime", "Drama"),
            runtimeSeconds = 10_200,
        )
        compose.setTvContent { AtFontScale(1.3f) { BackdropText(backdrop) } }
        compose.mainClock.advanceTimeBy(FADE_MILLIS)

        // All three of the synopsis's lines: squeezed for height, it ends its last whole line early.
        val synopsis = compose.onNodeWithText(SYNOPSIS, useUnmergedTree = true).assertNoLineCut()
        assertThat(synopsis.textLayout().lineCount).isEqualTo(SYNOPSIS_LINES)
        captureScreen("resting_backdrop_font_1_3")
    }

    private companion object {
        const val SYNOPSIS_LINES = 3
        const val FADE_MILLIS = 1_000L
        const val SYNOPSIS = "A group of professional bank robbers start to feel the heat from police when they " +
            "unknowingly leave a clue at their latest heist, and a detective who has given everything to the " +
            "job sets out to catch the one man as driven as he is, across a long summer in Los Angeles."
    }
}
