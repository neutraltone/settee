/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
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
@OptIn(ExperimentalTestApi::class)
class LiveSkeletonTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val catalogue = HeldCatalogue(app, "live-skeleton-test")
    private val graph = catalogue.graph
    private val viewModels = TestViewModels()
    private val played = mutableListOf<Channel>()

    @After
    fun tearDown() {
        catalogue.release()
        viewModels.clear()
        catalogue.close()
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

    private fun setLive(hasSources: Boolean = true) {
        graph.settings.setGuidePreviewVideo(false)
        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = hasSources,
                isSyncing = false,
                onPlayChannel = { played += it },
                onAddSource = {},
                onRefresh = {},
                viewModel = viewModels.channels(app, graph),
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
        catalogue.hold()
        setLive()
        compose.waitUntilAtLeastOneExists(hasTestTag(SKELETON_CARD), 5_000)

        compose.onAllNodesWithTag(SKELETON_CARD).onFirst().assertIsFocused()
        assertThat(compose.onAllNodes(hasText("Loading your channels")).fetchSemanticsNodes()).isEmpty()
        captureScreen("live_skeleton")

        press(Key.DirectionCenter)

        assertThat(played).isEmpty()
    }

    @Test
    fun `when the guide is built its first channel takes the Highlight from the skeleton`() {
        channelsOnTheDevice()
        catalogue.hold()
        setLive()
        compose.waitUntilAtLeastOneExists(hasTestTag(SKELETON_CARD), 5_000)

        catalogue.release()
        compose.waitUntilAtLeastOneExists(hasText("BBC One"), 5_000)
        compose.waitForIdle()

        compose.onNode(hasText("BBC One") and androidx.compose.ui.test.isFocusable()).assertIsFocused()
    }

    @Test
    fun `with no channels on the device and no provider, it still says there are none`() {
        setLive(hasSources = false)

        compose.waitUntilAtLeastOneExists(hasText("No channels yet"), 5_000)
        assertThat(compose.onAllNodesWithTag(SKELETON_CARD).fetchSemanticsNodes()).isEmpty()
    }
}
