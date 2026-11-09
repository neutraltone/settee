/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.channels.LiveScreen
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
 * The guide's record button records a Channel's best Quality, though its preview plays the next one.
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
    fun `the record button records the best Quality and then offers to stop it`() {
        val best = runBlocking { seedChannel() }
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
            compose.onAllNodes(hasContentDescription("Record what's on now")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasContentDescription("Record what's on now")).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasContentDescription("Stop recording", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        val recording = runBlocking { graph.recordingRepository.observeActive().first().single() }
        assertThat(recording.channelId).isEqualTo(best)
    }

    /** One Channel with a 4K and an HD Quality; answers the 4K row's id. */
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
                epgChannelId = null,
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
        return db.channels().everyRow().single { it.name == "News 4K" }.id
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
