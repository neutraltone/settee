/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.ERROR_DETAIL_TAG
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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
class LiveScreenRecordTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

    // The graph and its settings are process-wide, so what a test changes is the next test's to trip over.
    @Before
    @After
    fun leaveNothingBehind() = runBlocking {
        graph.recordingRepository.active().forEach { graph.database.recordings().delete(it.id) }
        graph.settings.setGuidePreviewVideo(true)
    }

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
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasText("The News") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("The News") and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasText("Record now") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Record now") and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) { runBlocking { graph.recordingRepository.active().isNotEmpty() } }
        compose.onNode(hasText("The News") and hasClickAction()).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasText("Stop recording") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }

        val recording = runBlocking { graph.recordingRepository.observeActive().first().single() }
        assertThat(recording.channelId).isEqualTo(playable)
    }

    @Test
    fun `a guide whose channels couldn't load says why, with the detail line, and offers Try again`() {
        // The graph is process-wide, so another test's channels would leave this guide loading.
        runBlocking {
            graph.database.sources().observeAll().first().forEach { graph.catalogRepository.deleteSource(it.id) }
        }
        compose.setTvContent {
            LiveScreen(
                isTelevision = true,
                hasSources = true,
                isSyncing = false,
                loadFailure = ViewerError(
                    "Can't reach your provider. Check this TV is online and the address is right.",
                    "UnknownHost",
                ),
                onPlayChannel = {},
                onAddSource = {},
                onRefresh = {},
            )
        }
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodesWithText(
                "Can't reach your provider.",
                substring = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(ERROR_DETAIL_TAG).assertTextEquals("UnknownHost")
        compose.onNodeWithText("Try again").assertExists()
        captureScreen("live_guide_load_failed")
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
        const val TIMEOUT_MILLIS = 10_000L
        const val HOUR = 60 * 60 * 1000L
    }
}
