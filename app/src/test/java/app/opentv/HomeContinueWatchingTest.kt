/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.data.model.Movie
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.ResumeItem
import app.opentv.ui.SeasonEpisode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Where the highlight goes in Home's Continue Watching as its cards move round and move on. */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class HomeContinueWatchingTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun film(id: Long, title: String) = ResumeItem(
        MediaKey.Movie(id),
        title,
        posterUrl = "https://example.com/$id.jpg",
        progress = 0.4f,
        titleKey = "movie:1:$id",
    )

    /** [show]'s card at its episode [id], titled with the show as Continue Watching titles it. */
    private fun episodeOf(show: String, id: Long, season: Int, episode: Int) = ResumeItem(
        MediaKey.Episode(id),
        show,
        posterUrl = "https://example.com/$id.jpg",
        progress = 0.4f,
        episode = SeasonEpisode(season, episode),
        titleKey = "series:1:$show",
    )

    private fun cover(id: Long, name: String) = Cover(
        VodTitle.OfMovie(
            Movie(
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
            ),
        ),
    )

    /** Whether Home is on screen; false while the viewer is off in the player. */
    private var home by mutableStateOf(true)

    /**
     * Shows Home the way the app's navigation does, with a row of Favourite movies under [cards]:
     * going to another screen drops Home but keeps its saved state, which comes back when Home does.
     */
    private fun setHome(cards: () -> List<ResumeItem>) = compose.setTvContent {
        val saved = rememberSaveableStateHolder()
        if (home) {
            saved.SaveableStateProvider("home") {
                HomeContent(
                    continueWatching = cards(),
                    favouriteShows = emptyList(),
                    favouriteMovies = listOf(cover(1, "Sicario"), cover(2, "Heat")),
                    favouriteChannels = emptyList(),
                    onResume = {},
                    onOpenSeries = {},
                    onOpenMovie = {},
                    onPlayChannel = {},
                    takeFocus = true,
                )
            }
        }
    }

    private fun card(title: String) = compose.onNode(hasText(title) and hasClickAction())

    /** Plays [title]'s card, leaves Home for the player, and comes back. */
    private fun pickAndComeBack(title: String, whileAway: () -> Unit) {
        card(title).performClick()
        home = false
        compose.waitForIdle()
        whileAway()
        home = true
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `back from the player, Home highlights the title played even once Continue Watching puts it first`() {
        var cards by mutableStateOf(listOf(film(9, "Collateral"), film(7, "Insomnia"), film(8, "Memento")))
        setHome { cards }

        pickAndComeBack("Memento") { cards = listOf(film(8, "Memento"), film(9, "Collateral"), film(7, "Insomnia")) }

        card("Memento").assertIsFocused()
    }

    @Test
    fun `back from the player, Home highlights the show played even once its card has moved on to the next episode`() {
        var cards by mutableStateOf(
            listOf(film(9, "Collateral"), film(7, "Insomnia"), episodeOf("Pachinko", 43, 1, 3)),
        )
        setHome { cards }

        pickAndComeBack("Pachinko") {
            cards = listOf(episodeOf("Pachinko", 44, 1, 4), film(9, "Collateral"), film(7, "Insomnia"))
        }

        card("Pachinko").assertIsFocused()
    }

    // A position synced from another device moves the card on while the viewer is elsewhere on Home.
    @Test
    fun `back into Continue Watching, the show last highlighted keeps the highlight once its card moves on`() {
        var cards by mutableStateOf(
            listOf(film(9, "Collateral"), film(7, "Insomnia"), episodeOf("Pachinko", 43, 1, 3)),
        )
        setHome { cards }
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionDown)

        cards = listOf(film(9, "Collateral"), film(7, "Insomnia"), episodeOf("Pachinko", 44, 1, 4))
        compose.waitForIdle()
        press(Key.DirectionUp)

        card("Pachinko").assertIsFocused()
    }
}
