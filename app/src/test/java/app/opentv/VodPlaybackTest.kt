/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.NextEpisodeCountdown
import app.opentv.player.PlayerController
import app.opentv.ui.vod.vodPlayback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the film player's view model hears from a real [PlayerController] while it finds a stream (#379). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(UnstableApi::class)
class VodPlaybackTest {

    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private val players = TestPlayers(app)

    /** Movie 2's stream is still being found until this completes, as on a slow portal. */
    private val found = CompletableDeferred<Unit>()
    private val controller = players.controller { key ->
        if (key == MediaKey.Movie(2)) found.await()
        Playable.Ready(key, "https://example.com/stream", userAgent = "", title = "Film", isLive = false)
    }
    private val playback = controller.vodPlayback()

    @After
    fun release() {
        found.complete(Unit)
        controller.release()
        players.close()
    }

    private fun watchMovie1TenMinutesIn() {
        playback.play(PlayerController.Request(MediaKey.Movie(1), startPositionMillis = TEN_MINUTES))
        players.untilReady(controller.player)
    }

    @Test
    fun `while a stream is being found its position is where it was asked to start, and no progress is saved`() {
        watchMovie1TenMinutesIn()

        playback.play(PlayerController.Request(MediaKey.Movie(2), startPositionMillis = FIFTY_MINUTES))

        assertThat(playback.position()).isEqualTo(FIFTY_MINUTES)
        assertThat(playback.progress()).isEqualTo(NextEpisodeCountdown.Playback(positionMillis = 0, durationMillis = 0))
    }

    @Test
    fun `while a stream asked to start at the beginning is being found, its position isn't known`() {
        watchMovie1TenMinutesIn()

        playback.play(PlayerController.Request(MediaKey.Movie(2), startPositionMillis = 0))

        assertThat(playback.position()).isNull()
    }

    @Test
    fun `once found, a stream's position is the player's`() {
        watchMovie1TenMinutesIn()

        assertThat(playback.position()).isEqualTo(controller.player.currentPosition)
        assertThat(playback.position()).isAtLeast(TEN_MINUTES)
    }

    private companion object {
        const val TEN_MINUTES = 600_000L
        const val FIFTY_MINUTES = 3_000_000L
    }
}
