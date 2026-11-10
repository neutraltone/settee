/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.player.DecoderSize
import app.opentv.ui.HomeViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's Live Preview plays what this device can, even when the Quality last settled on is too big (#184). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LivePreviewQualityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private suspend fun previewAfterSettlingOn(settledOn: String, tallest: Int): String? {
        val decoders = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(tallest * 16 / 9, tallest))
        val graph = ServiceLocator.Graph(app, decoders = decoders)
        val db = graph.database
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
        graph.settings.setLivePreviewOnHome(true)
        graph.viewerLibrary.live.record(db.channels().everyRow().single { it.name == settledOn })

        val viewModels = ViewModelsInTest()
        val state = try {
            viewModels.track(HomeViewModel(app, graph)).state.first { it?.live != null }
        } finally {
            viewModels.stopAll()
            db.close()
        }
        return state?.live?.preview?.channel?.name
    }

    @Test
    fun `a Quality too big for this device gives way to the one the Channel plays by default`() = runTest {
        assertThat(previewAfterSettlingOn("News 4K", tallest = 1088)).isEqualTo("News FHD")
    }

    @Test
    fun `a Quality this device can play stays, even when it isn't the default`() = runTest {
        assertThat(previewAfterSettlingOn("News HD", tallest = 1088)).isEqualTo("News HD")
    }

    @Test
    fun `a device that can play it keeps the Quality it settled on`() = runTest {
        assertThat(previewAfterSettlingOn("News 4K", tallest = 2160)).isEqualTo("News 4K")
    }
}
