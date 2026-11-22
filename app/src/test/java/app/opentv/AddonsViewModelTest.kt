/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.ui.settings.AddonsViewModel
import com.google.common.truth.Truth.assertThat
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adding an add-on in Settings › Add-ons, against a network that can't find the add-on's host. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonsViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val offline = OkHttpClient.Builder()
        .addInterceptor { throw UnknownHostException("example.com") }
        .build()

    private val graph = ServiceLocator.Graph(app, http = offline, settingsFile = "addons-view-model-test")

    @Before
    fun mainDispatcher() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val viewModels = ViewModelsInTest()

    @After
    fun close() {
        viewModels.stopAll()
        runBlocking { graph.stopBackgroundWork() }
        graph.database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an add-on that can't be reached says so, rather than that the address is wrong`() = runBlocking {
        val addons = viewModels.track(AddonsViewModel(app, graph))

        addons.add("https://example.com/addon/manifest.json")
        val failed = withTimeout(5_000) { addons.status.first { it is AddonsViewModel.Status.Failed } }

        assertThat((failed as AddonsViewModel.Status.Failed).error).isEqualTo(
            ViewerError("Can't reach the add-on. Check this device is online and the address is right.", "UnknownHost"),
        )
        assertThat(graph.settings.stremioAddons.value).isEmpty()
    }
}
