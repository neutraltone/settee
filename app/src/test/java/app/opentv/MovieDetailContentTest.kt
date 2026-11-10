/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso
import app.opentv.data.model.Movie
import app.opentv.data.model.StremioStream
import app.opentv.data.repo.CoverStatus
import app.opentv.ui.vod.MovieDetailContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which a lone composable doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MovieDetailContentTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val film = Movie(
        id = 7,
        sourceId = 1,
        streamId = "m",
        name = "The Quiet Road",
        categoryId = null,
        posterUrl = "https://example.com/poster.jpg",
        rating = 7.8,
        year = 2021,
        plot = "A long drive home, and everything said on the way.",
        durationSeconds = 112 * 60,
        containerExtension = "mkv",
        streamUrl = "https://example.com/m.mkv",
        backdropUrl = "https://example.com/backdrop.jpg",
        genre = "Drama, Road",
        cast = "Somebody Famous",
        director = "Someone Else",
    )

    private val streams = listOf(
        StremioStream(url = "https://example.com/a.mkv", title = "1080p", addonName = "First add-on"),
        StremioStream(url = "https://example.com/b.mkv", title = "4K", addonName = "Second add-on"),
    )

    private var plays = 0
    private var playedStream: StremioStream? = null
    private val favouriteRequests = mutableListOf<Boolean>()
    private val favourite = mutableStateOf<Boolean?>(false)

    private var findStreams: (suspend () -> List<StremioStream>) = { streams }

    private fun show(resumes: Boolean = false, status: CoverStatus? = null, addons: Boolean = false) {
        compose.setTvContent {
            MovieDetailContent(
                movie = film,
                resumes = resumes,
                status = status,
                favourite = favourite.value,
                findAddonStreams = if (addons) findStreams else null,
                onPlay = { plays++ },
                onPlayAddonStream = { playedStream = it },
                onSetFavourite = { favouriteRequests += it },
            )
        }
        settle()
    }

    // The page places its highlight a moment after it appears.
    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
    }

    private fun press(key: Key) {
        compose.onNode(isFocused()).performKeyInput { pressKey(key) }
        settle()
    }

    @Test
    fun `a film opens on Play, with its genre, year and runtime, and nothing beneath the hero`() {
        show()

        compose.onNodeWithText("Play").assertIsFocused()
        compose.onNodeWithText("Drama · 2021 · 1h 52m").assertIsDisplayed()
        compose.onNodeWithText("A long drive home, and everything said on the way.").assertIsDisplayed()
        compose.onNodeWithText("Somebody Famous").assertDoesNotExist()
        compose.onNodeWithText("More like this").assertDoesNotExist()
        captureScreen("movie_detail")
    }

    @Test
    fun `a film part way through resumes, and says how long is left`() {
        show(resumes = true, status = CoverStatus.InProgress(progress = 0.7f, remainingMillis = 30 * 60_000L))

        compose.onNodeWithText("Resume").assertIsFocused()
        compose.onNodeWithText("Drama · 2021 · 1h 52m · 30 min left").assertIsDisplayed()
        captureScreen("movie_detail_resume")
    }

    @Test
    fun `a Watched film says so`() {
        show(status = CoverStatus.Watched)

        compose.onNodeWithText("Drama · 2021 · 1h 52m · Watched").assertIsDisplayed()
    }

    @Test
    fun `pressing Play plays the film`() {
        show()

        compose.onNodeWithText("Play").performClick()

        assertThat(plays).isEqualTo(1)
    }

    @Test
    fun `Favourite adds the film, and Remove favourite takes it off`() {
        show()
        compose.onNodeWithText("Favourite").performClick()
        favourite.value = true
        settle()
        compose.onNodeWithText("Remove favourite").performClick()

        assertThat(favouriteRequests).containsExactly(true, false).inOrder()
    }

    @Test
    fun `without add-ons there is no Add-on sources button`() {
        show()

        compose.onNodeWithText("Add-on sources").assertDoesNotExist()
    }

    @Test
    fun `with add-ons, Add-on sources comes last and opens a picker that plays the stream picked`() {
        show(addons = true)

        press(Key.DirectionRight)
        compose.onNodeWithText("Favourite").assertIsFocused()
        press(Key.DirectionRight)
        compose.onNodeWithText("Add-on sources").assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNodeWithText("Choose a stream").assertIsDisplayed()
        compose.onNode(hasText("4K") and hasClickAction()).assertExists()
        captureScreen("movie_detail_addon_picker")

        compose.onNode(hasText("4K") and hasClickAction()).performClick()
        settle()

        assertThat(playedStream).isEqualTo(streams[1])
        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
    }

    @Test
    fun `a picker closed while it is still looking stays closed when the streams arrive`() {
        val answer = CompletableDeferred<List<StremioStream>>()
        findStreams = { answer.await() }
        show(addons = true)
        compose.onNodeWithText("Add-on sources").performClick()
        compose.onNodeWithText("Finding streams from your add-ons…").assertIsDisplayed()

        Espresso.pressBack()
        settle()
        answer.complete(streams)
        settle()

        compose.onNodeWithText("Choose a stream").assertDoesNotExist()
    }

    @Test
    fun `two add-ons offering the same link both show`() {
        val same = "https://example.com/same.mkv"
        findStreams = {
            listOf(
                StremioStream(url = same, title = "1080p", addonName = "First add-on"),
                StremioStream(url = same, title = "1080p", addonName = "Second add-on"),
            )
        }
        show(addons = true)

        compose.onNodeWithText("Add-on sources").performClick()
        settle()

        compose.onNodeWithText("First add-on").assertExists()
        compose.onNodeWithText("Second add-on").assertExists()
    }
}
