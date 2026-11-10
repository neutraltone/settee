/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.opentv.data.model.Movie
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MovieShelves
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which lone composables don't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MainShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    /** The tab a test's rail last opened. */
    private var selected: Tab? = null

    /** The rail with Home open, showing [recent] and nothing watched or favourited. */
    private fun setShell(recent: () -> List<Cover> = { emptyList() }) = compose.setTvContent {
        MainShell(
            tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
            current = Tab.HOME,
            onSelect = { selected = it },
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            activeProfileName = "Sam",
            onExit = { exited = true },
        ) { _, takeFocus, onNothingToShow ->
            HomeContent(
                continueWatching = emptyList(),
                favourites = emptyList(),
                favouriteChannels = emptyList(),
                recentlyAdded = recent(),
                onResume = {},
                onOpenSeries = {},
                onOpenMovie = {},
                onPlayChannel = {},
                takeFocus = takeFocus,
                onNothingToShow = onNothingToShow,
            )
        }
    }

    /** Whether Back asked to leave the app. */
    private var exited = false

    /** The title a test last opened with OK. */
    private var opened = ""

    /**
     * The rail over tabs that each show two rows of titles named after the tab ("Shows 1" is
     * the first title of the Shows tab's first row, "Shows 11" of its second), opening on [start].
     */
    private fun setShellWithTitles(start: Tab = Tab.SHOWS) {
        var current by mutableStateOf(start)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = current,
                onSelect = {
                    selected = it
                    current = it
                },
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = { exited = true },
                modifier = Modifier.fillMaxSize(),
            ) { tab, takeFocus, onNothingToShow ->
                key(tab) {
                    val name = compose.activity.getString(tab.labelRes)
                    HomeContent(
                        continueWatching = emptyList(),
                        favourites = (1L..8L).map { Cover(VodTitle.OfMovie(movie(it, "$name $it"))) },
                        favouriteChannels = emptyList(),
                        recentlyAdded = (11L..18L).map { Cover(VodTitle.OfMovie(movie(it, "$name $it"))) },
                        onResume = {},
                        onOpenSeries = {},
                        onOpenMovie = { opened = it.name },
                        onPlayChannel = {},
                        takeFocus = takeFocus,
                        onNothingToShow = onNothingToShow,
                    )
                }
            }
        }
    }

    /** A rail item, by its icon's description while the rail is closed and by its label while it's open. */
    private fun railItem(name: String) = compose.onNode(hasContentDescription(name) or hasText(name))

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `Left from a title enters the rail on the current tab, and Right goes back to the same row`() {
        setShellWithTitles()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Shows 11")

        press(Key.DirectionLeft)
        railItem("Shows").assertIsFocused()
        captureScreen("main_rail_open")

        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Shows 11")
    }

    @Test
    fun `Home opens with the highlight on its first title`() {
        setShellWithTitles(start = Tab.HOME)
        captureScreen("main_first_highlight")

        press(Key.DirectionCenter)

        assertThat(opened).isEqualTo("Home 1")
    }

    @Test
    fun `on Movies, Right from the rail goes back to the row the highlight left`() {
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.MOVIES,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                MovieShelves(
                    resume = emptyList(),
                    favourites = (1L..8L).map { movie(it, "Favourite $it") },
                    recommended = emptyList(),
                    recentlyAdded = (11L..18L).map { movie(it, "Added $it") },
                    genreRows = listOf(GenreGroup("Drama", (21L..28L).map { movie(it, "Drama $it") })),
                    onResume = {},
                    onOpenMovie = { opened = it.name },
                )
            }
        }
        compose.onNodeWithText("Favourite 1").requestFocus()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Drama 21")

        press(Key.DirectionLeft)
        railItem("Movies").assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)

        assertThat(opened).isEqualTo("Drama 21")
    }

    @Test
    fun `OK on another tab opens it and moves the highlight onto its first title`() {
        setShellWithTitles()
        press(Key.DirectionLeft)
        press(Key.DirectionDown)
        railItem("Recordings").assertIsFocused()

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(ENTRY_WAIT_MILLIS)
        compose.waitForIdle()

        assertThat(selected).isEqualTo(Tab.RECORDINGS)
        railItem("Recordings").assertIsNotFocused()
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Recordings 1")
    }

    @Test
    fun `OK on the tab already open goes back to the row the highlight left`() {
        setShellWithTitles()
        press(Key.DirectionDown)
        press(Key.DirectionLeft)

        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(ENTRY_WAIT_MILLIS)
        compose.waitForIdle()

        railItem("Shows").assertIsNotFocused()
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Shows 11")
    }

    /**
     * The rail with Home open beside one title, which takes the Highlight when Home appears,
     * and a content area tagged "content" to measure where the tab sits.
     */
    private fun setPlainShell() = compose.setTvContent {
        MainShell(
            tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
            current = Tab.HOME,
            onSelect = { selected = it },
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenProfiles = {},
            activeProfileName = "Sam",
            onExit = { exited = true },
            modifier = Modifier.fillMaxSize(),
        ) { _, takeFocus, _ ->
            val title = remember { FocusRequester() }
            // Waits a frame, as Home does: nothing can take focus before it's laid out.
            LaunchedEffect(Unit) {
                withFrameNanos {}
                if (takeFocus) title.requestFocus()
            }
            Box(Modifier.fillMaxSize().testTag("content")) {
                Text("Title", Modifier.focusRequester(title).clickable {})
            }
        }
    }

    @Test
    fun `closed, the rail is icons only on the screen's margin, and the tab starts after it`() {
        setPlainShell()
        compose.onNodeWithText("Title").assertIsFocused()

        compose.onNodeWithText("Live TV").assertDoesNotExist()
        railItem("Home").assertLeftPositionInRootIsEqualTo(48.dp)
        compose.onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(104.dp)
    }

    @Test
    fun `with the highlight in it, the rail opens with labels and pushes the tab along, and its icons stay put`() {
        setPlainShell()

        press(Key.DirectionLeft)

        railItem("Home").assertIsFocused()
        // Said once, not as both the icon's description and the label beside it.
        compose.onNodeWithText("Live TV").assert(!hasContentDescription("Live TV"))
        railItem("Home").assertLeftPositionInRootIsEqualTo(48.dp)
        compose.onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(236.dp)

        press(Key.DirectionRight)

        compose.onNodeWithText("Title").assertIsFocused()
        compose.onNodeWithText("Live TV").assertDoesNotExist()
        compose.onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(104.dp)
    }

    @Test
    fun `the tab beside the rail changes width once as it opens and once as it closes, never on every frame`() {
        val widths = mutableListOf<Int>()
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.HOME,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = { exited = true },
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                Box(
                    Modifier.fillMaxSize().layout { measurable, constraints ->
                        widths += constraints.maxWidth
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    Text("Title", Modifier.testTag("title").clickable {})
                }
            }
        }
        compose.onNodeWithTag("title").requestFocus()
        settle()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        settle()
        compose.onNodeWithText("Live TV").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        settle()
        compose.onNodeWithText("Live TV").assertDoesNotExist()

        val closed = with(compose.density) { (960.dp - 104.dp).roundToPx() }
        val open = with(compose.density) { (960.dp - 236.dp).roundToPx() }
        assertThat(widths.dedupeRuns()).containsExactly(closed, open, closed).inOrder()
    }

    @Test
    fun `a tab that hands the highlight to the rail as it appears finds it already open, with no slide`() {
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.HOME,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = { exited = true },
                modifier = Modifier.fillMaxSize(),
            ) { _, _, onNothingToShow ->
                LaunchedEffect(Unit) {
                    withFrameNanos {}
                    onNothingToShow()
                }
                Box(Modifier.fillMaxSize().testTag("content"))
            }
        }
        // The frame the tab hands over on, and the one after, which draws it.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()

        railItem("Home").assertIsFocused()
        compose.onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(236.dp)
        captureScreen("main_rail_already_open")
    }

    @Test
    fun `a press whose release went elsewhere doesn't make a later hand-over slide`() {
        var handOver by mutableStateOf(false)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.HOME,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, onNothingToShow ->
                LaunchedEffect(handOver) { if (handOver) onNothingToShow() }
                Box(Modifier.fillMaxSize().testTag("content")) {
                    Text("Title", Modifier.testTag("title").clickable {})
                }
            }
        }
        compose.onNodeWithTag("title").requestFocus()
        // Like a press that opens a dialog, whose window then gets the key's release.
        compose.onRoot().performKeyInput { keyDown(Key.MediaPlayPause) }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        handOver = true
        Snapshot.sendApplyNotifications()
        // The frame that recomposes the tab, the one it hands over on, and the one that draws it.
        repeat(3) { compose.mainClock.advanceTimeByFrame() }

        railItem("Home").assertIsFocused()
        compose.onNodeWithTag("content").assertLeftPositionInRootIsEqualTo(236.dp)
    }

    /** Runs the clock frame by frame until a slide would have finished. */
    private fun settle() = repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }

    private fun List<Int>.dedupeRuns() = filterIndexed { i, width -> i == 0 || width != this[i - 1] }

    @Test
    fun `Back on another tab goes to Home`() {
        setShellWithTitles(start = Tab.SHOWS)

        pressBack()

        assertThat(selected).isEqualTo(Tab.HOME)
        assertThat(exited).isFalse()
    }

    @Test
    fun `Back on Home asks to leave`() {
        setShellWithTitles(start = Tab.HOME)

        pressBack()

        assertThat(exited).isTrue()
        assertThat(selected).isNull()
    }

    @Test
    fun `Back from the open rail on another tab goes to Home too`() {
        setShellWithTitles(start = Tab.SHOWS)
        press(Key.DirectionLeft)
        railItem("Shows").assertIsFocused()

        pressBack()

        assertThat(selected).isEqualTo(Tab.HOME)
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `with nothing on Home, the rail's Home item is highlighted`() {
        setShell()

        railItem("Home").assertIsFocused()
        captureScreen("home_nothing_to_show")
    }

    @Test
    fun `from there, Search is above Home and the remote's down button reaches the rest in order`() {
        setShell()
        railItem("Home").assertIsFocused()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        railItem("Search").assertIsFocused()

        for (item in listOf("Home", "Live TV", "Movies", "Shows", "Recordings", "Sam", "Settings")) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            railItem(item).assertIsFocused()
        }
    }

    @Test
    fun `tabs switched off in settings aren't in the rail`() {
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = false, moviesEnabled = true, seriesEnabled = false),
                current = Tab.HOME,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = { exited = true },
            ) { _, _, onNothingToShow -> LaunchedEffect(Unit) { onNothingToShow() } }
        }

        railItem("Live TV").assertDoesNotExist()
        railItem("Shows").assertDoesNotExist()
        railItem("Movies").assertExists()
    }

    @Test
    fun `from there, OK on another tab opens it`() {
        setShell()

        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }

        assertThat(selected).isEqualTo(Tab.LIVE)
    }

    @Test
    fun `when titles arrive while the rail is highlighted, the highlight stays there`() {
        var recent by mutableStateOf(emptyList<Cover>())
        setShell { recent }
        railItem("Home").assertIsFocused()

        recent = listOf(Cover(VodTitle.OfMovie(movie(11, "Drive"))))

        compose.onNodeWithText("Drive").assertIsNotFocused()
        railItem("Home").assertIsFocused()
    }

    private companion object {
        /** Long enough for a tab picked in the rail to load and take the highlight. */
        const val ENTRY_WAIT_MILLIS = 300L

        /** About a second of frames: longer than any slide. */
        const val SETTLE_FRAMES = 60
    }

    private fun movie(id: Long, name: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = null,
        posterUrl = "https://example.com/$id.jpg",
        rating = null,
        year = null,
        plot = null,
        durationSeconds = null,
        containerExtension = "mp4",
        streamUrl = "",
    )
}
