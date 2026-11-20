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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Movie
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.ResumeItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home drawn from the rows the Profile last finished seeing, after a cold start, and the fresh rows
 * replacing them (#595): the Highlight stays on the title the viewer is on, however the row changed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeSnapshotHighlightTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val collateral = resume(9, "Collateral")
    private val insomnia = resume(7, "Insomnia")
    private val heat = Cover(VodTitle.OfMovie(movie(2, "Heat")))
    private val ronin = Cover(VodTitle.OfMovie(movie(3, "Ronin")))
    private val drive = Cover(VodTitle.OfMovie(movie(4, "Drive")))

    // As saved at the end of the last visit.
    private var continueWatching by mutableStateOf(listOf(collateral, insomnia))
    private var recentMovies by mutableStateOf(listOf(heat, ronin))

    private fun setHome() = compose.setTvContent {
        HomeContent(
            continueWatching = continueWatching,
            favouriteShows = emptyList(),
            favouriteMovies = emptyList(),
            favouriteChannels = emptyList(),
            onResume = {},
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = {},
            takeFocus = true,
            recentMovies = recentMovies,
        )
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `the saved Home opens on its first card, and a fresh row with a new title first keeps the Highlight on it`() {
        setHome()
        compose.onNodeWithText("Collateral").assertIsFocused()
        captureScreen("home_saved")

        // Watched something else on another device since: it leads the fresh row.
        continueWatching = listOf(resume(5, "Thief"), collateral, insomnia)
        compose.waitForIdle()

        compose.onNodeWithText("Collateral").assertIsFocused()
    }

    @Test
    fun `a title the viewer moved to stays highlighted when the fresh row reorders around it`() {
        setHome()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        compose.onNodeWithText("Ronin").assertIsFocused()

        recentMovies = listOf(drive, heat, ronin)
        compose.waitForIdle()

        compose.onNodeWithText("Ronin").assertIsFocused()
    }

    @Test
    fun `a highlighted title gone from the fresh row hands the Highlight to the card taking its place`() {
        setHome()
        press(Key.DirectionDown)
        press(Key.DirectionRight)
        compose.onNodeWithText("Ronin").assertIsFocused()

        recentMovies = listOf(heat, drive)
        compose.waitForIdle()

        compose.onNodeWithText("Drive").assertIsFocused()
    }

    private fun resume(id: Long, title: String) =
        ResumeItem(MediaKey.Movie(id), title, posterUrl = null, progress = 0.4f, titleKey = "movie:1:$id")

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
        containerExtension = null,
        streamUrl = "",
    )
}
