/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.EpgRepository
import app.opentv.data.repo.RecordingRepository
import app.opentv.data.repo.SourceRepository
import app.opentv.recording.RecordingEngine
import app.opentv.recording.RecordingStorage
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A recording leaves Recordings only once its file is gone, and says so when it can't be (#344). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecordingDeleteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.get(context)
    private val test = TestCatalog(context)
    private val recordings = RecordingRepository(test.db.recordings(), test.db.seriesRules())
    private val wording = ErrorWording(context, onTelevision = true)

    /** The locators the engine was asked to remove; [refusal], when set, is what removing throws. */
    private val removed = mutableListOf<String>()
    private var refusal: Exception? = null

    private val engine = RecordingEngine(
        context,
        recordings,
        SourceRepository(test.db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http), test.panel.http),
        settings,
        EpgRepository(
            programmeDao = test.db.programmes(),
            refreshDao = test.db.guideRefresh(),
            feedDao = test.db.epgFeeds(),
            aliasDao = test.db.epgAliases(),
            channelDao = test.db.channels(),
            sourceDao = test.db.sources(),
            api = XtreamApi(test.panel.http),
            http = test.panel.http,
            wording = wording,
            selfEnabledGuide = EpgRepository.SelfEnabledGuide.InMemory(),
        ),
        test.lineup,
        wording,
        removeFile = { locator ->
            refusal?.let { throw it }
            removed += locator
        },
    )

    @After
    fun close() = test.close()

    @Test
    fun `deleting a recording on this device removes its file`() {
        val file = folder.newFile("News - 2026-11-15 1800.ts").apply { writeText("ts") }

        RecordingStorage.delete(context, settings, file.absolutePath)

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a file that's already gone counts as deleted`() {
        val missing = File(folder.root, "Gone - 2026-11-15 1800.ts")

        RecordingStorage.delete(context, settings, missing.absolutePath)

        assertThat(missing.exists()).isFalse()
    }

    @Test
    fun `a file that can't be removed says so instead of passing as deleted`() {
        // A folder with something in it is a path File.delete() refuses on every platform.
        val stuck = folder.newFolder("Stuck - 2026-11-15 1800.ts").also { File(it, "inside").writeText("x") }

        assertThrows(Exception::class.java) {
            RecordingStorage.delete(context, settings, stuck.absolutePath)
        }
        assertThat(stuck.exists()).isTrue()
    }

    @Test
    fun `a recording whose file is removed leaves Recordings with nothing to say`() = testing {
        val id = recordings.insert(finished("/rec/News.ts"))

        val error = engine.delete(recordings.byId(id)!!)

        assertThat(error).isNull()
        assertThat(removed).containsExactly("/rec/News.ts")
        assertThat(recordings.byId(id)).isNull()
    }

    @Test
    fun `a recording on a NAS that can't be reached stays, and the viewer is told why`() = testing {
        val id = recordings.insert(finished("smb://nas.example.com/recordings/News.ts"))
        refusal = IOException("unreachable")

        val error = engine.delete(recordings.byId(id)!!)

        assertThat(recordings.byId(id)).isNotNull()
        assertThat(error?.sentence)
            .isEqualTo("Couldn't delete this from your NAS. Check it's on, then try deleting it again.")
        assertThat(error?.detail).isEqualTo("IO")
    }

    @Test
    fun `a recording on this device whose file won't go stays, and the viewer is told why`() = testing {
        val id = recordings.insert(finished("/rec/News.ts"))
        refusal = IOException("refused")

        val error = engine.delete(recordings.byId(id)!!)

        assertThat(recordings.byId(id)).isNotNull()
        assertThat(error?.sentence).isEqualTo("Couldn't delete this recording. Try deleting it again.")
    }

    @Test
    fun `a USB recording whose drive is unplugged stays, and the viewer is told to plug it back in`() = testing {
        val id = recordings.insert(finished("content://com.android.externalstorage.documents/document/News.ts"))
        refusal = RecordingStorage.Unavailable(R.string.rec_error_usb_unavailable)

        val error = engine.delete(recordings.byId(id)!!)

        assertThat(recordings.byId(id)).isNotNull()
        assertThat(error?.sentence)
            .isEqualTo("Can't find your USB drive. Plug it back in, then pick its folder again in Recording settings.")
        assertThat(error?.detail).isEqualTo("StorageUnavailable")
    }

    private fun finished(locator: String) = Recording(
        channelId = 1,
        sourceId = 1,
        channelName = "News",
        title = "News",
        filePath = locator,
        streamUrl = "",
        status = RecordingStatus.COMPLETED,
    )
}
