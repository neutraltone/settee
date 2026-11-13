/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Channel
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.VodViewModel
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The search screen with its real view models, options panel and database, where
 * [SearchContentTest] fakes them: saving when a result opens, and Remove from the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class RecentSearchesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
    private val recent get() = graph.viewerLibrary.recentSearches

    // The graph is process-wide, so its database outlives each test.
    @Before
    fun startEmpty() = runBlocking { recent.clear() }

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun show() {
        compose.setTvContent {
            view = LocalView.current
            ItemOptionsHost {
                SearchScreen(onPlayChannel = {}, onPlayMovie = {}, onOpenSeries = {}, onBack = {})
            }
        }
    }

    private fun waitFor(text: String): SemanticsNodeInteraction {
        compose.waitUntilAtLeastOneExists(hasText(text), TIMEOUT_MILLIS)
        return compose.onNodeWithText(text)
    }

    private fun shown() = runBlocking { recent.observe().first() }

    @Test
    fun `opening a result saves the search, and typing alone doesn't`() {
        runBlocking {
            graph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            graph.database.channels().replaceCatalogue(1, listOf(channel("BBC One")), syncStamp = 1)
            graph.catalogRepository.renormalizeAll()
        }
        show()

        device.type("bbc")
        val result = waitFor("BBC One")
        assertThat(shown()).isEmpty()

        result.performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { shown() == listOf("bbc") }
    }

    @Test
    fun `Remove in the panel opened with Menu removes the Recent Search`() {
        runBlocking {
            recent.save("news")
            recent.save("bbc")
        }
        show()

        waitFor("bbc").requestFocus().performKeyInput { pressKey(Key.Menu) }
        waitFor("Remove").performClick()

        compose.waitUntil(TIMEOUT_MILLIS) { shown() == listOf("news") }
        // The database answers before the screen: the list reaches it through a flow on another
        // thread, which waitForIdle doesn't know about, and only then does the row take the highlight.
        compose.waitUntilAtLeastOneExists(hasText("news") and isFocused(), TIMEOUT_MILLIS)
    }

    @Test
    fun `holding Select opens the same panel`() {
        runBlocking { recent.save("bbc") }
        show()

        waitFor("bbc").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }

        waitFor("Remove")
    }

    // Search now shares the app's one VodViewModel, which outlives each visit, where it used to get a new one.
    @Test
    fun `Search opened again starts empty, without the last visit's movies`() {
        // A graph and database of its own for the movie, so the app's shared one, which outlives each
        // test, doesn't gain a film every run.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vodGraph = ServiceLocator.Graph(
            app,
            settingsFile = "search-vod-test",
            openDatabase = {
                Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
            },
        )
        runBlocking {
            vodGraph.database.sources().insert(
                Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
            )
            vodGraph.database.movies().upsertAll(listOf(movie("Zebra Crossing")))
        }
        val vod = VodViewModel(app, vodGraph)
        try {
            searchAgain(vod)
        } finally {
            // Nothing clears a model built outside a ViewModelStore, so stop its flows by hand.
            vod.viewModelScope.cancel()
            vodGraph.database.close()
        }
    }

    private fun searchAgain(vod: VodViewModel) {
        var open by mutableStateOf(true)
        compose.setTvContent {
            view = LocalView.current
            ItemOptionsHost {
                if (open) {
                    SearchScreen(
                        onPlayChannel = {},
                        onPlayMovie = {},
                        onOpenSeries = {},
                        onBack = {},
                        vodViewModel = vod,
                    )
                }
            }
        }
        device.type("zebra")
        waitFor("Zebra Crossing")

        open = false
        // Before Search is back, so its first frame can't have them. waitUntil alone doesn't
        // recompose, so Search would never leave; waitForIdle each time round does.
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.waitForIdle()
            vod.movieResults.value.isEmpty()
        }
        open = true
        compose.waitForIdle()

        compose.onNodeWithText("zebra").assertDoesNotExist()
        compose.onNodeWithText("Zebra Crossing").assertDoesNotExist()
    }

    private fun channel(name: String) = Channel(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "https://example.com/$name.ts",
    )

    private fun movie(name: String) = Movie(
        sourceId = 1,
        streamId = name,
        name = name,
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "https://example.com/$name.mp4",
    )

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
