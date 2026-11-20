/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerError
import app.opentv.diagnostics.DiagnosticsLog
import app.opentv.diagnostics.OpenKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The last opens and errors, kept on the device for Diagnostics (#586). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsLogTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private var now = 1_700_000_000_000L

    private fun TestScope.log() = DiagnosticsLog(folder.root, this, dispatcher) { now }

    @Test
    fun `an error the player showed is still there after the app restarts`() = runTest(dispatcher) {
        log().errorShown(ViewerError("This channel isn't available right now.", "IO bad HTTP status · HTTP 404"))
        advanceUntilIdle()

        val errors = log().errors()

        assertThat(errors.map { it.detail }).containsExactly("IO bad HTTP status · HTTP 404")
        assertThat(errors.single().atUtcMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `only the last 20 errors are kept, however many there are`() = runTest(dispatcher) {
        val log = log()
        repeat(25) { log.errorShown(ViewerError("Failed.", "HTTP ${500 + it}")) }
        advanceUntilIdle()

        val details = log().errors().map { it.detail }

        assertThat(details).hasSize(20)
        assertThat(details.first()).isEqualTo("HTTP 505")
        assertThat(details.last()).isEqualTo("HTTP 524")
    }

    @Test
    fun `one stream failing again and again takes one entry, counted, not all twenty`() = runTest(dispatcher) {
        val log = log()
        val failing = ViewerError("The connection dropped.", "IO network connection failed")
        log.errorShown(ViewerError("Earlier.", "HTTP 500"))
        repeat(5) {
            log.errorShown(failing)
            now += 10_000
        }
        advanceUntilIdle()

        val errors = log().errors()

        assertThat(errors.map { it.detail to it.count })
            .containsExactly("HTTP 500" to 1, "IO network connection failed" to 5).inOrder()
        assertThat(errors.last().atUtcMillis).isEqualTo(1_700_000_040_000L)
    }

    @Test
    fun `the same error long after the last is a new entry`() = runTest(dispatcher) {
        val log = log()
        val failing = ViewerError("The connection dropped.", "IO network connection failed")
        log.errorShown(failing)
        now += 60 * 60_000
        log.errorShown(failing)
        advanceUntilIdle()

        assertThat(log().errors().map { it.count }).containsExactly(1, 1)
    }

    @Test
    fun `an open is kept with what it was and how long it took, a failed one with why`() = runTest(dispatcher) {
        val log = log()
        log.opened(OpenKind.Episode, millis = 2_400, failure = null)
        log.opened(
            OpenKind.AddonStream,
            millis = 9_000,
            failure = ViewerError("Failed.", "Source error · http://tv.example.com/x.mkv"),
        )
        advanceUntilIdle()

        val opens = log().opens()

        assertThat(opens.map { Triple(it.kind, it.millis, it.failure) }).containsExactly(
            Triple(OpenKind.Episode, 2_400L, null),
            Triple(OpenKind.AddonStream, 9_000L, "Source error · [address]"),
        ).inOrder()
    }

    @Test
    fun `only the last 20 opens are kept`() = runTest(dispatcher) {
        val log = log()
        repeat(25) { log.opened(OpenKind.Film, millis = it.toLong(), failure = null) }
        advanceUntilIdle()

        assertThat(log().opens().map { it.millis }).isEqualTo((5L..24L).toList())
    }

    @Test
    fun `a damaged file is started afresh rather than crashing the player that records into it`() =
        runTest(dispatcher) {
            folder.root.resolve("errors.json").writeText("{ not json")

            log().errorShown(ViewerError("Failed.", "HTTP 500"))
            advanceUntilIdle()

            assertThat(log().errors().map { it.detail }).containsExactly("HTTP 500")
        }
}
