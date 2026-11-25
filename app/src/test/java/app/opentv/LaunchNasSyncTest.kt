/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.core.StatusBus
import app.opentv.sync.LastNasSync
import app.opentv.sync.NasSync
import com.google.common.truth.Truth.assertThat
import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The NAS sync a launch runs keeps how it went, so Your devices can say it failed (#484). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LaunchNasSyncTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
    private val wording = ErrorWording(context, onTelevision = true)

    private fun lastNasSync() = LastNasSync(prefs, wording, now = { NOW })

    private suspend fun launch(store: LastNasSync, result: NasSync.Result) = FirstOpenWork(
        nasAutoSync = true,
        syncNas = { result },
        nasSynced = store::record,
        catchUpNormaliser = {},
        launchRefresh = {},
        syncingNas = "Syncing with your other devices",
    ).run()

    @After
    fun clearStatus() = StatusBus.set(null)

    @Test
    fun `a launch whose NAS sync fails leaves the failure stored, for the next time the app opens too`() = runTest {
        launch(lastNasSync(), NasSync.Result.Failed(ConnectException()))

        val stored = lastNasSync().outcome.value
        assertThat(stored?.atMillis).isEqualTo(NOW)
        assertThat(stored?.failure?.sentence).isEqualTo(context.getString(R.string.sync_nas_failed))
        assertThat(stored?.failure?.detail).isEqualTo("Connect")
    }

    @Test
    fun `a later NAS sync that works clears the stored failure`() = runTest {
        val store = lastNasSync()
        launch(store, NasSync.Result.Failed(ConnectException()))
        launch(store, NasSync.Result.Success(merged = 0, peers = 1))

        assertThat(store.outcome.value?.failure).isNull()
        assertThat(lastNasSync().outcome.value?.failure).isNull()
    }

    @Test
    fun `a stored failure is worded in the app's language when it's read, not when it happened`() = runTest {
        launch(lastNasSync(), NasSync.Result.Failed(ConnectException()))
        val locale = java.util.Locale.getDefault()
        try {
            // The language changed since: as MainActivity puts the chosen language on every context.
            val german = ErrorWording(app.opentv.core.LocaleUtils.wrap(context, "de"), onTelevision = true)

            val stored = LastNasSync(prefs, german, now = { NOW }).outcome.value

            assertThat(stored?.failure?.sentence).isEqualTo(
                "Dein NAS konnte nicht erreicht werden. Überprüfe, ob es eingeschaltet ist und die Angaben in den " +
                    "Aufnahme-Einstellungen stimmen.",
            )
            assertThat(stored?.failure?.detail).isEqualTo("Connect")
        } finally {
            java.util.Locale.setDefault(locale)
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
