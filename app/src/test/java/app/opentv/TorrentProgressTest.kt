/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.C
import app.opentv.player.torrent.TorrentStatus
import app.opentv.ui.vod.TorrentProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The line under the player's waiting TV while a torrent plays (#670), and whether it says the people
 * sharing it are sending it slower than it plays. A 1 GB film lasting 1,000 seconds plays 1 MB a second.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TorrentProgressTest {
    @get:Rule
    val compose = createComposeRule()

    private val status = MutableStateFlow<TorrentStatus>(TorrentStatus.Idle)
    private var durationMillis = 1_000_000L

    private fun show() = compose.setTvContent {
        Column { TorrentProgress(status, durationMillis = { durationMillis }) }
    }

    private fun arriving(bytesPerSecond: Long?) =
        TorrentStatus.Ready(fileSize = 1_000_000_000L, peers = 6, bytesPerSecond = bytesPerSecond)

    @Test
    fun `a torrent arriving slower than it plays says it will keep stopping, and a smaller copy may play smoothly`() {
        status.value = arriving(640_000)
        show()

        compose.onNodeWithText("Fetching it from 6 people").assertIsDisplayed()
        compose.onNodeWithText(SLOW).assertIsDisplayed()
        captureScreen("vod_player_torrent_too_slow")
    }

    @Test
    fun `a torrent arriving faster than it plays says nothing about speed`() {
        status.value = arriving(1_500_000)
        show()

        compose.onNodeWithText("Fetching it from 6 people").assertIsDisplayed()
        compose.onNodeWithText(SLOW).assertDoesNotExist()
    }

    @Test
    fun `before the speed has been measured, nothing is said about it`() {
        status.value = arriving(null)
        show()

        compose.onNodeWithText(SLOW).assertDoesNotExist()
    }

    @Test
    fun `before the player knows how long the film is, nothing is said about speed`() {
        durationMillis = C.TIME_UNSET
        status.value = arriving(640_000)
        show()

        compose.onNodeWithText(SLOW).assertDoesNotExist()
    }

    private companion object {
        const val SLOW =
            "It's arriving slower than it plays, so it will keep stopping. A smaller copy may play smoothly."
    }
}
