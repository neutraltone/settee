/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.opentv.data.model.Movie
import app.opentv.data.repo.AddonTitle
import app.opentv.ui.channels.SearchContent
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Search's Not from your provider section (#671): films TMDB knows that the Provider doesn't carry,
 * which only come when an add-on is set up, so an empty list stands for none set up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchNotFromProviderTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val query = TextFieldState()
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val openedFilms = mutableListOf<AddonTitle>()

    private fun show(movies: List<Movie> = emptyList(), addonFilms: List<AddonTitle> = emptyList()) {
        compose.setTvContent {
            view = LocalView.current
            SearchContent(
                query = query,
                channelResults = emptyList(),
                movieResults = movies,
                seriesResults = emptyList(),
                addonResults = addonFilms,
                onPlayChannel = {},
                onPlayMovie = {},
                onOpenSeries = {},
                onOpenAddonFilm = { openedFilms += it },
                onBack = {},
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `the Provider's results come first, then the films it doesn't carry`() {
        query.setTextAndPlaceCursorAtEnd("lost")
        show(movies = listOf(movie("Lost Highway")), addonFilms = listOf(LOST))

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("Lost Highway") and hasClickAction()).assertIsFocused()
        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        compose.onNode(hasText("Lost Film") and hasClickAction()).assertIsFocused()
        compose.onNodeWithText("Not from your provider").assertIsDisplayed()
        captureScreen("search_not_from_provider")
    }

    @Test
    fun `OK on a film the Provider doesn't carry opens it`() {
        query.setTextAndPlaceCursorAtEnd("lost")
        show(addonFilms = listOf(LOST))

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(openedFilms).containsExactly(LOST)
    }

    @Test
    fun `without an add-on there's no such section`() {
        query.setTextAndPlaceCursorAtEnd("lost")
        show(movies = listOf(movie("Lost Highway")))

        assertThat(compose.onAllNodes(hasText("Not from your provider")).fetchSemanticsNodes()).isEmpty()
    }

    private fun movie(name: String) = Movie(
        id = 1,
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = null,
        streamUrl = "https://example.com/$name.mp4",
    )

    private companion object {
        val LOST = AddonTitle(
            tmdbId = "101",
            imdbId = "tt0000101",
            name = "Lost Film",
            year = 1999,
            posterUrl = null,
            backdropUrl = null,
            overview = null,
        )
    }
}
