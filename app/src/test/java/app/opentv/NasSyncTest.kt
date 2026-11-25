/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.recording.SmbConfig
import app.opentv.sync.NasFiles
import app.opentv.sync.NasSync
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A NAS sync that couldn't read another device's file says so, rather than that none have synced (#485). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NasSyncTest {

    @get:Rule
    val testGraph = TestGraph(name = "nas-sync-test")

    private val graph get() = testGraph.graph

    /** The sync folder as the NAS holds it, by file name. */
    private class FakeNas(val files: MutableMap<String, String>) : NasFiles {
        override fun list(config: SmbConfig, folder: String): List<String> = files.keys.toList()

        override fun readText(config: SmbConfig, locator: String): String =
            files.getValue(locator.substringAfterLast('/'))

        override fun writeText(config: SmbConfig, locator: String, text: String) {
            files[locator.substringAfterLast('/')] = text
        }
    }

    private fun syncWith(nas: FakeNas): NasSync.Result {
        graph.settings.setSmbConfig("nas.example.com", "share", "", "u", "p")
        return runBlocking { NasSync(graph, nas).sync() }
    }

    @Test
    fun `another device's file that isn't a sync bundle is counted as unreadable, with its cause`() {
        val result = syncWith(FakeNas(mutableMapOf("other-device.json" to "{ half written")))

        assertThat(result).isInstanceOf(NasSync.Result.Success::class.java)
        result as NasSync.Result.Success
        assertThat(result.peers).isEqualTo(0)
        assertThat(result.unreadable).isEqualTo(1)
        assertThat(result.unreadableCause).isNotNull()
    }

    @Test
    fun `Your devices says another device's file couldn't be read, not that no other device has synced`() {
        val result = syncWith(FakeNas(mutableMapOf("other-device.json" to "{ half written")))

        val failure = graph.lastNasSync.record(result)

        assertThat(failure?.sentence).isEqualTo(
            "Couldn't read what another device saved on your NAS. Sync again in a moment, or update Settee on it.",
        )
        assertThat(failure?.detail).isNotEmpty()
        assertThat(graph.lastNasSync.outcome.value?.failure).isEqualTo(failure)
    }

    @Test
    fun `the sentence counts every device whose file couldn't be read`() {
        val result = syncWith(FakeNas(mutableMapOf("tv.json" to "not json", "phone.json" to "[]")))

        assertThat(graph.lastNasSync.record(result)?.sentence).startsWith(
            "Couldn't read what 2 other devices saved on your NAS.",
        )
    }

    @Test
    fun `a sync whose other devices' files all read says nothing went wrong`() {
        val nas = FakeNas(mutableMapOf())
        syncWith(nas)
        // This device's own bundle, under another device's name, is a readable peer file.
        nas.files["other-device.json"] = nas.files.values.single()

        val result = syncWith(nas)

        assertThat((result as NasSync.Result.Success).unreadable).isEqualTo(0)
        assertThat(result.peers).isEqualTo(1)
        assertThat(graph.lastNasSync.record(result)).isNull()
    }
}
