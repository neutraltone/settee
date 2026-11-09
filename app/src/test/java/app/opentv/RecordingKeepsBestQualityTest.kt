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
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.player.DecoderSize
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.ItemOptionsViewModel
import app.opentv.ui.options.OptionsSubject
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * A Channel plays the highest Quality this device can, but what it records, and what it hands another
 * app, is its best: nothing is decoded here while recording, and these decoders don't limit another app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingKeepsBestQualityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(
        app,
        decoders = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088)),
    )
    private val db = graph.database
    private val viewModel = ItemOptionsViewModel(app, graph)
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        val rows = listOf("News 4K", "News HD").mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = "10",
                logoUrl = null,
                epgChannelId = "news",
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().upsertAll(
            listOf(now - HOUR / 2, now + HOUR).map { start ->
                Programme(
                    feedId = feed,
                    epgChannelId = "news",
                    startUtcMillis = start,
                    endUtcMillis = start + HOUR,
                    title = "The News",
                )
            },
        )
    }

    @After
    fun close() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun news(): LineupChannel = graph.channelLineup.channels(LineupScope.All()).first().single()

    @Test
    fun `Record now records the best Quality while the Channel plays its next one`() = runTest {
        val news = news()
        assertThat(news.primary.name).isEqualTo("News HD")

        viewModel.choose(OptionsSubject.OfChannel(news), ItemOption.RecordNow).join()

        val recording = graph.recordingRepository.observeActive().first().single()
        assertThat(recording.channelId).isEqualTo(news.qualities.first { it.name == "News 4K" }.id)
    }

    @Test
    fun `a series rule is set on the best Quality`() = runTest {
        val news = news()
        val series = viewModel.optionsFor(OptionsSubject.OfChannel(news))
            .filterIsInstance<ItemOption.RecordWholeSeries>().single()

        viewModel.choose(OptionsSubject.OfChannel(news), series).join()

        assertThat(db.seriesRules().enabled().map { it.channelId })
            .containsExactly(news.qualities.first { it.name == "News 4K" }.id)
    }

    @Test
    fun `another app is handed the best Quality`() = runTest {
        viewModel.channels.openInExternalPlayer(app, news())

        val chooser = Shadows.shadowOf(app).nextStartedActivity
        val view = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)
        assertThat(view?.data.toString()).endsWith("/0.ts")
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
