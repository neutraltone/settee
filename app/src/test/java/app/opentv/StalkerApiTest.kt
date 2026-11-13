/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.model.Channel
import app.opentv.data.remote.StalkerApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A portal's channel list is read one channel at a time (#261). Running out of memory while reading it
 * isn't taken for a dropped session, so the list isn't downloaded again into a heap that just ran out.
 */
class StalkerApiTest {

    private val panel = FakePanel("handshake" to """{"js": {"token": "t"}}""")
    private val api = StalkerApi(panel.http)

    @Test
    fun `a channel list too big for memory is asked for once, not again after a new handshake`() = runTest {
        panel.replies["get_all_channels"] = "!oom"

        val error = runCatching { api.liveChannels(STALKER_SOURCE) }.exceptionOrNull()

        assertThat(error).isInstanceOf(OutOfMemoryError::class.java)
        assertThat(panel.asked.count { it == "get_all_channels" }).isEqualTo(1)
        assertThat(panel.asked.count { it == "handshake" }).isEqualTo(1)
    }

    @Test
    fun `a refused first call still shakes hands again and asks once more`() = runTest {
        panel.replies["get_all_channels"] = "!503"

        val channels = api.liveChannels(STALKER_SOURCE)

        assertThat(channels).isEmpty()
        assertThat(panel.asked.count { it == "get_all_channels" }).isEqualTo(2)
        assertThat(panel.asked.count { it == "handshake" }).isEqualTo(2)
    }

    @Test
    fun `a blank first reply still shakes hands again and asks once more`() = runTest {
        panel.replies["get_all_channels"] = "  "

        api.liveChannels(STALKER_SOURCE)

        assertThat(panel.asked.count { it == "get_all_channels" }).isEqualTo(2)
        assertThat(panel.asked.count { it == "handshake" }).isEqualTo(2)
    }

    @Test
    fun `channels under js data are read with everything they carry`() = runTest {
        panel.replies["get_all_channels"] = """{"js": {"total_items": 3, "data": $CHANNELS, "cur_page": 0}}"""

        assertThat(api.liveChannels(STALKER_SOURCE)).containsExactlyElementsIn(EXPECTED).inOrder()
        assertThat(panel.asked.count { it == "get_all_channels" }).isEqualTo(1)
    }

    @Test
    fun `channels sent as a bare array under js are read the same`() = runTest {
        panel.replies["get_all_channels"] = """{"js": $CHANNELS}"""

        assertThat(api.liveChannels(STALKER_SOURCE)).containsExactlyElementsIn(EXPECTED).inOrder()
    }

    private companion object {
        /** A numbered channel, one with no number, and entries a portal sends that aren't channels. */
        const val CHANNELS = """[
            {"id": "9", "name": "News", "number": "101", "tv_genre_id": "3", "logo": "/logo/9.png",
             "xmltv_id": "news.uk", "cmd": "ffmpeg http://localhost/ch/9"},
            "not a channel",
            {"name": "No id"},
            {"id": 12, "name": "Films", "logo": "http://example.com/12.png", "xmltv_id": "", "cmd": "auto /ch/12"}
        ]"""

        val EXPECTED = listOf(
            Channel(
                sourceId = STALKER_SOURCE.id,
                streamId = "9",
                name = "News",
                categoryId = "3",
                logoUrl = "http://example.com:80/logo/9.png",
                epgChannelId = "news.uk",
                number = 101,
                streamUrl = "stalker://2/9",
                cmd = "ffmpeg http://localhost/ch/9",
                sortIndex = 101,
            ),
            Channel(
                sourceId = STALKER_SOURCE.id,
                streamId = "12",
                name = "Films",
                categoryId = null,
                logoUrl = "http://example.com/12.png",
                epgChannelId = null,
                number = null,
                streamUrl = "stalker://2/12",
                cmd = "auto /ch/12",
                // Its place in the list, counting the entries that weren't channels.
                sortIndex = 3,
            ),
        )
    }
}
