/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.repo.TitleCover
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.vod.CoverGrid
import coil.Coil
import coil.EventListener
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import coil.test.FakeImageLoaderEngine
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The covers the Movies and Shows grids fetch ahead of the screen are given up on once the grid has no
 * use for them (#264): a stale prefetch holds up the covers of the row the viewer stops on.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverGridPrefetchTest {

    @get:Rule
    val compose = createComposeRule()

    private val started = CopyOnWriteArrayList<String>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val sizes = ConcurrentHashMap<String, Size>()

    /** Every poster stays loading until it's cancelled, so nothing finishes before the test looks. */
    @Before
    fun slowPosters() {
        val engine = FakeImageLoaderEngine.Builder().default { awaitCancellation() }.build()
        Coil.setImageLoader(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext())
                .components { add(engine) }
                .eventListener(
                    object : EventListener {
                        override fun onStart(request: ImageRequest) {
                            started += request.data.toString()
                        }

                        override fun onCancel(request: ImageRequest) {
                            cancelled += request.data.toString()
                        }

                        override fun resolveSizeEnd(request: ImageRequest, size: Size) {
                            sizes[request.data.toString()] = size
                        }
                    },
                )
                .build(),
        )
    }

    @After
    fun resetCoil() = Coil.reset()

    private fun covers(name: String) =
        (1L..200L).map { TitleCover(it, "$it", "$name $it", "https://example.com/$name/$it.jpg") }

    private fun titleOf(url: String) = url.removePrefix("https://example.com/").removeSuffix(".jpg").replace('/', ' ')

    private fun numberOf(url: String) = url.substringAfterLast('/').removeSuffix(".jpg").toInt()

    private fun onScreen(url: String) =
        compose.onAllNodesWithText(titleOf(url), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * The posters asked for more than a row past the last card on screen: the ones only the prefetch
     * fetches. The lazy grid composes the next row itself, and its cards ask for their own posters.
     */
    private fun fetchedAhead(): List<String> {
        fun ahead(): List<String> {
            val last = started.filter(::onScreen).maxOfOrNull(::numberOf) ?: return emptyList()
            return started.filter { numberOf(it) > last + MORE_THAN_A_ROW }
        }
        compose.waitUntil(5_000) { ahead().isNotEmpty() }
        compose.waitForIdle()
        return ahead()
    }

    private fun assertGivenUp(urls: List<String>) {
        // Idled each time round: the test runs on the main thread, and a cancelled request only
        // reports it once its coroutine resumes there. waitUntil alone never lets the looper run.
        runCatching {
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                cancelled.containsAll(urls)
            }
        }
        assertThat(cancelled).containsAtLeastElementsIn(urls)
    }

    @Test
    fun `covers are fetched ahead at the size their cards draw them`() {
        compose.setTvContent {
            ScreenFrame { CoverGrid(covers("Film"), "Loading", "Nothing here", {}, {}, null) }
        }

        val ahead = fetchedAhead()

        // 118 x 177 dp, ArtCardSize.Portrait, at the Stick's xhdpi.
        assertThat(ahead.map { sizes[it] }.toSet()).containsExactly(Size(236, 354))
    }

    @Test
    fun `leaving the grid gives up on the covers it was fetching ahead`() {
        var open by mutableStateOf(true)
        compose.setTvContent {
            ScreenFrame { if (open) CoverGrid(covers("Film"), "Loading", "Nothing here", {}, {}, null) }
        }
        val ahead = fetchedAhead()

        open = false

        assertGivenUp(ahead)
    }

    @Test
    fun `new covers give up on the old ones it was fetching ahead`() {
        var category by mutableStateOf(covers("Film"))
        compose.setTvContent {
            ScreenFrame { CoverGrid(category, "Loading", "Nothing here", {}, {}, null) }
        }
        val ahead = fetchedAhead()

        category = covers("Show")

        assertGivenUp(ahead)
    }

    private companion object {
        /** A row at 1080p (960 dp) holds at most seven 118 dp covers and their gaps. */
        const val MORE_THAN_A_ROW = 8
    }
}
