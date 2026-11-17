/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.CatalogueRefresh
import app.opentv.data.work.SyncWorker
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/**
 * Settings › Providers says how each provider's last Catalogue Refresh went, and runs the refresh
 * itself (#464).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsProvidersRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val secrets = FixedServerSecrets()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = ServiceLocator.get(app())

    private fun app() = ApplicationProvider.getApplicationContext<Application>()

    private fun show(memory: SettingsMemory = SettingsMemory()) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(memory = memory, onBack = {}, openSubtitles = null)
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    /** A provider nothing will reach: port 9 refuses at once. */
    private fun saveProvider(name: String, failure: ViewerError? = null): Long = runBlocking {
        graph.sourceRepository.save(
            Source(
                name = name,
                kind = SourceKind.M3U,
                url = "http://127.0.0.1:9/list.m3u",
                lastRefreshFailure = failure?.stored(),
            ),
        )
    }

    @Test
    fun `a provider whose last refresh failed says why under its row, with Refresh again`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        saveProvider("Fine films on 464")
        saveProvider("Locked films on 464", ViewerError(app().getString(R.string.error_login), "LoginRejected"))
        show()
        waitFor("Locked films on 464")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Locked films on 464").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        highlighted().assertTextContains("Refresh again")
        compose.onNodeWithText(app().getString(R.string.error_login)).assertExists()
        compose.onNodeWithText("LoginRejected").assertExists()
        captureScreen("settings_providers_refresh_failed")
        // Under its own row: the Highlight came straight down from it.
        press(KeyEvent.KEYCODE_DPAD_UP)
        highlighted().assertTextContains("Locked films on 464")

        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        val manual = WorkManager.getInstance(app()).getWorkInfosForUniqueWork(SyncWorker.MANUAL_WORK_NAME).get()
        assertThat(manual).isNotEmpty()
    }

    @Test
    fun `the refresh row is under the providers, reads Refreshing while one runs, then says how it went`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app())
        // Answers nothing until it's let go, so the refresh is still running while the row is looked at.
        val silent = ServerSocket(0)
        val letGo = CountDownLatch(1)
        thread { silent.accept().use { letGo.await(10, TimeUnit.SECONDS) } }
        runBlocking {
            graph.sourceRepository.save(
                Source(
                    name = "Slow films on 464",
                    kind = SourceKind.M3U,
                    url = "http://127.0.0.1:${silent.localPort}/a.m3u",
                ),
            )
        }
        show()
        waitFor("Slow films on 464")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val refreshRow = "Fetch your providers' lists again"
        compose.onNodeWithText(refreshRow).performScrollTo().requestFocus()
        press(KeyEvent.KEYCODE_DPAD_UP)
        highlighted().assertTextContains("Add a provider")
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(WorkManager.getInstance(app()).getWorkInfosForUniqueWork(SyncWorker.MANUAL_WORK_NAME).get())
            .isNotEmpty()
        assertThat(ShadowToast.shownToastCount()).isEqualTo(0)

        // As the work it queued would: the test's WorkManager doesn't run it.
        val refreshing = thread { runBlocking { graph.catalogueRefresh.refresh(CatalogueRefresh.Trigger.Manual) } }
        waitFor("Refreshing…")
        highlighted().assertTextContains(refreshRow)
        letGo.countDown()
        refreshing.join(20_000)
        silent.close()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("See Providers", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        highlighted().assertTextContains(refreshRow)
    }

    @Test
    fun `Display and playback has no refresh row, since it's under the providers`() {
        show(SettingsMemory(SettingsSection.Display))

        compose.onNode(hasText("Fetch your providers' lists again")).assertDoesNotExist()
    }
}
