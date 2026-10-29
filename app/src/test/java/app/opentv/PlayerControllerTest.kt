/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Channel
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.MediaKey
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
    private val sources = PlaybackSources(test.db, XtreamApi(panel.http), StalkerApi(panel.http))
    private val players = TestPlayers(context)
    private val channels = mutableListOf<Long>()

    @Before
    fun portal() = runBlocking {
        test.db.sources().insert(STALKER_SOURCE)
        panel.replies["handshake"] = """{"js": {"token": "t"}}"""
        panel.replies["create_link"] = """{"js": {"cmd": "ffmpeg http://example.com/play.ts"}}"""
        test.db.channels().upsertAll((1..5).map(::stalkerChannel))
        channels += test.db.channels().allForMatching().map { it.id }
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
