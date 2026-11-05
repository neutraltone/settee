/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.options.ItemOptionsHost
import com.google.common.truth.Truth.assertThat
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

    private fun show() {
        compose.setTvContent {
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

        compose.onNode(hasSetTextAction()).performTextInput("bbc")
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

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
