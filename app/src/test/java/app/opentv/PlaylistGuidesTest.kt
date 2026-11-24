/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The guides a playlist's header declares, from refreshing it to their programmes (#505). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaylistGuidesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val playlist = Source(id = 2, name = "List", kind = SourceKind.M3U, url = "http://example.com/get.php")

    @After
    fun close() = test.close()

    /** Refreshes [playlist] answering [header] as its first line, then downloads its guides. */
    private suspend fun refreshWithHeader(header: String) = run {
        test.db.sources().insert(playlist)
        test.panel.replies[""] = header + "\n" + """
            #EXTINF:-1 tvg-id="news",News
            http://example.com/news.m3u8
            #EXTINF:-1 tvg-id="films",Films
            http://example.com/films.m3u8
        """.trimIndent()
        test.catalog.fetchLive(playlist, nowUtcMillis = NOW)
        test.guide.syncAll(nowUtcMillis = NOW, force = true)
    }

    private suspend fun onNow(channel: String) = test.guide.programmeOnNow(channel, NOW + 10 * HOUR + 1)?.title

    @Test
    fun `a playlist saved with a byte-order mark fills the guide it declares`() = testing {
        test.panel.replies["one/xmltv.php"] = guide("news", "News at Ten")

        refreshWithHeader("﻿#EXTM3U url-tvg=\"$ONE\"")

        assertThat(test.db.sources().byId(playlist.id)?.epgUrl).isEqualTo(ONE)
        assertThat(onNow("news")).isEqualTo("News at Ten")
    }

    @Test
    fun `every guide a playlist lists fills the channels it covers`() = testing {
        test.panel.replies["one/xmltv.php"] = guide("news", "News at Ten")
        test.panel.replies["two/xmltv.php"] = guide("films", "Late Film")

        val summary = refreshWithHeader("#EXTM3U url-tvg=\"$ONE,$TWO\"")

        assertThat(summary.feedsFailed).isEqualTo(0)
        assertThat(onNow("news")).isEqualTo("News at Ten")
        assertThat(onNow("films")).isEqualTo("Late Film")
    }

    @Test
    fun `one broken guide in the list doesn't cost the others`() = testing {
        test.panel.replies["one/xmltv.php"] = "!404"
        test.panel.replies["two/xmltv.php"] = guide("films", "Late Film")

        val summary = refreshWithHeader("#EXTM3U url-tvg=\"$ONE, $TWO\"")

        assertThat(summary.feedsFailed).isEqualTo(0)
        assertThat(onNow("films")).isEqualTo("Late Film")
    }

    @Test
    fun `a guide that's down keeps the programmes it gave before, beside the others' new ones`() = testing {
        test.panel.replies["one/xmltv.php"] = guide("news", "News at Ten")
        test.panel.replies["two/xmltv.php"] = guide("films", "Late Film")
        refreshWithHeader("#EXTM3U url-tvg=\"$ONE,$TWO\"")

        test.panel.replies["one/xmltv.php"] = "!503"
        test.panel.replies["two/xmltv.php"] = guide("films", "Later Film")
        test.guide.syncAll(nowUtcMillis = NOW, force = true)

        assertThat(onNow("news")).isEqualTo("News at Ten")
        assertThat(onNow("films")).isEqualTo("Later Film")
    }

    @Test
    fun `when every guide in the list fails the guide says why`() = testing {
        test.panel.replies["one/xmltv.php"] = "!404"
        test.panel.replies["two/xmltv.php"] = "!503"

        val summary = refreshWithHeader("#EXTM3U url-tvg=\"$ONE,$TWO\"")

        assertThat(summary.feedsFailed).isEqualTo(1)
    }

    @Test
    fun `a guide address typed for an Xtream login is used whole, commas and all`() {
        // Only a playlist lists several guides: a viewer's own address is one, whatever it holds.
        val typed = "$ONE, $TWO"
        val login = Source(id = 3, name = "Panel", kind = SourceKind.XTREAM, url = "http://example.com", epgUrl = typed)

        val addresses = XtreamApi(FakePanel().http).epgAddresses(login)

        assertThat(addresses.map { it.toString() }).containsExactly(typed.toHttpUrl().toString())
    }

    private companion object {
        const val HOUR = 3_600_000L

        /** 2026-01-01. */
        const val NOW = 1_767_225_600_000L

        const val ONE = "http://example.com/xmltv.php?username=one"
        const val TWO = "http://example.org/xmltv.php?username=two"

        fun guide(channel: String, title: String) =
            """<?xml version="1.0"?><tv><programme start="20260101100000 +0000" """ +
                """stop="20260101110000 +0000" channel="$channel"><title>$title</title></programme></tv>"""
    }
}
