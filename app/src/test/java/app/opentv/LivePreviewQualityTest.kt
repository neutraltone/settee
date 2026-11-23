/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.media3.common.MimeTypes
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.player.DecoderList
import app.opentv.player.DecoderSize
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's Live Preview plays what this device can, even when the Quality last settled on is too big (#184). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePreviewQualityTest {

    /** The tallest HEVC this device decodes; each test sets it before the graph is first used. */
    private var tallest = 0

    @get:Rule
    val testGraph = TestGraph(
        decoders = DecoderList { mime ->
            FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(tallest * 16 / 9, tallest)).sizesFor(mime)
        },
    )

    private val app = testGraph.app

    private fun previewAfterSettlingOn(settledOn: String, tallest: Int): String? {
        this.tallest = tallest
        val graph = testGraph.graph
        val db = graph.database
        runBlocking {
            db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
            val rows = listOf("News 4K", "News FHD", "News HD").mapIndexed { i, name ->
                Channel(
                    sourceId = 1,
                    streamId = "$i",
                    name = name,
                    categoryId = null,
                    logoUrl = null,
                    epgChannelId = null,
                    number = null,
                    streamUrl = "http://example.com/live/u/p/$i.ts",
                )
            }
            db.channels().replaceCatalogue(1, rows, syncStamp = 1)
            graph.catalogRepository.renormalizeAll()
            graph.viewerLibrary.live.record(db.channels().everyRow().single { it.name == settledOn })
        }

        val home = testGraph.viewModel { HomeViewModel(app, graph) }
        val state = testGraph.firstWhenIdle(home.state) { it?.live != null }
        return state?.live?.preview?.channel?.name
    }

    @Test
    fun `a Quality too big for this device gives way to the one the Channel plays by default`() {
        assertThat(previewAfterSettlingOn("News 4K", tallest = 1088)).isEqualTo("News FHD")
    }

    @Test
    fun `a Quality this device can play stays, even when it isn't the default`() {
        assertThat(previewAfterSettlingOn("News HD", tallest = 1088)).isEqualTo("News HD")
    }

    @Test
    fun `a device that can play it keeps the Quality it settled on`() {
        assertThat(previewAfterSettlingOn("News 4K", tallest = 2160)).isEqualTo("News 4K")
    }
}
