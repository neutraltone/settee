/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.ui.MainScreen
import app.opentv.ui.Tab
import app.opentv.ui.vod.ProvidersCarry
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Recordings emptied from under the Highlight puts it on the rail's Recordings item (#444), through
 * the real main screen and Recordings screen, so their wiring of `onNothingToShow` is what's tested.
 *
 * In Robolectric this can't fail on that wiring alone: when the focused row goes, Android asks the
 * Compose view for focus again, and the rail's own way in sends it to the open tab's item too. It
 * still pins where the Highlight ends up through the screens the app really draws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingsEmptiedTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val graph = testGraph.graph

    private fun setMainScreenOnRecordings() = compose.setTvContent {
        MainScreen(
            isTelevision = true,
            providers = ProvidersCarry.MOVIES_AND_SHOWS,
            isSyncing = false,
            onPlayChannel = {},
            onOpenMovie = {},
            onOpenSeries = {},
            onResume = {},
            onAddSource = {},
            onEditProvider = {},
            onRefresh = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            onPlayRecording = {},
            onPlayCatchup = {},
            activeProfileName = "Sam",
            tab = Tab.RECORDINGS,
            onSelectTab = {},
        )
    }

    @Test
    fun `the only recording going from under the Highlight puts it on the rail's Recordings item`() {
        val now = System.currentTimeMillis()
        val id = runBlocking {
            graph.recordingRepository.insert(
                Recording(
                    channelId = 1,
                    sourceId = 1,
                    channelName = "Channel 1",
                    title = "Recording 1",
                    filePath = "/rec/1.ts",
                    streamUrl = "",
                    startedAtMillis = now - 2 * HOUR,
                    endedAtMillis = now - HOUR,
                    status = RecordingStatus.COMPLETED,
                ),
            )
        }
        setMainScreenOnRecordings()
        compose.onAllNodes(hasText("Recording 1", substring = true) and hasClickAction()).onFirst().requestFocus()
        compose.waitForIdle()

        runBlocking { graph.recordingRepository.delete(id) }
        compose.onAllNodes(hasText("No recordings yet", substring = true)).onFirst().assertExists()

        compose.onNode((hasContentDescription("Recordings") or hasText("Recordings")) and hasClickAction())
            .assertIsFocused()
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
