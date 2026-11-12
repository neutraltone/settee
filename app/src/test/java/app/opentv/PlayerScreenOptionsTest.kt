/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.player.PlayerScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @get:Rule
    val compose = createComposeRule()

    private val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

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

    // A recording left running would make any later test's Select stop to ask about it.
    @After
    fun stopRecordings() = runBlocking {
        graph.recordingRepository.active().forEach { graph.database.recordings().delete(it.id) }
    }

    // The graph is process-wide, so its database outlives each test.
    @Before
    fun catalogue() = runBlocking {
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
        val itv = graph.channelLineup.channelOf(idOf("10"))!!
        graph.channelLineup.setFavourite(itv, false)
    }

    private suspend fun idOf(streamId: String) =
        graph.database.channels().everyRow().single { it.streamId == streamId }.id

    /** The player on [streamId], once it has found its Channel: the banner names it. */
    private fun showPlaying(streamId: String, named: String) {
        val id = runBlocking { idOf(streamId) }
        compose.setTvContent { PlayerScreen(channelId = id, onBack = {}) }
        compose.waitUntilAtLeastOneExists(hasText(named, substring = true), TIMEOUT_MILLIS)
    }

    private fun openList() {
        compose.onRoot().performKeyInput { pressKey(Key.Menu) }
        compose.waitForIdle()
    }

    // Each Quality's write resumes on the main looper, which waitUntil alone doesn't run.
    private fun favouriteIds(): Set<Long> {
        shadowOf(Looper.getMainLooper()).idle()
        return runBlocking { graph.viewerLibrary.favourites.channelIds().first() }
    }

    private fun inListMatcher(text: String) = hasText(text) and hasAnyAncestor(isDialog())

    private fun waitInList(text: String) = compose.waitUntilAtLeastOneExists(inListMatcher(text), TIMEOUT_MILLIS)

    @Test
    fun `Record now records the Quality that's playing`() {
        showPlaying("10", "ITV")
        openList()
        waitInList("Record now")

        compose.onNode(inListMatcher("Record now")).performClick()

        compose.waitUntil(TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { graph.recordingRepository.active() }.isNotEmpty()
        }
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
        compose.waitUntil(TIMEOUT_MILLIS) { favouriteIds().containsAll(qualities) }
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

        compose.waitUntil(TIMEOUT_MILLIS) { favouriteIds().isEmpty() }
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

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
