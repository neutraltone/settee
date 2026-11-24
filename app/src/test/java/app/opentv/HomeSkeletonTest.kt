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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.core.ViewerError
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.LivePreview
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.LiveNow
import app.opentv.ui.LiveTile
import app.opentv.ui.ProviderBanner
import app.opentv.ui.ProviderFailure
import app.opentv.ui.ResumeItem
import app.opentv.ui.design.SKELETON_CARD
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home while its rows are still on their way (#581): their places held by skeletons from the first
 * frame, the Highlight already on the opening Continue Watching card, and each row's cards landing
 * over its place without moving it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeSkeletonTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private var continueWatching by mutableStateOf(emptyList<ResumeItem>())
    private var recentMovies by mutableStateOf(emptyList<Cover>())
    private var loading by mutableStateOf(setOf(HomeRowKind.CONTINUE_WATCHING, HomeRowKind.RECENT_MOVIES))
    private var live by mutableStateOf<LiveTile?>(null)
    private var liveLoading by mutableStateOf(false)
    private var favouriteMovies by mutableStateOf(emptyList<Cover>())
    private var banner by mutableStateOf<ProviderBanner?>(null)
    private val resumed = mutableListOf<MediaKey>()
    private var firstRowsDrawn = 0

    private fun setHome() = compose.setTvContent {
        HomeContent(
            continueWatching = continueWatching,
            favouriteShows = emptyList(),
            favouriteMovies = favouriteMovies,
            favouriteChannels = emptyList(),
            onResume = { resumed += it },
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = {},
            takeFocus = true,
            recentMovies = recentMovies,
            live = live,
            loading = loading,
            liveLoading = liveLoading,
            banner = banner,
            onFirstRowDrawn = { firstRowsDrawn++ },
        )
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun openingSkeleton() = compose.onAllNodesWithTag(SKELETON_CARD).onFirst()

    @Test
    fun `the opening Continue Watching skeleton card has the Highlight, and OK and Right do nothing`() {
        setHome()

        openingSkeleton().assertIsFocused()
        compose.onNodeWithText("Continue watching").assertExists()
        compose.onNodeWithText("Recently added movies").assertExists()
        captureScreen("home_skeleton")

        press(Key.DirectionCenter)
        press(Key.DirectionRight)

        assertThat(resumed).isEmpty()
        openingSkeleton().assertIsFocused()
    }

    @Test
    fun `a cold start ends when Home's first row has drawn with its cards, not while only skeletons show`() {
        setHome()
        assertThat(firstRowsDrawn).isEqualTo(0)

        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = setOf(HomeRowKind.CONTINUE_WATCHING)
        compose.waitForIdle()

        assertThat(firstRowsDrawn).isEqualTo(1)
    }

    @Test
    fun `Continue Watching's cards land with the Highlight on the first, and the row below stays put`() {
        setHome()
        val below = compose.onNodeWithText("Recently added movies").getUnclippedBoundsInRoot().top

        continueWatching = listOf(
            ResumeItem(MediaKey.Movie(9), "Collateral", posterUrl = null, progress = 0.4f, titleKey = "movie:1:9"),
            ResumeItem(MediaKey.Movie(7), "Insomnia", posterUrl = null, progress = 0.2f, titleKey = "movie:1:7"),
        )
        loading = setOf(HomeRowKind.RECENT_MOVIES)
        compose.waitForIdle()

        compose.onNodeWithText("Collateral").assertIsFocused()
        assertThat(compose.onNodeWithText("Recently added movies").getUnclippedBoundsInRoot().top).isEqualTo(below)
    }

    @Test
    fun `a row below the Highlight lands without taking it`() {
        setHome()

        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = setOf(HomeRowKind.CONTINUE_WATCHING)
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertExists()
        openingSkeleton().assertIsFocused()
    }

    @Test
    fun `the Live Preview's place leads Home with the Highlight, and its tile takes it over`() {
        liveLoading = true
        setHome()

        openingSkeleton().assertIsFocused()
        captureScreen("home_skeleton_live_preview")

        live = LiveTile(LiveNow(LivePreview.Playable(channel4), nowTitle = "Countdown", nextTitle = null), null, null)
        liveLoading = false
        compose.waitForIdle()

        compose.onNodeWithText("Channel 4").assertIsFocused()
    }

    @Test
    fun `Continue Watching coming back empty hands the Highlight to the row that leads Home now`() {
        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = setOf(HomeRowKind.CONTINUE_WATCHING)
        setHome()
        openingSkeleton().assertIsFocused()

        loading = emptySet()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsFocused()
    }

    @Test
    fun `Continue Watching coming back empty before the row below lands, that row takes the Highlight`() {
        setHome()
        openingSkeleton().assertIsFocused()

        loading = setOf(HomeRowKind.RECENT_MOVIES)
        compose.waitForIdle()
        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = emptySet()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsFocused()
    }

    @Test
    fun `a row of covers leading Home takes the Highlight when its cards land`() {
        loading = setOf(HomeRowKind.RECENT_MOVIES)
        setHome()
        openingSkeleton().assertIsFocused()
        captureScreen("home_skeleton_covers_lead")

        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = emptySet()
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsFocused()
    }

    @Test
    fun `Continue Watching coming back empty leaves the Highlight where the viewer moved it`() {
        banner = ProviderBanner(listOf(ProviderFailure(1, "Acme TV", ViewerError("Check your login", "LoginRejected"))))
        loading = setOf(HomeRowKind.CONTINUE_WATCHING, HomeRowKind.RECENT_MOVIES)
        setHome()
        openingSkeleton().assertIsFocused()

        press(Key.DirectionUp)
        compose.onNodeWithText("Open Providers").assertIsFocused()
        loading = setOf(HomeRowKind.RECENT_MOVIES)
        compose.waitForIdle()
        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        loading = emptySet()
        compose.waitForIdle()

        compose.onNodeWithText("Open Providers").assertIsFocused()
    }

    @Test
    fun `a row landing above the place with the Highlight takes it, as the place no longer leads Home`() {
        loading = setOf(HomeRowKind.FAVOURITE_MOVIES)
        setHome()
        openingSkeleton().assertIsFocused()

        recentMovies = listOf(Cover(VodTitle.OfMovie(movie(2, "Heat"))))
        compose.waitForIdle()

        compose.onNodeWithText("Heat").assertIsFocused()
    }

    private val channel4 = Channel(
        id = 40,
        sourceId = 1,
        streamId = "40",
        name = "Channel 4",
        streamUrl = "https://example.com/40.ts",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
    )

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )
}
