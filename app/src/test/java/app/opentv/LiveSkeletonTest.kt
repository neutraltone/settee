/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.opentv.core.StatusBus
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.SKELETON_CARD
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live while its guide is built from channels already on the device, as on every cold start (#581):
 * skeleton guide rows, the first holding the Highlight, rather than a centred loading message. With
 * no channels on the device, the messages that say why stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveSkeletonTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose, name = "live-skeleton-test")

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val played = mutableListOf<Channel>()

    @After
    fun tearDown() {
        StatusBus.set(null)
    }

    private fun channelsOnTheDevice() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        val rows = listOf("BBC One", "ITV").mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = null,
                logoUrl = null,
                epgChannelId = null,
                number = i + 1,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
    }

    private fun setLive(hasSources: Boolean = true, isSyncing: Boolean = false) {
        graph.settings.setGuidePreviewVideo(false)
        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = hasSources,
                isSyncing = isSyncing,
                onPlayChannel = { played += it },
                onAddSource = {},
                onRefresh = {},
                viewModel = testGraph.viewModel { ChannelsViewModel(app, graph) },
            )
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `with channels on the device and the guide still building, skeleton rows show, the first highlighted`() {
        channelsOnTheDevice()
        testGraph.hold()
        setLive()
        compose.onAllNodesWithTag(SKELETON_CARD).onFirst().assertExists()

        compose.onAllNodesWithTag(SKELETON_CARD).onFirst().assertIsFocused()
        assertThat(compose.onAllNodes(hasText("Loading your channels")).fetchSemanticsNodes()).isEmpty()
        captureScreen("live_skeleton")

        press(Key.DirectionCenter)

        assertThat(played).isEmpty()
    }

    @Test
    fun `when the guide is built its first channel takes the Highlight from the skeleton`() {
        channelsOnTheDevice()
        testGraph.hold()
        setLive()
        compose.onAllNodesWithTag(SKELETON_CARD).onFirst().assertExists()

        testGraph.release()
        compose.onAllNodes(hasText("BBC One")).onFirst().assertExists()
        compose.waitForIdle()

        compose.onNode(hasText("BBC One") and androidx.compose.ui.test.isFocusable()).assertIsFocused()
    }

    @Test
    fun `with no channels on the device and no provider, it still says there are none`() {
        setLive(hasSources = false)

        compose.onAllNodes(hasText("No channels yet")).onFirst().assertExists()
        assertThat(compose.onAllNodesWithTag(SKELETON_CARD).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `a first sync says what it's fetching in its own words, not the corner's`() {
        StatusBus.show("Refreshing your channels")
        setLive(isSyncing = true)

        compose.onAllNodes(hasText("Loading your channels")).onFirst().assertExists()
        compose.onNodeWithText("A large provider can take a couple of minutes", substring = true).assertExists()
        compose.onNodeWithText("Refreshing your channels").assertDoesNotExist()
    }
}
