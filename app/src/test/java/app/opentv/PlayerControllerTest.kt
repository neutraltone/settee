/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Loader
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.PlaybackSources
import app.opentv.player.PlayerController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player asks for a Stalker link once it knows which channel to play, and again on a restart (#98). */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerControllerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val test = TestCatalog(context)
    private val panel = test.panel
    private val sources = PlaybackSources(test.db, XtreamApi(panel.http), StalkerApi(panel.http), test.lineup)
    private val players = TestPlayers(context)
    private val channels = mutableListOf<Long>()

    @Before
    fun portal() = runBlocking {
        test.db.sources().insert(STALKER_SOURCE)
        panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        panel.replies["create_link"] = """{"js": {"cmd": "ffmpeg http://example.com/play.ts"}}"""
        test.db.channels().upsertAll((1..5).map(::stalkerChannel))
        channels += test.db.channels().everyRow().map { it.id }
    }

    @After
    fun close() {
        players.close()
        test.close()
    }

    @Test
    fun `zapping through Stalker channels asks the portal for one link, for the channel stopped on`() {
        val controller = players.controller(LIVE, resolve = sources::forWatching)

        channels.forEach { controller.play(PlayerController.Request(MediaKey.Live(it))) }
        players.until { players.played.isNotEmpty() }

        assertThat(panel.asked.count { it == "create_link" }).isEqualTo(1)
        assertThat(panel.cmds).containsExactly("ffmpeg http://localhost/ch/5")
    }

    @Test
    fun `a restart after the connection drops asks the portal for a new link`() {
        val controller = players.controller(LIVE, failing = 1, resolve = sources::forWatching)

        controller.play(PlayerController.Request(MediaKey.Live(channels.first())), debounce = false)
        players.until { players.played.size == 2 }

        assertThat(panel.asked.count { it == "create_link" }).isEqualTo(2)
    }

    @Test
    fun `a channel that keeps failing stops restarting after a few tries`() {
        val controller = players.controller(LIVE, failing = 100, resolve = sources::forWatching)

        controller.play(PlayerController.Request(MediaKey.Live(channels.first())), debounce = false)
        players.until { (controller.state.value as? PlayerController.State.Error)?.canRetry == false }
        players.advance(10_000)

        assertThat(panel.asked.count { it == "create_link" }).isEqualTo(3)
    }

    @Test
    fun `running out of memory isn't restarted on its own, but the viewer can still retry`() {
        players.failure = { Loader.UnexpectedLoaderException(OutOfMemoryError("Failed to allocate")) }
        val controller = players.controller(LIVE, failing = 100, resolve = sources::forWatching)

        controller.play(PlayerController.Request(MediaKey.Live(channels.first())), debounce = false)
        players.until { controller.state.value is PlayerController.State.Error }
        players.advance(10_000)

        val error = controller.state.value as PlayerController.State.Error
        assertThat(error.reason.sentence)
            .isEqualTo("This stream needs more memory than this device has. A different quality may work.")
        assertThat(error.reason.detail).isEqualTo("IO unspecified")
        assertThat(error.canRetry).isTrue()
        assertThat(players.played).hasSize(1)
    }

    @Test
    fun `a stream that isn't video shows its error at once, with Retry still on offer`() {
        players.failure = { ParserException.createForMalformedContainer("an HTML page, not video", null) }
        val controller = players.controller(LIVE, failing = 100, resolve = sources::forWatching)

        controller.play(PlayerController.Request(MediaKey.Live(channels.first())), debounce = false)
        players.until { controller.state.value is PlayerController.State.Error }
        players.advance(10_000)

        val error = controller.state.value as PlayerController.State.Error
        assertThat(error.canRetry).isTrue()
        assertThat(players.played).hasSize(1)
        assertThat(panel.asked.count { it == "create_link" }).isEqualTo(1)
    }

    @Test
    fun `a channel the provider gives no link for says so, with what went wrong on the detail line`() {
        val controller = players.controller(LIVE) { key -> Playable.Failed(key, Playable.Failure.LINK_REFUSED) }

        controller.play(PlayerController.Request(MediaKey.Live(channels.first())), debounce = false)
        players.until { controller.state.value is PlayerController.State.Error }

        val error = controller.state.value as PlayerController.State.Error
        assertThat(error.reason.sentence)
            .isEqualTo("Your provider didn't give a link for this channel. Try refreshing your provider in Settings.")
        assertThat(error.reason.detail).isEqualTo("LinkRefused")
    }

    private fun stalkerChannel(n: Int) = Channel(
        sourceId = STALKER_SOURCE.id,
        streamId = "$n",
        name = "Channel $n",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = n,
        streamUrl = "stalker://2/$n",
        cmd = "ffmpeg http://localhost/ch/$n",
    )
}
