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
import app.opentv.data.model.Series
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
 * A card leaving one of Home's rows while it has the highlight (Remove from Continue Watching, Mark
 * watched, Remove from Favourites) hands the highlight to the card taking its place, not the row's
 * first (#430).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeCardRemovedTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun resumeItem(id: Long, title: String) = ResumeItem(
        MediaKey.Movie(id),
        title,
        posterUrl = "https://example.com/$id.jpg",
        progress = 0.4f,
        titleKey = "movie:1:$id",
    )

    private fun show(id: Long, name: String) = Cover(
        VodTitle.OfSeries(
            Series(
                id = id,
                sourceId = 1,
                seriesId = "$id",
                name = name,
                categoryId = null,
                posterUrl = "https://example.com/$id.jpg",
                rating = null,
                year = null,
                plot = null,
            ),
        ),
    )

    private val fourToContinue = listOf(
        resumeItem(9, "Collateral"),
        resumeItem(7, "Insomnia"),
        resumeItem(8, "Memento"),
        resumeItem(10, "Thief"),
    )

    private val threeShows = listOf(show(1, "Severance"), show(5, "Andor"), show(6, "Slow Horses"))

    private var continueWatching by mutableStateOf(emptyList<ResumeItem>())
    private var favouriteShows by mutableStateOf(emptyList<Cover>())

    private fun setHome() = compose.setTvContent {
        HomeContent(
            continueWatching = continueWatching,
            favouriteShows = favouriteShows,
            favouriteMovies = emptyList(),
            favouriteChannels = emptyList(),
            onResume = {},
            onOpenSeries = {},
            onOpenMovie = {},
            onPlayChannel = {},
            takeFocus = true,
        )
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `removing the highlighted Continue Watching card highlights the one taking its place`() {
        continueWatching = fourToContinue
        favouriteShows = threeShows
        setHome()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNodeWithText("Memento").assertIsFocused()

        continueWatching = fourToContinue.filterNot { it.title == "Memento" }

        compose.onNodeWithText("Thief").assertIsFocused()
        captureScreen("home_card_removed")
    }

    @Test
    fun `removing a highlighted Favourite show highlights the one taking its place`() {
        favouriteShows = threeShows
        setHome()
        press(Key.DirectionRight)
        compose.onNodeWithText("Andor").assertIsFocused()

        favouriteShows = threeShows.filterNot { it.title.name == "Andor" }

        compose.onNodeWithText("Slow Horses").assertIsFocused()
    }

    @Test
    fun `removing the last card of a row highlights the row's new last card`() {
        favouriteShows = threeShows
        setHome()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNodeWithText("Slow Horses").assertIsFocused()

        favouriteShows = threeShows.dropLast(1)

        compose.onNodeWithText("Andor").assertIsFocused()
    }
}
