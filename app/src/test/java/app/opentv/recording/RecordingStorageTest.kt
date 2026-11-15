/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two recordings never share a file (#354). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RecordingStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.get(context)
    private val dir = RecordingStorage.internalDir(context)

    @Test
    fun `opening a recording whose file is taken leaves that file alone and writes to the next free name`() {
        val taken = File(dir, "BBC One - News - 2026-11-15 1430.ts").apply { writeText("first recording") }

        val sink = RecordingStorage.openSink(context, settings, taken.absolutePath)
        sink.output.write("second".toByteArray())
        sink.close()

        assertThat(taken.readText()).isEqualTo("first recording")
        val second = File(dir, "BBC One - News - 2026-11-15 1430 (2).ts")
        assertThat(sink.resolvedLocator).isEqualTo(second.absolutePath)
        assertThat(second.readText()).isEqualTo("second")
    }

    @Test
    fun `a third recording of the same name takes the third name`() {
        File(dir, "ITV - 2026-11-15 1430.ts").writeText("1")
        File(dir, "ITV - 2026-11-15 1430 (2).ts").writeText("2")

        val sink = RecordingStorage.openSink(context, settings, File(dir, "ITV - 2026-11-15 1430.ts").absolutePath)
        sink.close()

        assertThat(sink.resolvedLocator).isEqualTo(File(dir, "ITV - 2026-11-15 1430 (3).ts").absolutePath)
    }

    @Test
    fun `a free name is written where it was planned`() {
        val planned = File(dir, "Channel 4 - 2026-11-15 1430.ts")

        val sink = RecordingStorage.openSink(context, settings, planned.absolutePath)
        sink.close()

        assertThat(sink.resolvedLocator).isNull()
        assertThat(planned.exists()).isTrue()
    }

    @Test
    fun `the NAS keeps trying names until one is free`() {
        val taken = setOf("Rec/News - 2026-11-15 1430.ts", "Rec/News - 2026-11-15 1430 (2).ts")
        val tried = mutableListOf<String>()

        val created = SmbClient.createUnique("Rec/News - 2026-11-15 1430.ts") { path ->
            tried += path
            path.takeUnless { it in taken }
        }

        assertThat(created).isEqualTo("Rec/News - 2026-11-15 1430 (3).ts")
        assertThat(tried).containsExactly(
            "Rec/News - 2026-11-15 1430.ts",
            "Rec/News - 2026-11-15 1430 (2).ts",
            "Rec/News - 2026-11-15 1430 (3).ts",
        ).inOrder()
    }
}
