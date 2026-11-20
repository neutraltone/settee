/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ViewerError
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.diagnostics.OpenKind
import app.opentv.diagnostics.PlaybackDiagnostics
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the players tell Diagnostics about opens and errors (#586). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaybackDiagnosticsTest {

    private val players = TestPlayers(ApplicationProvider.getApplicationContext<Context>())
    private val seen = SeenDiagnostics()

    @Before
    fun tell() {
        players.diagnostics = seen
    }

    @After
    fun close() = players.close()

    @Test
    fun `a film's open is timed from asking to play to its first frame`() {
        val controller = players.controller(resolve = {
            delay(2_000)
            film(it)
        })

        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.until { seen.opens.isNotEmpty() }

        val open = seen.opens.single()
        assertThat(open.kind).isEqualTo(OpenKind.Film)
        // From the ask, so the 2 s spent getting its link counts; the fake player's loading adds the rest.
        assertThat(open.millis).isAtLeast(2_000L)
        assertThat(open.failure).isNull()
    }

    @Test
    fun `a late frame from the last stream doesn't end the next one's open`() {
        val link = CompletableDeferred<Unit>()
        val controller = players.controller(resolve = {
            link.await()
            film(it)
        })
        players.hanging = true

        controller.play(PlayerController.Request(MediaKey.Episode(1)), debounce = false)
        players.advance(1_000)
        lastStreamsFrame(controller)
        link.complete(Unit)
        players.until { controller.player.mediaItemCount == 1 }
        lastStreamsFrame(controller)
        players.advance(1_000)

        assertThat(seen.opens).isEmpty()
    }

    @Test
    fun `a film that keeps failing is one failed open, and every error it showed is told`() {
        val controller = players.controller(failing = 100, resolve = { film(it) })

        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.untilItStopsRestarting(controller)

        val shown = (controller.state.value as PlayerController.State.Error).reason
        assertThat(seen.opens.map { it.kind to it.failure }).containsExactly(OpenKind.Film to shown.detail)
        assertThat(seen.errors).hasSize(TestPlayers.AUTO_TRIES)
        assertThat(seen.errors.last()).isEqualTo(shown)
    }

    @Test
    fun `a stream that fails once and comes back by itself is one failed open, not two opens`() {
        val controller = players.controller(failing = 1, resolve = { film(it) })

        controller.play(PlayerController.Request(MediaKey.Movie(1)), debounce = false)
        players.until { controller.state.value is PlayerController.State.Playing }
        players.advance(1_000)

        assertThat(seen.opens.map { it.kind to (it.failure != null) }).containsExactly(OpenKind.Film to true)
    }

    @Test
    fun `a link that can't be had is a failed open too`() {
        val controller = players.controller(resolve = { Playable.Failed(it, Playable.Failure.LINK_REFUSED) })

        controller.play(PlayerController.Request(MediaKey.Episode(1)), debounce = false)
        players.until { controller.state.value is PlayerController.State.Error }

        assertThat(seen.opens.map { it.kind }).containsExactly(OpenKind.Episode)
        assertThat(seen.errors).hasSize(1)
    }

    /** What the player reports when the stream it just stopped shows a frame anyway. */
    private fun lastStreamsFrame(controller: PlayerController) =
        controller.player.analyticsCollector.onRenderedFirstFrame(Any(), 0L)

    private fun film(key: MediaKey) =
        Playable.Ready(key, "https://example.com/film", userAgent = "", title = "Film", isLive = false)

    private class SeenDiagnostics : PlaybackDiagnostics {
        data class Open(val kind: OpenKind, val millis: Long, val failure: String?)

        val opens = mutableListOf<Open>()
        val errors = mutableListOf<ViewerError>()

        override fun opened(kind: OpenKind, millis: Long, failure: ViewerError?) {
            opens += Open(kind, millis, failure?.detail)
        }

        override fun errorShown(error: ViewerError) {
            errors += error
        }
    }
}
