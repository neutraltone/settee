/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.LineupChannel
import app.opentv.data.repo.LineupScope
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

/** What a channel's panel offers and does, against the real catalogue, guide and recordings. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChannelOptionsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val db = graph.database
    private val viewModel = ItemOptionsViewModel(app, graph)
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        val rows = listOf("UK| BBC One HD", "UK| BBC One SD").mapIndexed { i, name ->
            Channel(
                sourceId = 1,
                streamId = "$i",
                name = name,
                categoryId = "10",
                logoUrl = null,
                epgChannelId = "bbc1",
                number = null,
                streamUrl = "http://example.com/live/u/p/$i.ts",
            )
        }
        db.channels().replaceCatalogue(1, rows, syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
    }

    @After
    fun close() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun bbcOne(): LineupChannel = graph.channelLineup.channels(LineupScope.All()).first().single()

    private suspend fun subject() = OptionsSubject.OfChannel(bbcOne())

    private suspend fun guide(vararg programmes: Triple<String, Long, Long>) {
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().upsertAll(
            programmes.map { (title, start, end) ->
                Programme(
                    feedId = feed,
                    epgChannelId = "bbc1",
                    startUtcMillis = start,
                    endUtcMillis = end,
                    title = title,
                )
            },
        )
    }

    private fun onNow(title: String) = Triple(title, now - HOUR, now + HOUR)

    private fun later(title: String, hours: Int) = Triple(title, now + hours * HOUR, now + (hours + 1) * HOUR)

    @Test
    fun `a channel with no guide offers neither the series nor a later programme`() = runTest {
        val options = viewModel.optionsFor(subject())

        assertThat(options).doesNotContain(ItemOption.ScheduleLater)
        assertThat(options.filterIsInstance<ItemOption.RecordWholeSeries>()).isEmpty()
    }

    @Test
    fun `the guide is looked up, so a channel from outside the guide still offers what's on`() = runTest {
        guide(onNow("The News"), later("Film", 2))

        val options = viewModel.optionsFor(subject())

        assertThat(seriesOption(options).programme.title).isEqualTo("The News")
        assertThat(options).contains(ItemOption.ScheduleLater)
    }

    @Test
    fun `a programme that is only upcoming offers no series`() = runTest {
        guide(later("Film", 2))

        assertThat(viewModel.optionsFor(subject()).filterIsInstance<ItemOption.RecordWholeSeries>()).isEmpty()
    }

    @Test
    fun `any Quality recording offers Stop recording`() = runTest {
        val notPrimary = bbcOne().qualities.last()
        db.recordings().insert(
            Recording(
                channelId = notPrimary.id,
                sourceId = 1,
                channelName = "BBC One",
                logoUrl = null,
                title = "The News",
                filePath = "/tmp/news.ts",
                streamUrl = notPrimary.streamUrl,
                userAgent = "",
                startedAtMillis = now,
                status = RecordingStatus.RECORDING,
            ),
        )

        val options = viewModel.optionsFor(subject())

        assertThat(options).contains(ItemOption.StopRecording)
        assertThat(options).doesNotContain(ItemOption.RecordNow)
    }

    @Test
    fun `Stop recording stops every Quality that's recording`() = runTest {
        bbcOne().qualities.forEach { quality ->
            db.recordings().insert(
                Recording(
                    channelId = quality.id,
                    sourceId = 1,
                    channelName = "BBC One",
                    title = "The News",
                    filePath = "/tmp/${quality.id}.ts",
                    streamUrl = quality.streamUrl,
                    status = RecordingStatus.RECORDING,
                ),
            )
        }

        viewModel.choose(subject(), ItemOption.StopRecording).join()

        val asked = generateSequence { Shadows.shadowOf(app).nextStartedService }.toList()
        assertThat(asked).hasSize(2)
    }

    @Test
    fun `later programmes leave out what's on now, soonest first`() = runTest {
        guide(later("Late Film", 5), onNow("The News"), later("Quiz", 2))

        assertThat(
            viewModel.channels.laterProgrammes(bbcOne()).map {
                it.title
            },
        ).containsExactly("Quiz", "Late Film").inOrder()
    }

    @Test
    fun `adding a Favourite covers every Quality, and the panel then offers removing it`() = runTest {
        val subject = subject()

        viewModel.choose(subject, ItemOption.AddFavourite).join()

        assertThat(graph.channelLineup.favouriteRowIds()).containsExactlyElementsIn(
            subject.channel.qualities.map {
                it.id
            },
        )
        assertThat(viewModel.optionsFor(subject())).contains(ItemOption.RemoveFavourite)
    }

    @Test
    fun `hiding takes every Quality out of the lineup`() = runTest {
        val subject = subject()

        viewModel.choose(subject, ItemOption.HideChannel).join()

        assertThat(graph.channelLineup.channels(LineupScope.All()).first()).isEmpty()
        subject.channel.qualities.forEach { assertThat(graph.catalogRepository.channel(it.id)?.hidden).isTrue() }
    }

    @Test
    fun `Record now names the capture after what's on`() = runTest {
        guide(onNow("The News"))

        viewModel.choose(subject(), ItemOption.RecordNow).join()

        assertThat(graph.recordingRepository.observeActive().first().map { it.title }).containsExactly("The News")
    }

    @Test
    fun `Record whole series books the later airings in the guide, not only what's on now`() = runTest {
        guide(onNow("The News"), later("Film", 2), later("The News", 24))

        viewModel.choose(subject(), seriesOption(viewModel.optionsFor(subject()))).join()

        assertThat(graph.recordingRepository.scheduled().map { it.title }).containsExactly("The News")
    }

    @Test
    fun `Record whole series sets the series the panel named, even if something else is on by then`() = runTest {
        val ended = Programme(
            feedId = 1,
            epgChannelId = "bbc1",
            startUtcMillis = now - 2 * HOUR,
            endUtcMillis = now - HOUR,
            title = "The News",
        )
        guide(onNow("Quiz"), later("The News", 24))

        viewModel.choose(subject(), ItemOption.RecordWholeSeries(ended)).join()

        assertThat(graph.recordingRepository.scheduled().map { it.title }).containsExactly("The News")
        assertThat(graph.recordingRepository.observeActive().first()).isEmpty()
    }

    private fun seriesOption(options: List<ItemOption>) =
        options.filterIsInstance<ItemOption.RecordWholeSeries>().single()

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
