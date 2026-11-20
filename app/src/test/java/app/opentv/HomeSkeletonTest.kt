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
import app.opentv.data.model.Movie
import app.opentv.data.repo.HomeRowKind
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
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
    private val resumed = mutableListOf<MediaKey>()

    private fun setHome() = compose.setTvContent {
        HomeContent(
            continueWatching = continueWatching,
            favouriteShows = emptyList(),
            favouriteMovies = emptyList(),
            favouriteChannels = emptyList(),
            onResume = { resumed += it },
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = {},
            takeFocus = true,
            recentMovies = recentMovies,
            loading = loading,
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
