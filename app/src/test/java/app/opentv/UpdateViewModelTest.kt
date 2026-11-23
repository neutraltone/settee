/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.core.content.edit
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.update.UpdateChecker
import app.opentv.update.UpdateHub
import app.opentv.update.UpdateUiState
import app.opentv.update.UpdateViewModel
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Installing an update kills the app, and a recording running in it with it (#336), so Install asks
 * first while one is running. Whether the download started is told by whether the update's APK was
 * asked for; this fake server answers everything with a 404, so a started download goes on to fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpdateViewModelTest {

    private val asked: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        asked += chain.request().url.toString()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()
    }.build()

    @get:Rule
    val testGraph = TestGraph(http = http)

    private val app = testGraph.app
    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { UpdateViewModel(app, graph) } }

    private val update = UpdateChecker.Update(
        versionName = "0.5.0",
        title = "Settee 0.5.0",
        notes = null,
        apkUrl = "https://example.com/settee.apk",
        apkSizeBytes = 1000,
        releaseUrl = "https://example.com/release",
    )

    @After
    fun close() {
        UpdateHub.state.value = UpdateUiState.Idle
    }

    private fun recording(title: String) = runBlocking {
        graph.recordingRepository.insert(
            Recording(
                channelId = 1,
                sourceId = 1,
                channelName = "BBC One",
                title = title,
                filePath = "/tmp/$title.ts",
                streamUrl = "https://example.com/1",
                startedAtMillis = System.currentTimeMillis(),
                status = RecordingStatus.RECORDING,
            ),
        )
    }

    private fun downloadStarted() = asked.any { it == update.apkUrl }

    private fun downloads() = asked.count { it == update.apkUrl }

    private fun stateBecomes(predicate: (UpdateUiState) -> Boolean): UpdateUiState =
        testGraph.firstWhenIdle(viewModel.state, predicate)

    @Test
    fun `with a recording running, Install asks first and doesn't download`() = runBlocking {
        recording("The News")
        UpdateHub.state.value = UpdateUiState.Available(update)

        viewModel.install()

        val asking = stateBecomes { it is UpdateUiState.RecordingRunning }
        assertThat((asking as UpdateUiState.RecordingRunning).programme).isEqualTo("The News")
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isFalse()
    }

    @Test
    fun `with two recordings running, the question names neither`() = runBlocking {
        recording("The News")
        recording("Newsnight")
        UpdateHub.state.value = UpdateUiState.Available(update)

        viewModel.install()

        val asking = stateBecomes { it is UpdateUiState.RecordingRunning }
        assertThat((asking as UpdateUiState.RecordingRunning).programme).isNull()
    }

    @Test
    fun `Try again after a failed download asks first too`() = runBlocking {
        recording("The News")
        UpdateHub.state.value = UpdateUiState.Failed(update, "timeout")

        viewModel.install()

        stateBecomes { it is UpdateUiState.RecordingRunning }
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isFalse()
    }

    @Test
    fun `Install anyway downloads the update`() = runBlocking {
        recording("The News")
        UpdateHub.state.value = UpdateUiState.Available(update)
        viewModel.install()
        stateBecomes { it is UpdateUiState.RecordingRunning }

        viewModel.installAnyway()

        stateBecomes { it is UpdateUiState.Failed }
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isTrue()
    }

    @Test
    fun `with nothing recording, Install downloads straight away`() = runBlocking {
        UpdateHub.state.value = UpdateUiState.Available(update)

        viewModel.install()

        stateBecomes { it is UpdateUiState.Failed }
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isTrue()
    }

    @Test
    fun `Later closes the question without downloading`() = runBlocking {
        recording("The News")
        UpdateHub.state.value = UpdateUiState.Available(update)
        viewModel.install()
        stateBecomes { it is UpdateUiState.RecordingRunning }

        viewModel.dismiss()

        assertThat(viewModel.state.value).isEqualTo(UpdateUiState.Idle)
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isFalse()
    }

    @Test
    fun `with a database Settee can't open, Install still downloads the update that may fix it`() = runBlocking {
        // Its own graph, which the close after each test would fail on, on the test graph's threads.
        val broken = ServiceLocator.Graph(
            app,
            http = http,
            dispatchers = graph.dispatchers,
            openDatabase = { error("won't open") },
        )
        val viewModel = testGraph.track(UpdateViewModel(app, broken))
        UpdateHub.state.value = UpdateUiState.Available(update)

        viewModel.install()

        testGraph.firstWhenIdle(viewModel.state) { it is UpdateUiState.Failed }
        assertWithMessage("whether the update's APK was asked for").that(downloadStarted()).isTrue()
    }

    @Test
    fun `pressing Install twice downloads the update once`() = runBlocking {
        UpdateHub.state.value = UpdateUiState.Available(update)

        viewModel.install()
        viewModel.install()

        stateBecomes { it is UpdateUiState.Failed }
        // A second download would follow the first closely: settled, it would have been asked for.
        testGraph.awaitIdle()
        assertThat(downloads()).isEqualTo(1)
    }

    @Test
    fun `after Later on the warning, the next launch looks for the update again`() = runBlocking {
        app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).edit {
            putLong("last_update_check", System.currentTimeMillis())
        }
        recording("The News")
        UpdateHub.state.value = UpdateUiState.Available(update)
        viewModel.install()
        stateBecomes { it is UpdateUiState.RecordingRunning }

        viewModel.dismiss()
        asked.clear()
        // The next launch: a view model of its own, which looks for an update as it starts.
        testGraph.track(UpdateViewModel(app, graph))

        testGraph.awaitIdle()
        assertWithMessage("whether GitHub was asked for the latest release")
            .that(asked.any { it != update.apkUrl })
            .isTrue()
    }
}
