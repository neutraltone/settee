/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import app.opentv.core.ViewerError
import app.opentv.diagnostics.DatabaseState
import app.opentv.diagnostics.DeviceState
import app.opentv.diagnostics.DiagnosticsReport
import app.opentv.diagnostics.Gathered
import app.opentv.diagnostics.LoggedError
import app.opentv.diagnostics.LoggedOpen
import app.opentv.diagnostics.MemoryState
import app.opentv.diagnostics.OpenKind
import app.opentv.diagnostics.TableRows
import app.opentv.ui.settings.DiagnosticsPane
import app.opentv.ui.settings.DiagnosticsReportScreen
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Diagnostics, and the full report it opens (#586). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    @Test
    fun `Diagnostics sits just before About, and the remote reaches its rows`() {
        var opened = 0
        show(SettingsMemory(SettingsSection.About), onOpenDiagnostics = { opened++ })

        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        waitFor("Save a report to your NAS")
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Save a report to your NAS").assertIsFocused()
        // Gathered from the app's own database and files, not left saying it's still gathering.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Gathering…")).fetchSemanticsNodes().isEmpty() }
        listOf("Database", "Storage", "Memory", "Opens", "Errors", "This device").forEach {
            compose.onNodeWithText(it).performScrollTo()
        }
        captureScreen("settings_diagnostics")

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(opened).isEqualTo(1)
    }

    @Test
    fun `saving a report with no NAS set up says where to set one up`() {
        show(SettingsMemory(SettingsSection.Diagnostics))
        waitFor("Save a report to your NAS")
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        waitFor("Set up your NAS under Settings → Recording first, then come back here.")
    }

    @Test
    fun `the full report lists every part, with a part that couldn't be gathered said in words`() {
        compose.setTvContent {
            view = LocalView.current
            DiagnosticsReportScreen(gather = { REPORT }, onBack = {})
        }
        waitFor("Settee couldn't measure its storage.")
        compose.onNodeWithText("Done").assertIsFocused()
        captureScreen("settings_diagnostics_report")

        compose.onNodeWithText("channels: ", substring = true).performScrollTo()
        compose.onNodeWithText("Couldn't count this table.").performScrollTo()
        compose.onNodeWithText("IO network connection failed").performScrollTo()
    }

    @Test
    fun `the Opens row says when the last open failed, rather than how long it took`() {
        val failed = REPORT.copy(
            opens = Gathered.Got(listOf(LoggedOpen(1_763_644_500_000, OpenKind.Film, 9_000, "Source error"))),
        )
        compose.setTvContent {
            view = LocalView.current
            DiagnosticsPane(onOpenReport = {}, gather = { failed })
        }

        waitFor("The last failed after 9.0 s. 1 of the last 1 failed.")
    }

    private fun show(memory: SettingsMemory, onOpenDiagnostics: () -> Unit = {}) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(memory = memory, onBack = {}, onOpenDiagnostics = onOpenDiagnostics, openSubtitles = null)
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        val REPORT = DiagnosticsReport(
            database = Gathered.Got(
                DatabaseState(
                    fileBytes = 12_000_000,
                    tables = listOf(
                        TableRows("channels", 4_210),
                        TableRows(
                            "programmes",
                            null,
                            ViewerError("Couldn't count this table.", "SQLiteDatabaseCorrupt"),
                        ),
                    ),
                    handMarkedPositions = 3,
                    removedFavourites = 1,
                ),
            ),
            storage = Gathered.Failed(ViewerError("Settee couldn't measure its storage.", "IO")),
            memory = Gathered.Got(
                MemoryState(
                    heapUsedBytes = 80_000_000,
                    heapMaxBytes = 192_000_000,
                    pssBytes = 210_000_000,
                    lowMemory = false,
                ),
            ),
            opens = Gathered.Got(listOf(LoggedOpen(1_763_644_500_000, OpenKind.Episode, 2_400))),
            errors = Gathered.Got(
                listOf(
                    LoggedError(
                        1_763_644_600_000,
                        "The connection dropped.",
                        "IO network connection failed",
                        count = 4,
                    ),
                ),
            ),
            device = DeviceState("0.9.0", "9", "PS7681/4207", "AFTSS"),
        )
    }
}
