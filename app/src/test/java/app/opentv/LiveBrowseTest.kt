/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.channels.LiveScreen
import app.opentv.ui.visibleTabs
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Live TV on the remote: where the Highlight goes, the Categories Sidebar, the preview band and days (#164). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveBrowseTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * Midday on a fixed date, on this device's clock. Today's page of the guide ends at local
     * midnight, so a guide built from the real clock put "Later on" into tomorrow, and "Now on"
     * into yesterday, whenever the tests ran within two hours of it. A test may move it on, to
     * see what the guide shows once time has passed since it was built.
     */
    private var now = java.util.Calendar.getInstance().apply {
        clear()
        set(2026, java.util.Calendar.JUNE, 15, 12, 0)
    }.timeInMillis

    /** This device's zone, which a test may set to see the guide's days around a clock change. */
    private val zone = MutableStateFlow(ZoneId.systemDefault())

    @get:Rule
    val testGraph = TestGraph(compose, clock = { now }, zone = zone)

    private val graph = testGraph.graph
    private val viewModel by lazy { testGraph.viewModel { ChannelsViewModel(app, graph) } }
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
    private fun library(extra: (feed: Long) -> List<Programme> = { emptyList() }) = runBlocking {
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
        guide(extra)
    }

    /**
     * Each channel's programmes: on now, later today, and two tomorrow and one the day after, plus
     * whatever [extra] lists from the feed.
     */
    private suspend fun guide(extra: (feed: Long) -> List<Programme>) {
        val db = graph.database
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().insertOrReplace(
            (1L..4L).flatMap { id ->
                listOf(
                    listing(feed, id, now - HOUR..now + HOUR, "Now on $id", "What's on $id now."),
                    listing(feed, id, now + HOUR..now + 2 * HOUR, "Later on $id", "What's on $id later."),
                    listing(feed, id, tomorrow + 9 * HOUR..tomorrow + 10 * HOUR, "Morning on $id"),
                    listing(feed, id, tomorrow + 20 * HOUR..tomorrow + 21 * HOUR, "Evening on $id"),
                    listing(feed, id, tomorrow + DAY + 9 * HOUR..tomorrow + DAY + 10 * HOUR, "Day after on $id"),
                )
            } + extra(feed),
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

    /**
     * Waits for the guide's rows with their listings. The guide builds its channels as soon as they're
     * read, before the listings are (`guideBuilds`), so rows alone can still have no programme cells:
     * a Right pressed then stays on the channel, and the presses after it land a cell short.
     */
    private fun awaitRows() {
        compose.waitForIdle()
        assertThat(viewModel.guideRows.value.rows.any { it.programmes.isNotEmpty() }).isTrue()
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
        awaitText("What's on 1 now.")
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
        awaitText("What's on 1 now.")
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
        awaitText("What's on 1 now.")
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val text = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
            assertThat(text.joinToString()).doesNotContain("What's on")
        }
    }

    @Test
    fun `the preview band shows the synopsis of the programme the Highlight is on`() {
        library()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()

        press(Key.DirectionRight, times = 2)

        programmeCell("Later on 1").assertIsFocused()
        awaitText("What's on 1 later.")
        compose.onNode(hasText("What's on 1 now.")).assertDoesNotExist()
        captureScreen("live_later_programme")
    }

    /** Waits for text that's read from the database after the Highlight lands, such as a synopsis. */
    private fun awaitText(text: String) {
        compose.waitForIdle()
        // Past the band's wait for the Highlight to settle (SYNOPSIS_SETTLE_MILLIS), on the test's clock.
        compose.mainClock.advanceTimeBy(BAND_SETTLE_MILLIS)
        compose.onAllNodes(hasText(text)).onFirst().assertExists()
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
        compose.onAllNodes(programme("Evening on 1")).onFirst().assertExists()
        programmeCell("Evening on 1").requestFocus()
        compose.waitForIdle()
    }

    private fun programme(title: String) = hasText(title) and hasClickAction()

    @Test
    fun `Right past a row's last programme moves to the next day, on that row's first programme`() {
        onTomorrowsLastProgramme()

        press(Key.DirectionRight)

        assertOnTheDayAftersFirstProgramme()
        captureScreen("live_next_day")
    }

    @Test
    fun `two quick Rights past a row's last programme move one day, the channel highlighted while it loads`() {
        onTomorrowsLastProgramme()

        // Both straight into the window, with nothing composed in between.
        compose.runOnIdle { repeat(2) { view.press(KeyEvent.KEYCODE_DPAD_RIGHT) } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        channelCell("Channel One").assertIsFocused()
        captureScreen("guide_next_day_loading")
        compose.mainClock.autoAdvance = true

        assertOnTheDayAftersFirstProgramme()
    }

    @Test
    fun `holding Right from a row's last programme moves one day, and stops at that day's end`() {
        onTomorrowsLastProgramme()

        // The key going down and the remote repeating it, with nothing composed in between.
        compose.runOnIdle {
            view.press(KeyEvent.KEYCODE_DPAD_RIGHT, release = false).press(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1)
        }
        assertOnTheDayAftersFirstProgramme()

        compose.runOnIdle { view.press(KeyEvent.KEYCODE_DPAD_RIGHT, repeatCount = 1) }
        assertOnTheDayAftersFirstProgramme()
    }

    @Test
    fun `Left back to now before the next day lands leaves the Highlight on the channel, and Right pages again`() {
        onTomorrowsLastProgramme()

        compose.runOnIdle { view.press(KeyEvent.KEYCODE_DPAD_RIGHT).press(KeyEvent.KEYCODE_DPAD_LEFT) }
        compose.onAllNodes(programme("Now on 1")).onFirst().assertExists()
        compose.waitForIdle()
        channelCell("Channel One").assertIsFocused()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(0)

        press(Key.DirectionRight, times = 3)
        assertThat(viewModel.guideDayOffset.value).isEqualTo(1)
    }

    private fun assertOnTheDayAftersFirstProgramme() {
        compose.onAllNodes(programme("Day after on 1")).onFirst().assertExists()
        compose.waitForIdle()
        programmeCell("Day after on 1").assertIsFocused()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(2)
    }

    @Test
    fun `Left before a later day's first programme goes back a day, onto that row's last programme`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.onAllNodes(programme("Day after on 1")).onFirst().assertExists()
        compose.waitForIdle()

        press(Key.DirectionLeft)
        compose.onAllNodes(programme("Evening on 1")).onFirst().assertExists()
        compose.waitForIdle()

        programmeCell("Evening on 1").assertIsFocused()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(1)
    }

    @Test
    fun `two days on from the Saturday the clocks go back, the guide shows Monday from midnight, labelled Monday`() {
        zone.value = ZoneId.of("Europe/London")
        fun local(at: String) = LocalDateTime.parse(at).atZone(zone.value).toInstant().toEpochMilli()
        now = local("2025-10-25T12:00")
        library { feed ->
            listOf(
                listing(feed, 1, local("2025-10-26T23:00")..local("2025-10-27T00:00"), "Sunday's last on 1"),
                listing(feed, 1, local("2025-10-27T00:00")..local("2025-10-27T01:00"), "Monday's first on 1"),
            )
        }
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()

        viewModel.nudgeGuideDay(2)

        compose.onAllNodes(programme("Monday's first on 1")).onFirst().assertExists()
        compose.onAllNodes(hasText("Mon 27 Oct")).onFirst().assertExists()
        compose.onAllNodes(programme("Sunday's last on 1")).assertCountEquals(0)
        captureScreen("guide_later_day_clock_change")
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
        compose.onAllNodes(hasText("No favourites yet")).onFirst().assertExists()

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
        compose.onAllNodes(hasText("No favourites yet")).onFirst().assertExists()

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
        compose.onAllNodes(hasText("Nothing to show here")).onFirst().assertExists()

        compose.onNode(hasText("Loading channels", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Choose a category") and hasClickAction()).assertExists()
        captureScreen("live_empty_category")
    }

    @Test
    fun `the day's landing happens once, so a row coming back later doesn't take the Highlight`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.onAllNodes(programme("Day after on 1")).onFirst().assertExists()
        compose.waitForIdle()
        press(Key.DirectionDown, times = 3)
        programmeCell("Day after on 4").assertIsFocused()

        val sport = viewModel.visibleCategoryGroups.value.first { it.label == "Sport" }.key
        viewModel.selectCategory(sport)
        compose.onAllNodes(hasText("Channel One")).assertCountEquals(0)
        viewModel.selectCategory(null)
        compose.onAllNodes(hasText("Channel One") and hasClickAction()).onFirst().assertExists()
        compose.waitForIdle()

        programmeCell("Day after on 4").assertIsFocused()
    }

    @Test
    fun `paging to a day with nothing on a row lands on its channel, and Right from there goes on a day`() {
        onTomorrowsLastProgramme()
        press(Key.DirectionRight)
        compose.onAllNodes(programme("Day after on 1")).onFirst().assertExists()
        compose.waitForIdle()

        press(Key.DirectionRight)
        compose.waitForIdle()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(3)
        compose.onAllNodes(programme("Day after on 1")).assertCountEquals(0)
        compose.waitForIdle()
        channelCell("Channel One").assertIsFocused()

        press(Key.DirectionRight)
        compose.waitForIdle()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(4)
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
        compose.waitForIdle()
        assertThat(viewModel.guideRows.value.rows.none { it.primary.name == "Sport Four" }).isTrue()
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
        compose.waitForIdle()
        assertThat(viewModel.guideDayOffset.value).isEqualTo(0)
        compose.onAllNodes(programme("Now on 1")).onFirst().assertExists()
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
        compose.onAllNodes(hasText("Channel One")).assertCountEquals(0)
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
        awaitText("What's on 1 now.")
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
        compose.onAllNodes(hasText("Schedule recording") and hasClickAction()).onFirst().assertExists()
        compose.onNode(hasText("Remind me") and hasClickAction()).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()

        compose.onNode(hasText("Schedule recording")).assertDoesNotExist()
        programmeCell("Later on 1").assertIsFocused()
    }

    /**
     * Channel One: a short news that ends ten minutes after the guide is built, then a film.
     * Channel Two: a programme that ends at the same time, then nothing until one o'clock.
     */
    private fun endingSoonLibrary() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.categories().upsertAll(listOf(Category("10", 1, "News", StreamKind.LIVE, sortIndex = 0)))
        db.channels().replaceCatalogue(
            1,
            listOf(channel(1, "Channel One", 1, "10"), channel(2, "Channel Two", 1, "10")),
            syncStamp = 1,
        )
        graph.catalogRepository.renormalizeAll()
        val feed = db.epgFeeds().insert(EpgFeed(name = "Feed"))
        db.programmes().insertOrReplace(
            listOf(
                listing(feed, 1, now - HOUR..now + 10 * MINUTE, "Short news", "The short news."),
                listing(feed, 1, now + 10 * MINUTE..now + 2 * HOUR, "Afternoon film", "The afternoon film."),
                listing(feed, 2, now - HOUR..now + 10 * MINUTE, "Quiz", "The quiz."),
                listing(feed, 2, now + HOUR..now + 2 * HOUR, "Drama", "The drama."),
            ),
        )
    }

    /**
     * Moves the clock on to twenty past, past the end of what was on when the guide was built but
     * inside the same half hour, so the rows aren't rebuilt, and lets the Live screen's minute tick
     * run. Checks the rows really are the ones built at noon, so the band can't pass by a rebuild.
     */
    private fun twentyMinutesLater() {
        val builtAtNoon = viewModel.guideRows.value.rows.first { it.primary.name == "Channel One" }
        assertThat(builtAtNoon.now?.title).isEqualTo("Short news")
        now += 20 * MINUTE
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitForIdle()
        assertThat(
            viewModel.guideRows.value.rows.first {
                it.primary.name == "Channel One"
            },
        ).isSameInstanceAs(builtAtNoon)
    }

    @Test
    fun `on a channel's name, the band shows what's on now, not a programme that ended since the guide was built`() {
        endingSoonLibrary()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        channelCell("Channel One").assertIsFocused()
        awaitText("The short news.")

        twentyMinutesLater()

        awaitText("The afternoon film.")
        compose.onNode(hasText("The short news.")).assertDoesNotExist()
        captureScreen("live_channel_after_programme_ended")
    }

    @Test
    fun `on a programme that has ended, the band still shows that programme`() {
        endingSoonLibrary()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        twentyMinutesLater()

        programmeCell("Short news").requestFocus()
        compose.waitForIdle()

        awaitText("The short news.")
        compose.onNode(hasText("The afternoon film.")).assertDoesNotExist()
    }

    @Test
    fun `on a channel with nothing on now, the band says there's no guide for it, as for a channel with no guide`() {
        endingSoonLibrary()
        setLive()
        pressOkOnLiveInTheRail()
        awaitRows()
        twentyMinutesLater()

        press(Key.DirectionDown)

        channelCell("Channel Two").assertIsFocused()
        awaitText(app.getString(R.string.guide_no_info_channel))
        compose.onNode(hasText("The quiz.")).assertDoesNotExist()
        compose.onNode(hasText("The drama.")).assertDoesNotExist()
    }

    private companion object {
        const val MINUTE = 60 * 1000L
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR

        /** GuidePreview's SYNOPSIS_SETTLE_MILLIS, which the band waits before reading a synopsis. */
        const val BAND_SETTLE_MILLIS = 200L
    }
}

/**
 * [keyCode] on the remote, through the window, and on to the next. A [repeatCount] above 0 is the key
 * held, and sends no key up; so does [release] false, the first key down of a hold.
 */
private fun View.press(keyCode: Int, repeatCount: Int = 0, release: Boolean = repeatCount == 0): View {
    val at = android.os.SystemClock.uptimeMillis()
    dispatchKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_DOWN, keyCode, repeatCount))
    if (release) dispatchKeyEvent(KeyEvent(at, at, KeyEvent.ACTION_UP, keyCode, 0))
    return this
}

/** A programme on channel [id]'s guide, as the library's channels name their guides, from [airs]' start to end. */
private fun listing(feed: Long, id: Long, airs: LongRange, title: String, description: String? = null) = Programme(
    feedId = feed,
    epgChannelId = "epg$id",
    startUtcMillis = airs.first,
    endUtcMillis = airs.last,
    title = title,
    description = description,
)
