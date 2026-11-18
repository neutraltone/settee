/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.RecordingStatus
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
    private val fixture = RecordingFixture(context)
    private val recordings = fixture.recordings
    private val engine = fixture.engine
    private val bbcOne = fixture.bbcOne

    @Before
    fun emptyRecordingsFolder() {
        RecordingStorage.internalDir(context).listFiles()?.forEach { it.delete() }
    }

    @After
    fun close() = fixture.close()

    @Test
    fun `recording a channel twice in one minute keeps both recordings`() = runTest {
        val first = engine.startChannel(bbcOne)
        engine.openSink(recordings.byId(first)!!).apply { output.write("first".toByteArray()) }.close()
        recordings.setStatus(first, RecordingStatus.COMPLETED)
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
        val first = recordings.byId(engine.startChannel(bbcOne))!!
        recordings.setStatus(first.id, RecordingStatus.COMPLETED)
        val second = recordings.byId(engine.startChannel(bbcOne))!!
        recordings.setStatus(second.id, RecordingStatus.COMPLETED)
        recordings.setFilePath(first.id, file.path)
        recordings.setFilePath(second.id, file.path)

        engine.delete(recordings.byId(first.id)!!)

        assertThat(recordings.byId(first.id)).isNull()
        assertThat(file.exists()).isTrue()

        engine.delete(recordings.byId(second.id)!!)

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
}
