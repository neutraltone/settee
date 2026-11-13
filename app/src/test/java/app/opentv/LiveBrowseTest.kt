/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.visibleTabs
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Live TV on the remote: where the Highlight goes, the Categories Sidebar, the preview band and days (#164). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LiveBrowseTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * Midday on a fixed date, on this device's clock. Today's page of the guide ends at local
     * midnight, so a guide built from the real clock put "Later on" into tomorrow, and "Now on"
     * into yesterday, whenever the tests ran within two hours of it.
     */
    private val now = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, java.util.Calendar.JUNE, 15, 12, 0)
    }.timeInMillis

    private val graph = ServiceLocator.Graph(app, clock = { now })
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.channels(app, graph) }
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    /** The start of tomorrow on this device, which is where the guide's next day starts. */
    private val tomorrow = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    @After
    fun close() {
        graph.settings.lastChannelId = 0L
        graph.settings.setGuidePreviewVideo(true)
        viewModels.clear()
        graph.database.close()
    }

    private fun channel(id: Long, name: String, source: Long, category: String) = Channel(
        id = id,
        sourceId = source,
        streamId = "$id",
        name = name,
        categoryId = category,
        logoUrl = null,
        epgChannelId = "epg$id",
        number = id.toInt(),
        streamUrl = "https://example.com/$id.ts",
    )

    /** News from the first provider, Sport from the second, and what's on each channel now and next. */
    private fun library() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.sources().insert(Source(id = 2, name = "Provider B", kind = SourceKind.XTREAM, url = "https://example.org"))
        db.categories().upsertAll(
            listOf(
                Category("10", 1, "News", StreamKind.LIVE, sortIndex = 0),
                Category("20", 2, "Sport", StreamKind.LIVE, sortIndex = 0),
            ),
        )
        db.channels().replaceCatalogue(
            1,
            listOf(
                channel(1, "Channel One", 1, "10"),
                channel(2, "Channel Two", 1, "10"),
                channel(3, "Channel Three", 1, "10"),
            ),
            syncStamp = 1,
        )
        db.channels().replaceCatalogue(2, listOf(channel(4, "Sport Four", 2, "20")), syncStamp = 1)
        graph.catalogRepository.renormalizeAll()
        guide()
    }

    /** Each channel's programmes: on now, later today, and two tomorrow and one the day after. */
    private suspend fun guide() {
        val db = graph.database
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().insertOrReplace(
            (1L..4L).flatMap { id ->
                listOf(
                    Programme(
                        feedId = feed,
                        epgChannelId = "epg$id",
                        startUtcMillis = now - HOUR,
                        endUtcMillis =
                        now + HOUR,
                        title = "Now on $id",
                        description = "What's on $id now.",
                    ),
                    Programme(
                        feedId = feed,
                        epgChannelId = "epg$id",
                        startUtcMillis = now + HOUR,
                        endUtcMillis =
                        now + 2 * HOUR,
                        title = "Later on $id",
                    ),
                    Programme(
                        feedId = feed,
                        epgChannelId = "epg$id",
                        startUtcMillis = tomorrow + 9 * HOUR,
                        endUtcMillis =
                        tomorrow + 10 * HOUR,
                        title = "Morning on $id",
                    ),
                    Programme(
                        feedId = feed,
                        epgChannelId = "epg$id",
                        startUtcMillis = tomorrow + 20 * HOUR,
                        endUtcMillis =
                        tomorrow + 21 * HOUR,
                        title = "Evening on $id",
                    ),
                    Programme(
                        feedId = feed,
                        epgChannelId = "epg$id",
                        startUtcMillis = tomorrow + DAY + 9 * HOUR,
                        endUtcMillis =
                        tomorrow + DAY + 10 * HOUR,
                        title = "Day after on $id",
                    ),
                )
            },
        )
    }

    /** What the rail has open: Home until OK on Live TV opens it, as from the app's first screen. */
    private var tab by mutableStateOf(Tab.HOME)

    /** The rail with Home open and its Highlight on Live TV in the rail. */
    private fun setLive(isSyncing: Boolean = false) {
        graph.settings.setGuidePreviewVideo(false)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = tab,
                onSelect = { tab = it },
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { open, takeFocus, _ ->
                view = LocalView.current
                if (open == Tab.LIVE) {
                    LiveScreen(
                        isTelevision = true,
                        hasSources = true,
                        isSyncing = isSyncing,
                        onPlayChannel = {},
                        onAddSource = {},
                        onRefresh = {},
                        takeFocus = takeFocus,
                        viewModel = viewModel,
                    )
                }
            }
        }
        railItem("Home").requestFocus()
        compose.waitForIdle()
        press(Key.DirectionDown)
    }

    private fun awaitRows() {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.guideRows.value.rows.isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun pressOkOnLiveInTheRail() {
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** A channel's cell in the guide's first column, rather than the band's heading of the same name. */
    private fun channelCell(name: String) = compose.onNode(hasText(name) and hasClickAction())

    private fun programmeCell(title: String) = compose.onNode(hasText(title) and hasClickAction())

    @Test
    fun `with no last-watched channel, OK on Live TV lands on the first channel of All channels`() {
        library()
        setLive()

        pressOkOnLiveInTheRail()
        awaitRows()

        channelCell("Channel One").assertIsFocused()
        captureScreen("live_first_highlight")
    }

    @Test
    fun `Left from the first column opens the sidebar on All channels, with every provider's categories under it`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()

        press(Key.DirectionLeft)

        compose.onNode(hasText("All channels") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Favourites") and hasClickAction()).assertExists()
        compose.onNode(hasText("News") and hasClickAction()).assertExists()
        compose.onNode(hasText("Sport") and hasClickAction()).assertExists()
        compose.onNode(hasText("Provider A")).assertDoesNotExist()
        captureScreen("live_sidebar")
    }

    @Test
    fun `Left from a programme moves along the row, with the sidebar still tucked away`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionRight)
        programmeCell("Now on 1").assertIsFocused()

        press(Key.DirectionLeft)

        channelCell("Channel One").assertIsFocused()
        compose.onNode(hasText("All channels")).assertDoesNotExist()
    }

    @Test
    fun `Right from the sidebar goes back to the channel and programme the viewer left, and closes it`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionDown)
        channelCell("Channel Three").assertIsFocused()

        press(Key.DirectionLeft)
        press(Key.DirectionRight)

        channelCell("Channel Three").assertIsFocused()
        compose.onNode(hasText("All channels")).assertDoesNotExist()
    }

    private fun railItem(label: String) = compose.onNode(
        (hasContentDescription(label) or hasText(label)) and hasClickAction(),
    )

    @Test
    fun `while the rows load, the Highlight stays on Live TV in the rail, then moves once to the channel`() {
        setLive(isSyncing = true)
        pressOkOnLiveInTheRail()
        // Longer than the rail gives a tab that takes the Highlight itself.
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        railItem("Live TV").assertIsFocused()
        captureScreen("live_loading")

        graph.settings.lastChannelId = 0L
        library()
        awaitRows()
        compose.waitForIdle()

        channelCell("Channel One").assertIsFocused()
    }

    @Test
    fun `a press made before the rows arrive wins, and nothing moves when they do`() {
        setLive(isSyncing = true)
        pressOkOnLiveInTheRail()
        press(Key.DirectionDown)
        railItem("Movies").assertIsFocused()

        library()
        awaitRows()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()

        railItem("Movies").assertIsFocused()
    }

    @Test
    fun `OK on Live TV lands on the channel last watched`() {
        library()
        graph.settings.lastChannelId = 3L
        setLive()

        pressOkOnLiveInTheRail()
        awaitRows()

        channelCell("Channel Three").assertIsFocused()
    }

    @Test
    fun `nothing in the preview band takes the Highlight, so Up from the first channel stays put`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()

        press(Key.DirectionUp)

        channelCell("Channel One").assertIsFocused()
        compose.onNode(hasText("What's on 1 now.")).assertExists()
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val text = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
            assertThat(text.joinToString()).doesNotContain("What's on")
        }
    }

    /**
     * The guide on tomorrow's page with the Highlight on Channel One's evening programme, its last
     * that day. Today's page runs a day on from now, into tomorrow, so it starts from tomorrow's.
     */
    private fun onTomorrowsLastProgramme() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        viewModel.nudgeGuideDay(1)
        compose.waitUntilAtLeastOneExists(programme("Evening on 1"), 5_000)
        programmeCell("Evening on 1").requestFocus()
        compose.waitForIdle()
    }

    private fun programme(title: String) = hasText(title) and hasClickAction()

    @Test
    fun `Right past a row's last programme moves to the next day, on that row's first programme`() {
        onTomorrowsLastProgramme()

        press(Key.DirectionRight)
        compose.waitUntilAtLeastOneExists(programme("Day after on 1"), 5_000)
        compose.waitForIdle()

        programmeCell("Day after on 1").assertIsFocused()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(2)
        captureScreen("live_next_day")
    }

    @Test
    fun `Left before a later day's first programme goes back a day, onto that row's last programme`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.waitUntilAtLeastOneExists(programme("Day after on 1"), 5_000)
        compose.waitForIdle()

        press(Key.DirectionLeft)
        compose.waitUntilAtLeastOneExists(programme("Evening on 1"), 5_000)
        compose.waitForIdle()

        programmeCell("Evening on 1").assertIsFocused()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(1)
    }

    @Test
    fun `picking Favourites with no Favourites says how to add one, and keeps the Highlight on it`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionLeft)
        press(Key.DirectionUp)

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("No favourites yet"), 5_000)

        compose.onNode(hasText("Favourites") and hasClickAction()).assertIsFocused()
        captureScreen("live_no_favourites")
    }

    @Test
    fun `with no Favourites, Right reaches Choose a category, which opens the sidebar again`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionLeft)
        press(Key.DirectionUp)
        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("No favourites yet"), 5_000)

        press(Key.DirectionRight)
        compose.onNode(hasText("Choose a category") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)

        compose.onNode(hasText("Favourites") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `a category whose channels are all hidden says so, rather than loading, and offers the sidebar`() {
        library()
        runBlocking { graph.database.channels().setHidden(4, true) }
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()

        viewModel.selectCategory(viewModel.visibleCategoryGroups.value.first { it.label == "Sport" }.key)
        compose.waitUntilAtLeastOneExists(hasText("Nothing to show here"), 5_000)

        compose.onNode(hasText("Loading channels", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Choose a category") and hasClickAction()).assertExists()
        captureScreen("live_empty_category")
    }

    @Test
    fun `the day's landing happens once, so a row coming back later doesn't take the Highlight`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.waitUntilAtLeastOneExists(programme("Day after on 1"), 5_000)
        compose.waitForIdle()
        press(Key.DirectionDown, times = 3)
        programmeCell("Day after on 4").assertIsFocused()

        val sport = viewModel.visibleCategoryGroups.value.first { it.label == "Sport" }.key
        viewModel.selectCategory(sport)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Channel One")).fetchSemanticsNodes().isEmpty() }
        viewModel.selectCategory(null)
        compose.waitUntilAtLeastOneExists(hasText("Channel One") and hasClickAction(), 5_000)
        compose.waitForIdle()

        programmeCell("Day after on 4").assertIsFocused()
    }

    @Test
    fun `paging to a day with nothing on a row lands on its channel, and Right from there goes on a day`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.waitUntilAtLeastOneExists(programme("Day after on 1"), 5_000)
        compose.waitForIdle()

        press(Key.DirectionRight)
        compose.waitUntil(5_000) { viewModel.guideDayOffset.value == 3 }
        compose.waitUntilDoesNotExist(programme("Day after on 1"), 5_000)
        compose.waitForIdle()
        channelCell("Channel One").assertIsFocused()

        press(Key.DirectionRight)
        compose.waitUntil(5_000) { viewModel.guideDayOffset.value == 4 }
        compose.waitForIdle()
        channelCell("Channel One").assertIsFocused()
    }

    @Test
    fun `leaving Live TV and coming back opens the guide on today`() {
        onTomorrowsLastProgramme()

        tab = Tab.HOME
        compose.waitForIdle()
        tab = Tab.LIVE
        compose.waitForIdle()

        assertThat(viewModel.guideDayOffset.value).isEqualTo(0)
    }

    /** News with forty more channels, so the guide scrolls well past its first screen. */
    private fun longNews() = runBlocking {
        graph.database.channels().replaceCatalogue(
            1,
            listOf(
                channel(1, "Channel One", 1, "10"),
                channel(2, "Channel Two", 1, "10"),
                channel(3, "Channel Three", 1, "10"),
            ) + (10L..49L).map { channel(it, "Extra $it", 1, "10") },
            syncStamp = 2,
        )
        graph.catalogRepository.renormalizeAll()
    }

    @Test
    fun `picking a category starts at its first channel, even from far down a channel that's in it`() {
        library()
        longNews()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionDown, times = 30)
        channelCell("Extra 37").assertIsFocused()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        compose.onNode(hasText("News") and hasClickAction()).assertIsFocused()

        press(Key.DirectionCenter)
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.guideRows.value.rows.none { it.primary.name == "Sport Four" }
        }
        compose.waitForIdle()
        press(Key.DirectionRight)

        channelCell("Channel One").assertIsFocused()
    }

    @Test
    fun `Left from a channel on a later day goes back to now, and Left again opens the sidebar`() {
        onTomorrowsLastProgramme()
        channelCell("Channel One").requestFocus()
        compose.waitForIdle()

        press(Key.DirectionLeft)
        compose.waitUntil(5_000) { viewModel.guideDayOffset.value == 0 }
        compose.waitUntilAtLeastOneExists(programme("Now on 1"), 5_000)
        compose.waitForIdle()
        channelCell("Channel One").assertIsFocused()
        compose.onNode(hasText("All channels")).assertDoesNotExist()

        press(Key.DirectionLeft)
        compose.onNode(hasText("All channels") and hasClickAction()).assertIsFocused()
    }

    @Test
    fun `picking a category shows only its channels, and Right moves into them`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionLeft)
        press(Key.DirectionDown, times = 2)
        compose.onNode(hasText("Sport") and hasClickAction()).assertIsFocused()

        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Channel One")).fetchSemanticsNodes().isEmpty() }
        press(Key.DirectionRight)

        channelCell("Sport Four").assertIsFocused()
        compose.onNode(hasText("Sport")).assertDoesNotExist()
    }

    @Test
    fun `Find a category narrows the sidebar, counting only categories`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionLeft)
        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(device.highlighted()).isTrue()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.type("spo")

        compose.onNode(hasText("Sport") and hasClickAction()).assertExists()
        compose.onNode(hasText("News") and hasClickAction()).assertDoesNotExist()
        compose.onNode(hasText("1 of 2")).assertExists()
        captureScreen("live_find_category")
        device.close()
    }

    @Test
    fun `OK on a programme opens its details, and Back comes back to that programme`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        press(Key.DirectionRight, times = 2)

        press(Key.DirectionCenter)
        compose.waitUntilAtLeastOneExists(hasText("Schedule recording") and hasClickAction(), 5_000)
        compose.onNode(hasText("Remind me") and hasClickAction()).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()

        compose.onNode(hasText("Schedule recording")).assertDoesNotExist()
        programmeCell("Later on 1").assertIsFocused()
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
