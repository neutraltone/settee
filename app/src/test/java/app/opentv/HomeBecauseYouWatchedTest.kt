/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Movie
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.BecauseYouWatched
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.ResumeItem
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home's Because you watched row: where it sits, what it's called, and when it's there at all. */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeBecauseYouWatchedTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )

    private fun cover(movie: Movie) = Cover(VodTitle.OfMovie(movie))

    private val resume = listOf(ResumeItem(MediaKey.Movie(9), "Collateral", posterUrl = null, progress = 0.4f))

    private val likeHeat = BecauseYouWatched("Heat", listOf(cover(movie(21, "Ronin")), cover(movie(22, "Thief"))))

    /** The movie a test's Home last opened. */
    private var opened: Movie? = null

    private fun setHome(because: BecauseYouWatched?, arrangement: HomeArrangement = HomeArrangement.USUAL) =
        compose.setTvContent {
            HomeContent(
                continueWatching = resume,
                favouriteShows = emptyList(),
                favouriteMovies = emptyList(),
                favouriteChannels = emptyList(),
                onResume = {},
                onOpenSeries = {},
                onOpenMovie = { opened = it },
                onPlayChannel = {},
                takeFocus = true,
                recentMovies = listOf(cover(movie(11, "Drive"))),
                arrangement = arrangement,
                becauseYouWatched = because,
            )
        }

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    /** Brings [text] fully onto the screen, as HomeContentTest does. */
    private fun scrollTo(text: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction())[0]
        list.performScrollToNode(hasText(text))
        val below =
            compose.onNodeWithText(text).getUnclippedBoundsInRoot().bottom - list.getUnclippedBoundsInRoot().bottom
        if (below > 0.dp) {
            val by = with(compose.density) { below.toPx() }
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, by) }
        }
    }

    @Test
    fun `Because you watched comes right after Continue Watching, named for the title it's based on`() {
        setHome(likeHeat)

        val order = listOf("Continue watching", "Because you watched Heat", "Recently added movies")
        order.zipWithNext { above, below ->
            scrollTo(below)
            assertThat(topOf(above)).isLessThan(topOf(below))
        }
        scrollTo("Ronin")
        compose.onNodeWithText("Ronin").performClick()
        assertThat(opened).isEqualTo(movie(21, "Ronin"))
        captureScreen("home_because_you_watched")
    }

    @Test
    fun `with nothing to base it on, Home has no Because you watched row`() {
        setHome(because = null)

        compose.onNodeWithText("Because you watched", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a Profile that hid Because you watched doesn't get it`() {
        setHome(likeHeat, HomeArrangement.USUAL.withShown(HomeRowKind.BECAUSE_YOU_WATCHED, false))

        compose.onNodeWithText("Because you watched", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Continue watching").assertExists()
    }
}
