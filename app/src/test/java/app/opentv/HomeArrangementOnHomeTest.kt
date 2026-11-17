/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.repo.HomeArrangement
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeChannel
import app.opentv.ui.HomeContent
import app.opentv.ui.LiveNow
import app.opentv.ui.LiveTile
import app.opentv.ui.ResumeItem
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home drawn the way the Profile arranged it on the Home rows page (#212). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeArrangementOnHomeTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val resume = listOf(
        ResumeItem(
            MediaKey.Movie(9),
            "Collateral",
            posterUrl = "https://example.com/9.jpg",
            progress = 0.4f,
            titleKey = "movie:1:9",
        ),
    )

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

    private fun series(id: Long, name: String) = Series(
        id = id,
        sourceId = 1,
        seriesId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
    )

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        streamUrl = "https://example.com/$id.ts",
        categoryId = null,
        logoUrl = "https://example.com/$id.png",
        epgChannelId = null,
        number = null,
    )

    private fun favourite(id: Long, name: String) = channel(id, name).let {
        HomeChannel(LineupChannel(it, listOf(it), favourite = true))
    }

    private val severance = Cover(VodTitle.OfSeries(series(1, "Severance")))
    private val heat = Cover(VodTitle.OfMovie(movie(2, "Heat")))
    private val drive = Cover(VodTitle.OfMovie(movie(11, "Drive")))
    private val shogun = Cover(VodTitle.OfSeries(series(13, "Shogun")))

    private val livePreview = LiveTile(
        LiveNow(LivePreview.Playable(channel(40, "Channel 4")), nowTitle = "Countdown", nextTitle = null),
        player = null,
        error = null,
    )

    private fun setHome(
        arrangement: HomeArrangement,
        continueWatching: List<ResumeItem> = resume,
        favourites: Boolean = true,
        recent: Boolean = false,
        live: LiveTile? = null,
    ) = compose.setTvContent {
        HomeContent(
            continueWatching = continueWatching,
            favouriteShows = if (favourites) listOf(severance) else emptyList(),
            favouriteMovies = if (favourites) listOf(heat) else emptyList(),
            favouriteChannels = if (favourites) listOf(favourite(3, "BBC One")) else emptyList(),
            onResume = {},
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = {},
            takeFocus = true,
            recentShows = if (recent) listOf(shogun) else emptyList(),
            recentMovies = if (recent) listOf(drive) else emptyList(),
            live = live,
            arrangement = arrangement,
        )
    }

    /** Brings [text] fully onto the screen, as HomeContentTest does: six rows overflow a TV. */
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

    private fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun `Home draws its rows in the order the Profile arranged them`() {
        setHome(
            HomeArrangement(
                listOf(
                    HomeRowKind.FAVOURITE_CHANNELS,
                    HomeRowKind.FAVOURITE_MOVIES,
                    HomeRowKind.CONTINUE_WATCHING,
                    HomeRowKind.RECENT_SHOWS,
                    HomeRowKind.RECENT_MOVIES,
                    HomeRowKind.FAVOURITE_SHOWS,
                ),
                hidden = emptySet(),
                livePreview = true,
            ),
            recent = true,
        )

        // The top row in the Profile's order takes the first highlight.
        compose.onNodeWithText("BBC One").assertIsFocused()
        val order = listOf(
            "Favourite channels",
            "Favourite movies",
            "Continue watching",
            "Recently added shows",
            "Recently added movies",
            "Favourite shows",
        )
        order.zipWithNext { above, below ->
            scrollTo(below)
            assertThat(topOf(above)).isLessThan(topOf(below))
        }
    }

    @Test
    fun `a row the Profile hid is left off Home`() {
        setHome(HomeArrangement.USUAL.withShown(HomeRowKind.CONTINUE_WATCHING, false))

        compose.onNodeWithText("Continue watching").assertDoesNotExist()
        compose.onNodeWithText("Collateral").assertDoesNotExist()
        compose.onNodeWithText("Severance").assertIsFocused()
    }

    @Test
    fun `with the Live Preview turned off, Home leaves it out even after live TV`() {
        setHome(HomeArrangement.USUAL.copy(livePreview = false), live = livePreview)

        compose.onNodeWithText("Channel 4").assertDoesNotExist()
        compose.onNodeWithText("Collateral").assertIsFocused()
    }

    @Test
    fun `when the only rows with anything in them are hidden, Home says so instead of the usual hint`() {
        setHome(HomeArrangement.USUAL.withShown(HomeRowKind.CONTINUE_WATCHING, false), favourites = false)

        compose.onNodeWithText("What you watch and favourite will show up here.").assertDoesNotExist()
        compose.onNodeWithText(
            "You've hidden Home's rows. Turn them back on in Settings → Display → Home rows.",
        ).assertExists()
        captureScreen("home_hidden_rows_hint")
    }

    @Test
    fun `when the only thing to show is a Live Preview the Profile turned off, Home says so`() {
        setHome(
            HomeArrangement.USUAL.copy(livePreview = false),
            continueWatching = emptyList(),
            favourites = false,
            live = livePreview,
        )

        compose.onNodeWithText(
            "You've hidden Home's rows. Turn them back on in Settings → Display → Home rows.",
        ).assertExists()
    }

    @Test
    fun `with nothing in any row, hidden or not, Home keeps the usual hint`() {
        setHome(
            HomeArrangement.USUAL.withShown(HomeRowKind.CONTINUE_WATCHING, false),
            continueWatching = emptyList(),
            favourites = false,
        )

        compose.onNodeWithText("What you watch and favourite will show up here.").assertExists()
    }
}
