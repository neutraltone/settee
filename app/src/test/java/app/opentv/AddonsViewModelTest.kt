/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.core.ViewerError
import app.opentv.ui.settings.AddonsViewModel
import com.google.common.truth.Truth.assertThat
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adding an add-on in Settings › Add-ons, against a network that can't find the add-on's host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddonsViewModelTest {

    private val offline = OkHttpClient.Builder()
        .addInterceptor { throw UnknownHostException("example.com") }
        .build()

    @get:Rule
    val testGraph = TestGraph(name = "addons-view-model-test", http = offline)

    private val app = testGraph.app
    private val graph = testGraph.graph

    @Test
    fun `an add-on that can't be reached says so, rather than that the address is wrong`() {
        val addons = testGraph.viewModel { AddonsViewModel(app, graph) }

        addons.add("https://example.com/addon/manifest.json")
        testGraph.awaitIdle()
        val failed = addons.status.value

        assertThat((failed as AddonsViewModel.Status.Failed).error).isEqualTo(
            ViewerError("Can't reach the add-on. Check this device is online and the address is right.", "UnknownHost"),
        )
        assertThat(graph.settings.stremioAddons.value).isEmpty()
    }
}
