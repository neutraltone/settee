/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.visibleTabs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live TV's guide emptied from under the Highlight, by unstarring the last Favourite or hiding a
 * category's last channel: the Highlight goes to Choose a category, the only thing left (#450).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveEmptiedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { ChannelsViewModel(app, graph) } }

    @After
    fun close() {
        graph.settings.setGuidePreviewVideo(true)
    }

    private fun channel(id: Long, name: String, category: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = category,
        logoUrl = null,
        epgChannelId = null,
        number = id.toInt(),
        streamUrl = "https://example.com/$id.ts",
    )

    /** News with two channels and Sport with one, from one provider. */
    private fun library() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.categories().upsertAll(
            listOf(
                Category("10", 1, "News", StreamKind.LIVE, sortIndex = 0),
                Category("20", 1, "Sport", StreamKind.LIVE, sortIndex = 1),
            ),
        )
        db.channels().replaceCatalogue(
            1,
            listOf(channel(1, "Channel One", "10"), channel(2, "Channel Two", "10"), channel(3, "Sport Three", "20")),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
    }

    private var tab by mutableStateOf(Tab.HOME)

    /** OK on Live TV in the rail, which puts the Highlight in its guide. */
    private fun openLive() {
        graph.settings.setGuidePreviewVideo(false)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = tab,
                onSelect = { tab = it },
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { open, takeFocus, _ ->
                if (open == Tab.LIVE) {
                    LiveScreen(
                        isTelevision = true,
                        hasSources = true,
                        isSyncing = false,
                        onPlayChannel = {},
                        onAddSource = {},
                        onRefresh = {},
                        takeFocus = takeFocus,
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.onNode((hasContentDescription("Home") or hasText("Home")) and hasClickAction()).requestFocus()
        compose.waitForIdle()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** From All channels' first channel, opens the sidebar, picks [label] and goes Right into the guide. */
    private fun pickInSidebar(label: String) {
        press(Key.DirectionLeft)
        // The sidebar opens on All channels: Favourites above it, then News and Sport below.
        when (label) {
            "Favourites" -> press(Key.DirectionUp)
            "Sport" -> press(Key.DirectionDown, times = 2)
        }
        compose.onNode(hasText(label) and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionRight)
    }

    private fun row(name: String) = viewModel.guideRows.value.rows.first { it.primary.name == name }

    private fun channelCell(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun chooseCategory() = compose.onNode(hasText("Choose a category") and hasClickAction())

    @Test
    fun `removing the last Favourite from the guide puts the Highlight on Choose a category`() {
        library()
        openLive()
        viewModel.toggleFavourite(row("Sport Three"))
        compose.waitForIdle()
        pickInSidebar("Favourites")
        channelCell("Sport Three").assertIsFocused()

        viewModel.toggleFavourite(row("Sport Three"))
        compose.waitForIdle()
        compose.onAllNodes(hasText("No favourites yet")).onFirst().assertExists()

        chooseCategory().assertIsFocused()
        captureScreen("live_no_favourites_highlighted")
    }

    @Test
    fun `hiding the last channel of a category from the guide puts the Highlight on Choose a category`() {
        library()
        openLive()
        pickInSidebar("Sport")
        channelCell("Sport Three").assertIsFocused()

        viewModel.hide(row("Sport Three"))
        compose.waitForIdle()
        compose.onAllNodes(hasText("Nothing to show here")).onFirst().assertExists()

        chooseCategory().assertIsFocused()
    }
}
