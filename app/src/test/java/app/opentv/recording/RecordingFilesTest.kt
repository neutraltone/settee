/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.PANEL_SOURCE
import app.opentv.TestCatalog
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.model.Channel
import app.opentv.data.model.RecordingStatus
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two recordings never share a file, so deleting one never takes another's (#354). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecordingFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val test = TestCatalog(context)
    private val db = test.db
    private val recordings = RecordingRepository(db.recordings(), db.seriesRules())
    private val engine = RecordingEngine(
        context,
        recordings,
        SourceRepository(db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http)),
        AppSettings.get(context),
        EpgRepository(
            programmeDao = db.programmes(),
            refreshDao = db.guideRefresh(),
            feedDao = db.epgFeeds(),
            aliasDao = db.epgAliases(),
            channelDao = db.channels(),
            sourceDao = db.sources(),
            api = XtreamApi(test.panel.http),
            http = test.panel.http,
            wording = ErrorWording(context, onTelevision = false),
            selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
        ),
        test.lineup,
        ErrorWording(context, onTelevision = false),
        clock = { NOW },
    )

    private val bbcOne = Channel(
        id = 7,
        sourceId = PANEL_SOURCE.id,
        streamId = "7",
        name = "UK| BBC One HD",
        displayName = "BBC One",
        groupKey = "uk:bbcone",
        qualityRank = 200,
        categoryId = null,
        logoUrl = null,
        epgChannelId = null,
        number = null,
        streamUrl = "http://example.com/live/u/p/7.ts",
    )

    @Before
    fun emptyRecordingsFolder() {
        RecordingStorage.internalDir(context).listFiles()?.forEach { it.delete() }
    }

    @After
    fun close() = test.close()

    @Test
    fun `recording a channel twice in one minute keeps both recordings`() = runTest {
        val first = engine.startChannel(bbcOne)
        engine.openSink(recordings.byId(first)!!).apply { output.write("first".toByteArray()) }.close()
        val second = engine.startChannel(bbcOne)
        engine.openSink(recordings.byId(second)!!).apply { output.write("second".toByteArray()) }.close()

        val firstPath = recordings.byId(first)!!.filePath
        val secondPath = recordings.byId(second)!!.filePath
        assertThat(secondPath).isNotEqualTo(firstPath)
        assertThat(File(firstPath).readText()).isEqualTo("first")
        assertThat(File(secondPath).readText()).isEqualTo("second")
    }

    @Test
    fun `deleting one of two recordings of one file keeps the file until the last goes`() = runTest {
        val file = File(RecordingStorage.internalDir(context), "BBC One - 2026-11-15 1430.ts").apply { writeText("x") }
        val first = recordings.byId(engine.startChannel(bbcOne))!!.copy(status = RecordingStatus.COMPLETED)
        val second = recordings.byId(engine.startChannel(bbcOne))!!.copy(status = RecordingStatus.COMPLETED)
        recordings.setFilePath(first.id, file.path)
        recordings.setFilePath(second.id, file.path)

        engine.delete(first.copy(filePath = file.path))

        assertThat(recordings.byId(first.id)).isNull()
        assertThat(file.exists()).isTrue()

        engine.delete(second.copy(filePath = file.path))

        assertThat(recordings.byId(second.id)).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `retrying a failed recording records over its own file, leaving nothing behind`() = runTest {
        val id = engine.startChannel(bbcOne)
        engine.openSink(recordings.byId(id)!!).apply { output.write("partial".toByteArray()) }.close()
        val planned = recordings.byId(id)!!.filePath
        recordings.setStatus(id, RecordingStatus.FAILED, "stopped")

        assertThat(engine.retry(recordings.byId(id)!!)).isTrue()
        engine.openSink(recordings.byId(id)!!).apply { output.write("again".toByteArray()) }.close()

        assertThat(recordings.byId(id)!!.filePath).isEqualTo(planned)
        assertThat(RecordingStorage.internalDir(context).list()).asList().containsExactly(File(planned).name)
        assertThat(File(planned).readText()).isEqualTo("again")
    }

    private companion object {
        const val NOW = 1_763_217_000_000L
    }
}
