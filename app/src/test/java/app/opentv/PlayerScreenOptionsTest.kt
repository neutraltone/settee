/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import app.opentv.data.model.Channel
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.player.PlayerScreen
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
 * The live player with its real database, where [LivePlayerContentTest] fakes what it's told: that
 * the options list's Favourite and Record reach the Channel being watched, every Quality of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, UnstableApi::class)
class PlayerScreenOptionsTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val graph = testGraph.graph

    private fun row(streamId: String, name: String, quality: String) = Channel(
        sourceId = 1,
        streamId = streamId,
        name = name,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
        qualityLabel = quality,
    )

    @Before
    fun catalogue(): Unit = runBlocking {
        graph.database.sources().insert(
            Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"),
        )
        graph.database.channels().replaceCatalogue(
            1,
            listOf(
                row("10", "UK| ITV HD", "HD"),
                row("20", "UK| ITV FHD", "FHD"),
                row("30", "UK| Film HD", "HD"),
                row("40", "UK| News HD", "HD"),
                row("50", "UK| News FHD", "FHD"),
            ),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
    }

    private suspend fun idOf(streamId: String) =
        graph.database.channels().everyRow().single { it.streamId == streamId }.id

    /** The player on [streamId], once it has found its Channel: the banner names it. */
    private fun showPlaying(streamId: String, named: String) {
        val id = runBlocking { idOf(streamId) }
        compose.setTvContent { PlayerScreen(channelId = id, onBack = {}) }
        compose.onAllNodes(hasText(named, substring = true)).onFirst().assertExists()
    }

    private fun openList() {
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
    }

    private fun favouriteIds(): Set<Long> = runBlocking { graph.viewerLibrary.favourites.channelIds().first() }

    private fun inListMatcher(text: String) = hasText(text) and hasAnyAncestor(isDialog())

    private fun waitInList(text: String) = compose.onAllNodes(inListMatcher(text)).onFirst().assertExists()

    @Test
    fun `Record now records the Quality that's playing`() {
        showPlaying("10", "ITV")
        openList()
        waitInList("Record now")

        compose.onNode(inListMatcher("Record now")).performClick()

        compose.waitForIdle()
        assertThat(runBlocking { graph.recordingRepository.active() }).isNotEmpty()
        val recording = runBlocking { graph.recordingRepository.active().single() }
        assertThat(recording.channelId).isEqualTo(runBlocking { idOf("10") })
    }

    @Test
    fun `Add to Favourites stars every Quality of the Channel, and the list then offers removing it`() {
        showPlaying("10", "ITV")
        openList()
        waitInList("Add to Favourites")

        compose.onNode(inListMatcher("Add to Favourites")).performClick()

        val qualities = runBlocking { setOf(idOf("10"), idOf("20")) }
        compose.waitForIdle()
        assertThat(favouriteIds()).containsAtLeastElementsIn(qualities)
        compose.waitForIdle()
        openList()
        waitInList("Remove from Favourites")
    }

    @Test
    fun `Remove from Favourites unstars every Quality of the Channel`() {
        runBlocking {
            graph.channelLineup.setFavourite(graph.channelLineup.channelOf(idOf("10"))!!, true)
        }
        showPlaying("20", "ITV")
        openList()
        waitInList("Remove from Favourites")

        compose.onNode(inListMatcher("Remove from Favourites")).performClick()

        // So does each removal.
        compose.waitForIdle()
        assertThat(favouriteIds()).isEmpty()
    }

    @Test
    fun `a recording on another Quality of the Channel still offers stopping it`() {
        runBlocking {
            graph.database.recordings().insert(
                Recording(
                    channelId = idOf("40"),
                    sourceId = 1,
                    channelName = "News",
                    title = "News",
                    filePath = "/data/rec/news.ts",
                    streamUrl = "http://example.com/live/u/p/40.ts",
                    status = RecordingStatus.RECORDING,
                ),
            )
        }
        showPlaying("50", "News")

        openList()

        waitInList("Stop recording")
    }

    @Test
    fun `a channel being recorded offers stopping it`() {
        runBlocking {
            graph.database.recordings().insert(
                Recording(
                    channelId = idOf("30"),
                    sourceId = 1,
                    channelName = "Film",
                    title = "Film",
                    filePath = "/tmp/film.ts",
                    streamUrl = "http://example.com/live/u/p/30.ts",
                    status = RecordingStatus.RECORDING,
                ),
            )
        }
        showPlaying("30", "Film")

        openList()

        waitInList("Stop recording")
    }
}
