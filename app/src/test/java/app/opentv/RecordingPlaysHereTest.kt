/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.media3.common.MimeTypes
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.Playable
import app.opentv.data.repo.recordable
import app.opentv.data.repo.toRecord
import app.opentv.player.DecoderSize
import app.opentv.ui.options.ItemOption
import app.opentv.ui.options.ItemOptionsViewModel
import app.opentv.ui.options.OptionsSubject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * What's recorded is what this device can play, so a viewer can always watch what they recorded. Another
 * app is handed the Channel's best, since this device's decoders don't limit it (#184).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingPlaysHereTest {

    @get:Rule
    val testGraph = TestGraph(decoders = FakeDecoders(MimeTypes.VIDEO_H265 to DecoderSize(1920, 1088)))

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val db = graph.database
    private val viewModel = testGraph.track(ItemOptionsViewModel(app, graph))
    private val now = System.currentTimeMillis()
    private val laterStart = now + 2 * HOUR

    @Before
    fun setUp(): Unit = runBlocking {
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
        db.programmes().insertOrReplace(
            listOf(now - HOUR / 2, laterStart).map { start ->
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

    /** A test's steps on the main thread, as the options panel takes them; see [TestGraph.runOnMain]. */
    private fun testing(block: suspend CoroutineScope.() -> Unit) = testGraph.runOnMain(block)

    private suspend fun news(): LineupChannel = graph.channelLineup.channels(LineupScope.All()).first().single()

    @Test
    fun `Record now records the Quality this device plays, not one too big for it`() = testing {
        val news = news()

        viewModel.choose(OptionsSubject.OfChannel(news), ItemOption.RecordNow).join()

        val recording = graph.recordingRepository.observeActive().first().single()
        assertThat(recording.channelId).isEqualTo(news.qualities.first { it.name == "News HD" }.id)
    }

    @Test
    fun `a series rule is set on the Quality this device plays`() = testing {
        val news = news()
        val series = viewModel.optionsFor(OptionsSubject.OfChannel(news))
            .filterIsInstance<ItemOption.RecordWholeSeries>().single()

        viewModel.choose(OptionsSubject.OfChannel(news), series).join()

        assertThat(db.seriesRules().enabled().map { it.channelId })
            .containsExactly(news.qualities.first { it.name == "News HD" }.id)
    }

    @Test
    fun `moving to another provider for a clash doesn't move up to a Quality too big to play`() = testing {
        db.sources().insert(Source(id = 2, name = "Other", kind = SourceKind.XTREAM, url = "http://example.org"))
        val rows = listOf("News 4K", "News HD").mapIndexed { i, name ->
            Channel(
                sourceId = 2,
                streamId = "$i",
                name = name,
                categoryId = "10",
                logoUrl = null,
                epgChannelId = "news",
                number = null,
                streamUrl = "http://example.org/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(2, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
        val first = news().qualities.first { it.sourceId == 1L && it.name == "News HD" }
        val programme = db.programmes().startingAt("news", laterStart)!!
        // Something else already has the first provider's connection then.
        db.recordings().insert(
            Recording(
                channelId = 999,
                sourceId = 1,
                channelName = "Elsewhere",
                title = "Elsewhere",
                filePath = "/tmp/elsewhere.ts",
                streamUrl = "http://example.com/live/u/p/999.ts",
                status = RecordingStatus.SCHEDULED,
                scheduledStartMillis = programme.startUtcMillis,
                scheduledEndMillis = programme.endUtcMillis,
            ),
        )

        graph.recordingEngine.scheduleProgramme(first, programme)

        val moved = graph.recordingRepository.scheduled().single { it.title == "The News" }
        assertThat(moved.sourceId).isEqualTo(2L)
        val capture = graph.playbackSources.forRecording(MediaKey.Recording(moved.id)) as Playable.Ready
        assertThat(capture.url).contains("example.org/live/u/p/1")
    }

    @Test
    fun `what's recordable leaves out what's too big, unless that's all there is`() = testing {
        val lineup = graph.channelLineup
        val news = news()
        val hd = news.qualities.first { it.name == "News HD" }
        val uhd = news.qualities.first { it.name == "News 4K" }

        assertThat(lineup.recordable(news)).containsExactly(hd)
        assertThat(lineup.toRecord(news, playing = uhd)).isEqualTo(hd)
        assertThat(lineup.toRecord(LineupChannel(uhd, listOf(uhd)), playing = uhd)).isEqualTo(uhd)
    }

    @Test
    fun `another app is handed the best Quality`() = testing {
        viewModel.channels.openInExternalPlayer(app, news())

        val chooser = Shadows.shadowOf(app).nextStartedActivity
        val view = chooser.getParcelableExtra(android.content.Intent.EXTRA_INTENT, android.content.Intent::class.java)
        assertThat(view?.data.toString()).endsWith("/0.ts")
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
