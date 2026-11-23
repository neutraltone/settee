/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Canaries for TestGraph (#616): each reproduces one of the races the suite used to lose now and
 * then, on purpose and every run, so a change that breaks the waiting fails here every time.
 */

/** The races a screen's test ran into, in real time. */
@OptIn(FlowPreview::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
class TestGraphTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private val db = testGraph.database

    private fun aChannel() = runBlocking {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        val channel = Channel(
            sourceId = 1,
            streamId = "1",
            name = "BBC One",
            categoryId = null,
            logoUrl = null,
            epgChannelId = null,
            number = 1,
            streamUrl = "http://example.com/live/u/p/1.ts",
        )
        db.channels().replaceCatalogue(1, listOf(channel), syncStamp = 1)
    }

    @Test
    fun `with a Room read held the screen shows it loading, and once released the next check sees the rows`() {
        aChannel()
        testGraph.hold()
        compose.setTvContent {
            val names by remember { db.channels().observeEvery().map { all -> all.joinToString { it.name } } }
                .collectAsState(null)
            BasicText(names ?: "Loading")
        }
        compose.onNodeWithText("Loading").assertExists()

        testGraph.release()

        compose.onNodeWithText("BBC One").assertExists()
    }

    @Test
    fun `an assertion straight after a Room-backed screen appears sees what its background work made of it`() {
        aChannel()
        val graph = testGraph.graph
        compose.setTvContent {
            val names by remember {
                db.sources().observeAll()
                    // Settled first, as the title index waits for a burst of writes to end.
                    .debounce(SETTLE_MILLIS)
                    .map { all -> slowly { all.joinToString { it.name } } }
                    .flowOn(graph.dispatchers.default)
            }.collectAsState("")
            BasicText(names)
        }

        compose.onNodeWithText("Provider").assertExists()
    }

    @Test
    fun `work a test leaves running is stopped before the next test starts`() {
        val earlier = TestGraph(name = "earlier-test")
        lateinit var loop: Job
        lateinit var viewModelWork: Job

        earlier.around {
            val earlierDb = earlier.database
            loop = CoroutineScope(earlier.graph.dispatchers.io).launch {
                while (true) {
                    earlierDb.sources().all()
                    delay(10)
                }
            }
            viewModelWork = earlier.viewModel { LeftRunning(earlier.app, earlier.graph) }
                .viewModelScope.coroutineContext.job
        }

        assertThat(loop.isCompleted).isTrue()
        assertThat(viewModelWork.isCompleted).isTrue()
    }

    /** Reads on and on, as a screen's view model does, until it's stopped. */
    class LeftRunning(app: Application, graph: ServiceLocator.Graph) : AndroidViewModel(app) {
        init {
            viewModelScope.launch(graph.dispatchers.io) {
                while (true) {
                    graph.database.sources().all()
                    delay(10)
                }
            }
        }
    }

    private companion object {
        // Never counted down: awaiting it is work that takes SLOW_MILLIS, standing in for a slow CPU.
        private val never = CountDownLatch(1)

        fun <T> slowly(work: () -> T): T {
            never.await(SLOW_MILLIS, TimeUnit.MILLISECONDS)
            return work()
        }

        const val SLOW_MILLIS = 300L
        const val SETTLE_MILLIS = 500L
    }
}

/** The race a view model's test ran into, when its virtual time skipped ahead of a real thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TestGraphVirtualTimeTest {

    @get:Rule
    val testGraph = TestGraph(virtualTime = true)

    private val db = testGraph.database

    @Test
    fun `virtual time doesn't jump ahead while a database read is in progress`() = testGraph.runTest {
        db.sources().insert(Source(id = 1, name = "Provider", kind = SourceKind.XTREAM, url = "http://example.com"))
        var readAt = -1L

        launch {
            db.sources().all()
            readAt = currentTime
        }
        launch { delay(1_000) }
        advanceUntilIdle()

        assertThat(readAt).isEqualTo(0)
    }
}

/** Runs [block] as a test of its own, through this graph: set up, then torn down. */
private fun TestGraph.around(block: () -> Unit) = apply(
    object : Statement() {
        override fun evaluate() = block()
    },
    Description.EMPTY,
).evaluate()
