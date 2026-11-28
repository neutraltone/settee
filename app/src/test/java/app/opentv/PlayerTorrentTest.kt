/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ReportedSource
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.player.PlayerController
import app.opentv.player.torrent.FakeTorrentEngine
import app.opentv.player.torrent.TorrentFailedException
import app.opentv.player.torrent.TorrentFailure
import app.opentv.player.torrent.TorrentRequest
import app.opentv.player.torrent.TorrentStatus
import app.opentv.player.torrent.uri
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A torrent runs only while it plays (#670): the player starts the engine on it, and leaving the player,
 * or playing something else, stops it and deletes its pieces.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerTorrentTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val players = TestPlayers(context)
    private val engine = FakeTorrentEngine(ByteArray(64))
    private val film = MediaKey.AddonStream(MediaKey.Movie(1), TORRENT.uri, addonName = "Films")
    private val link = MediaKey.AddonStream(MediaKey.Movie(1), "https://example.com/film.mkv", addonName = "Films")

    @After
    fun close() = players.close()

    @Test
    fun `playing a torrent starts the engine on it, and the player shows it connecting`() {
        val controller = players.controller(resolve = ::resolve, torrentEngine = engine)

        controller.play(PlayerController.Request(film), debounce = false)
        players.until { players.played.isNotEmpty() }
        engine.findPeers(4)

        assertThat(engine.started).containsExactly(TORRENT)
        assertThat(players.played).containsExactly(TORRENT.uri)
        assertThat(controller.torrent.value).isEqualTo(TorrentStatus.Connecting(peers = 4))
        controller.release()
    }

    @Test
    fun `leaving the player stops the torrent and deletes its pieces`() {
        val controller = players.controller(resolve = ::resolve, torrentEngine = engine)
        controller.play(PlayerController.Request(film), debounce = false)
        players.until { players.played.isNotEmpty() }

        controller.release()

        assertThat(engine.status.value).isEqualTo(TorrentStatus.Idle)
        assertThat(engine.deletions).isEqualTo(1)
    }

    @Test
    fun `playing a link after a torrent stops the torrent`() {
        val controller = players.controller(resolve = ::resolve, torrentEngine = engine)
        controller.play(PlayerController.Request(film), debounce = false)
        players.until { players.played.isNotEmpty() }

        controller.play(PlayerController.Request(link), debounce = false)
        players.until { players.played.size == 2 }

        assertThat(engine.deletions).isEqualTo(1)
        assertThat(engine.started).containsExactly(TORRENT)
        controller.release()
        assertThat(engine.deletions).isEqualTo(1)
    }

    @Test
    fun `a player that never played a torrent leaves the engine alone`() {
        val controller = players.controller(resolve = ::resolve, torrentEngine = engine)
        engine.start(TORRENT)
        controller.play(PlayerController.Request(link), debounce = false)
        players.until { players.played.isNotEmpty() }

        controller.release()

        assertThat(engine.status.value).isNotEqualTo(TorrentStatus.Idle)
    }

    @Test
    fun `a torrent nobody is sharing says so, without restarting, and Try again starts it afresh`() {
        players.failure = { TorrentFailedException(TorrentFailure.NOBODY_SHARING, "0 peers") }
        val controller = players.controller(failing = 1, resolve = ::resolve, torrentEngine = engine)
        controller.play(PlayerController.Request(film), debounce = false)

        players.until { controller.state.value is PlayerController.State.Error }
        engine.fail(TorrentFailure.NOBODY_SHARING, "0 peers")

        val error = controller.state.value as PlayerController.State.Error
        assertThat(error.reason.sentence).isEqualTo(
            "Nobody is sharing this torrent right now, so it can't play. Pick another stream.",
        )
        assertThat(error.reason.detail).contains("NobodySharing")
        assertThat(error.anotherStreamOf).isEqualTo(MediaKey.Movie(1))
        players.advance(5_000)
        assertThat(players.played).hasSize(1)

        controller.retry()
        players.until { players.played.size == 2 }
        assertThat(engine.started).containsExactly(TORRENT, TORRENT)
        controller.release()
    }

    private fun resolve(key: MediaKey): Playable {
        val url = (key as MediaKey.AddonStream).url
        return Playable.Ready(
            key,
            url,
            userAgent = "",
            title = "Film",
            isLive = false,
            source = ReportedSource.STREMIO,
            torrent = TORRENT.takeIf { url == TORRENT.uri },
        )
    }

    private companion object {
        val TORRENT = TorrentRequest("0123456789abcdef0123456789abcdef01234567", fileIndex = 1)
    }
}
