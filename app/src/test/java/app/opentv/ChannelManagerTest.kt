/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.channels.ChannelManagerScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The channel manager on the remote: its sidebar always open, and one target per channel row (#164). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ChannelManagerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.channels(app, graph) }
    private lateinit var view: View
    private var left = false

    @After
    fun close() {
        graph.settings.setHiddenCategories(emptySet())
        viewModels.clear()
        graph.database.close()
    }

    private fun channel(id: Long, name: String, source: Long, category: String) = Channel(
        id = id,
        sourceId = source,
        streamId = "$id",
        name = name,
        categoryId = category,
        logoUrl = null,
        epgChannelId = null,
        number = id.toInt(),
        streamUrl = "https://example.com/$id.ts",
    )

    /** News from one provider, Sport from another, and a category under Parental Lock. */
    private fun library() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.sources().insert(Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "https://example.org"))
        db.categories().upsertAll(
            listOf(
                Category("10", 1, "News", StreamKind.LIVE, sortIndex = 0),
                Category("20", 2, "Sport", StreamKind.LIVE, sortIndex = 0),
                Category("66", 2, "UK| Adult", StreamKind.LIVE, sortIndex = 1),
            ),
        )
        db.channels().replaceCatalogue(
            1,
            listOf(channel(1, "Channel One", 1, "10"), channel(2, "Channel Two", 1, "10")),
            syncStamp = 1,
        )
        db.channels().replaceCatalogue(
            2,
            listOf(channel(4, "Sport Four", 2, "20"), channel(6, "Late Six", 2, "66")),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
        graph.settings.setHiddenCategories(setOf("adult"))
    }

    private fun setManager() {
        library()
        compose.setTvContent {
            view = LocalView.current
            ChannelManagerScreen(onBack = { left = true }, viewModel = viewModel)
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.managerRows.value.isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun entry(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** A channel's row, which says its name and whether it's showing in the guide. */
    private fun row(name: String) = compose.onNode(hasText(name, substring = true) and hasClickAction())

    @Test
    fun `it opens with the sidebar open on the first category, Search at the top and locked categories listed`() {
        setManager()

        entry("News").assertIsFocused()
        entry("Search for channels").assertExists()
        entry("Sport").assertExists()
        entry("Adult").assertExists()
        compose.onNode(hasText("Channel One", substring = true) and hasText("Showing", substring = true)).assertExists()
        compose.onNode(hasText("Done")).assertDoesNotExist()
        captureScreen("channel_manager_first_highlight")
    }

    @Test
    fun `OK on a channel row hides it, and OK again shows it`() {
        setManager()
        press(Key.DirectionRight)
        row("Channel One").assertIsFocused()

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(
            hasText("Channel One", substring = true) and hasText("Hidden", substring = true),
            5_000,
        )
        captureScreen("channel_manager_hidden")

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(
            hasText("Channel One", substring = true) and hasText("Showing", substring = true),
            5_000,
        )
    }

    @Test
    fun `holding Select on a row offers Favourite, the row's only other target`() {
        setManager()
        press(Key.DirectionRight)

        compose.onNode(isFocused()).performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        compose.waitUntilAtLeastOneExists(hasText("Add to Favourites") and hasClickAction(), 5_000)
        compose.onNode(hasText("Add to Favourites") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { graph.viewerLibrary.favourites.channelIds().first().isNotEmpty() }
        }

        assertThat(runBlocking { graph.viewerLibrary.favourites.channelIds().first() }).containsExactly(1L)
        compose.onNode(hasText("Channel One", substring = true) and hasText("Showing", substring = true)).assertExists()
    }

    @Test
    fun `OK on Search for channels moves the Highlight into the search box`() {
        setManager()
        press(Key.DirectionUp)
        entry("Search for channels").assertIsFocused()

        press(Key.DirectionCenter)

        // The second text box: the first is Find a category's, at the top of the sidebar.
        val keyboard = DeviceKeyboard(compose) { view }
        assertThat(keyboard.highlighted(keyboard.box(1))).isTrue()
    }

    @Test
    fun `Back leaves the screen`() {
        setManager()

        Espresso.pressBack()
        compose.waitForIdle()

        assertThat(left).isTrue()
    }
}
