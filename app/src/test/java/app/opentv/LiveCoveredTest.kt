/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live TV stays built under Settings, Search and title pages (#192), but its guide stops listening
 * to the EPG while one covers it, so a guide sync started from Settings doesn't regroup the day's
 * listings and rebuild the rows behind it (#327). Through the real [KeptShell] and [LiveScreen].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LiveCoveredTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Midday on a fixed date, so today's guide runs from now until well after the programmes written. */
    private val now = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, java.util.Calendar.JUNE, 15, 12, 0)
    }.timeInMillis

    /** The start of tomorrow on this device, where the guide's next day starts. */
    private val tomorrow = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    private val graph = ServiceLocator.Graph(app, clock = { now })
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.channels(app, graph) }
    private lateinit var nav: NavHostController

    @After
    fun close() {
        graph.settings.lastChannelId = 0L
        graph.settings.setGuidePreviewVideo(true)
        viewModels.clear()
        graph.database.close()
    }

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = "10",
        logoUrl = null,
        epgChannelId = "epg$id",
        number = id.toInt(),
        streamUrl = "https://example.com/$id.ts",
    )

    /** Three channels, each with a programme on now. */
    private fun library() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.categories().upsertAll(listOf(Category("10", 1, "News", StreamKind.LIVE, sortIndex = 0)))
        db.channels().replaceCatalogue(
            1,
            listOf(channel(1, "Channel One"), channel(2, "Channel Two"), channel(3, "Channel Three")),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
        feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().insertOrReplace((1L..3L).map { programme(it, now - HOUR, now + HOUR, "Now on $it") })
    }

    private var feed = 0L

    private fun programme(channel: Long, start: Long, end: Long, title: String) = Programme(
        feedId = feed,
        epgChannelId = "epg$channel",
        startUtcMillis = start,
        endUtcMillis = end,
        title = title,
    )

    /** Live TV as the main screen, with Settings to open over it. */
    private fun setApp() {
        graph.settings.setGuidePreviewVideo(false)
        compose.setTvContent {
            nav = rememberNavController()
            KeptShell(
                nav,
                shell = {
                    LiveScreen(
                        isTelevision = true,
                        hasSources = true,
                        isSyncing = false,
                        onPlayChannel = {},
                        onAddSource = {},
                        onRefresh = {},
                        takeFocus = true,
                        viewModel = viewModel,
                    )
                },
            ) {
                NavHost(nav, startDestination = Routes.MAIN) {
                    mainShellRoute()
                    composable(Routes.SETTINGS) { StandIn("Settings") }
                }
            }
        }
    }

    /** A screen that takes the highlight as it opens, as every screen does. */
    @Composable
    private fun StandIn(name: String) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            first.requestFocus()
        }
        Column(Modifier.fillMaxSize().background(SetteeColors.Background)) {
            Box(Modifier.focusRequester(first).testTag("$name option").focusable()) { Text(name, SetteeType.Body) }
        }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitRows() {
        compose.waitUntil(10_000) {
            idleMainLooper()
            viewModel.guideRows.value.rows.any { it.programmes.isNotEmpty() }
        }
        compose.waitForIdle()
    }

    private fun channelCell(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun liveShown() = runCatching { channelCell("Channel Two").assertIsDisplayed() }.isSuccess

    private fun rowsList(title: String) =
        viewModel.guideRows.value.rows.any { row -> row.programmes.any { it.title == title } }

    /** Opens Settings over Live TV and waits until it has finished covering it. */
    private fun openSettings() {
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("Settings option") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) { !liveShown() }
    }

    /** Back as the remote sends it, through the activity. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `listings written while Settings covers the guide wait for Back, which finds the guide on its channel`() {
        library()
        setApp()
        awaitRows()
        channelCell("Channel Two").requestFocus()
        compose.waitForIdle()
        channelCell("Channel Two").assertIsFocused()

        openSettings()
        letTheGuideStop()
        runBlocking {
            graph.database.programmes().insertOrReplace(listOf(programme(2, now + 2 * HOUR, now + 3 * HOUR, NEWS)))
            // The same settled listings the guide reads, so the EPG's settle has passed once this has
            // them; the guide's own pause between builds ran out long before, from its last build.
            withTimeout(10_000) {
                graph.epgRepository.observeGuideWindow(now, now + DAY).first { list -> list.any { it.title == NEWS } }
            }
        }

        // A guide still listening builds its three rows from them now: give it time to.
        val until = System.currentTimeMillis() + BUILD_MILLIS
        while (System.currentTimeMillis() < until) {
            idleMainLooper()
            assertThat(rowsList(NEWS)).isFalse()
            Thread.sleep(POLL_MILLIS)
        }

        back()
        compose.waitUntil(10_000) {
            idleMainLooper()
            rowsList(NEWS)
        }
        compose.waitForIdle()

        channelCell("Channel Two").assertIsFocused()
    }

    @Test
    fun `back from Settings over a later day, the guide shows today and none of that day's listings`() {
        library()
        runBlocking {
            graph.database.programmes().insertOrReplace(listOf(programme(1, tomorrow, tomorrow + HOUR, TOMORROW)))
        }
        setApp()
        awaitRows()
        compose.runOnIdle { viewModel.nudgeGuideDay(1) }
        compose.waitUntil(10_000) {
            idleMainLooper()
            rowsList(TOMORROW)
        }
        compose.waitForIdle()
        compose.onNode(hasText(TOMORROW)).assertIsDisplayed()

        openSettings()
        letTheGuideStop()
        back()

        // Whether or not today's rows were built before the guide stopped, it draws today.
        compose.onNode(hasText("Today")).assertIsDisplayed()
        compose.onNode(hasText(TOMORROW)).assertDoesNotExist()
        compose.waitUntil(10_000) {
            idleMainLooper()
            !rowsList(TOMORROW)
        }
        compose.waitForIdle()
        compose.onNode(hasText("Today")).assertIsDisplayed()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(0)
    }

    /**
     * Past when nothing collecting them stops the guide's flows: the rows five seconds after the screen
     * stops, and the programme index under them five seconds after that (WhileSubscribed).
     */
    private fun letTheGuideStop() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(STOP_SECONDS))

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val NEWS = "News flash"
        const val DAY = 24 * HOUR
        const val STOP_SECONDS = 11L
        const val BUILD_MILLIS = 1_500L
        const val TOMORROW = "Tomorrow's news"
        const val POLL_MILLIS = 100L
    }
}
