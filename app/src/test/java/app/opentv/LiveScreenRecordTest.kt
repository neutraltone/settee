/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.core.ViewerError
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.MainShell
import app.opentv.ui.ProviderFailure
import app.opentv.ui.Tab
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.visibleTabs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recording from the guide, through programme details, records what this device can play, so it
 * can always be watched here.
 * Robolectric lists no decoders, so here as on a 1080p Stick a Quality tagged 4K doesn't play by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LiveScreenRecordTest {

    private val compose = createComposeRule()

    // A build that reports an error that could be Settee's, as a release does.
    @get:Rule
    val testGraph = TestGraph(compose, crashReportsTo = reportsOffered())

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = testGraph.graph

    // The settings are the process's, so what a test changes is the next test's to trip over.
    @After
    fun leaveNothingBehind() = graph.settings.setGuidePreviewVideo(true)

    @Test
    fun `Record now on what's on records the Quality this device plays, and then offers to stop it`() {
        val playable = runBlocking { seedChannel() }
        graph.settings.setGuidePreviewVideo(false)

        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = true,
                isSyncing = false,
                onPlayChannel = {},
                onAddSource = {},
                onRefresh = {},
            )
        }
        compose.onAllNodes(hasText("The News") and hasClickAction()).onFirst().assertExists()
        compose.onNode(hasText("The News") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Record now") and hasClickAction()).onFirst().assertExists()
        compose.onNode(hasText("Record now") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertThat(runBlocking { graph.recordingRepository.active().isNotEmpty() }).isTrue()
        compose.onNode(hasText("The News") and hasClickAction()).performClick()
        compose.onAllNodes(hasText("Stop recording") and hasClickAction()).onFirst().assertExists()

        val recording = runBlocking { graph.recordingRepository.observeActive().first().single() }
        assertThat(recording.channelId).isEqualTo(playable)
    }

    @Test
    fun `a guide whose channels couldn't load names the provider, says why, and offers Try again`() {
        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = true,
                isSyncing = false,
                loadFailure = ProviderFailure(
                    1,
                    "Acme TV",
                    ViewerError(
                        "Can't reach your provider. Check this TV is online and the address is right.",
                        "UnknownHost",
                    ),
                ),
                onPlayChannel = {},
                onAddSource = {},
                onRefresh = {},
            )
        }
        compose.onAllNodesWithText("Can't reach your provider.", substring = true).onFirst().assertExists()

        compose.onNodeWithText("Couldn't load your channels from Acme TV").assertExists()
        compose.onNodeWithTag(ERROR_DETAIL_TAG).assertTextEquals("UnknownHost")
        compose.onNodeWithText("Try again").assertExists()
        captureScreen("live_guide_load_failed")
    }

    @Test
    fun `a guide failing in a way that could be Settee's keeps Try again lit, with Report at the end of its buttons`() {
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.LIVE,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                LiveScreen(
                    isTelevision = true,
                    hasSources = true,
                    isSyncing = false,
                    loadFailure = ProviderFailure(
                        1,
                        "Acme TV",
                        ViewerError("Couldn't load channels.", "HTTP 500"),
                        SourceKind.M3U,
                    ),
                    onPlayChannel = {},
                    onAddSource = {},
                    onRefresh = {},
                )
            }
        }
        val report = compose.onNode(hasText(testGraph.app.getString(R.string.report_button)) and hasClickAction())
        report.assertExists()
        captureScreen("live_guide_load_failed_report")

        // Into the guide from the rail, as the remote comes.
        compose.onNode(hasContentDescription("Live TV") or hasText("Live TV")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(RAIL_HANDOVER_MILLIS)
        compose.waitForIdle()
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        report.assertIsFocused()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** One Channel with a 4K and an HD Quality; answers the HD row's id. */
    private suspend fun seedChannel(): Long {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.categories().upsertAll(listOf(Category(id = "10", sourceId = 1, name = "News", kind = StreamKind.LIVE)))
        val rows = listOf("News 4K", "News HD").mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = "10",
                logoUrl = null,
                epgChannelId = "news",
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        val now = System.currentTimeMillis()
        db.programmes().insertOrReplace(
            listOf(
                Programme(
                    feedId = feed,
                    epgChannelId = "news",
                    startUtcMillis = now - HOUR,
                    endUtcMillis =
                    now + HOUR,
                    title = "The News",
                ),
            ),
        )
        return db.channels().everyRow().single { it.name == "News HD" }.id
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L

        /** How long the rail takes to hand the Highlight to the tab it opened. */
        const val RAIL_HANDOVER_MILLIS = 500L
    }
}
