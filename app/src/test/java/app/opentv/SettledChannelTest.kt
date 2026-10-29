/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.player.SettledChannel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SettledChannelTest {

    private fun channel(streamId: String) = Channel(
        sourceId = 1,
        streamId = streamId,
        name = "Channel $streamId",
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/$streamId.ts",
    )

    @Test
    fun `ten seconds on a channel settles on it`() = runTest {
        val settled = mutableListOf<Channel>()
        val watcher = SettledChannel(backgroundScope) { settled += it }
        val bbc = channel("101")

        watcher.tunedTo(bbc)
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(settled).containsExactly(bbc)
    }

    @Test
    fun `zapping past channels settles only on the one the viewer stays on`() = runTest {
        val settled = mutableListOf<Channel>()
        val watcher = SettledChannel(backgroundScope) { settled += it }
        val itv = channel("202")

        for (id in listOf("101", "102", "103")) {
            watcher.tunedTo(channel(id))
            advanceTimeBy(9_000)
        }
        watcher.tunedTo(itv)
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(settled).containsExactly(itv)
    }

    @Test
    fun `leaving the player before ten seconds settles on nothing`() = runTest {
        val settled = mutableListOf<Channel>()
        val watcher = SettledChannel(backgroundScope) { settled += it }

        watcher.tunedTo(channel("101"))
        advanceTimeBy(9_000)
        watcher.stopped()
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(settled).isEmpty()
    }
}
