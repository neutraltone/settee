/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.data.model.Movie
import app.opentv.data.repo.GenreGroup
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.VodTitle
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MovieShelves
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main screen stays built under Settings, Profiles, Search and title pages, so Back to it shows
 * it as it was instead of building it again (#192). Through a NavHost with the real [KeptShell] and
 * rail; the screens opened over it are stand-ins that take the highlight, as every screen does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class KeptShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private lateinit var nav: NavHostController

    /** The title last opened with OK, and how many have been. */
    private var opened = ""
    private var openedCount = 0

    private fun open(movie: Movie) {
        opened = movie.name
        openedCount++
        nav.navigate(Routes.movieDetail(movie.id))
    }

    /** The rail over Home, which shows a row of [recent] titles, and Movies, which shows three rows. */
    private fun setApp(recent: () -> List<Cover> = { covers("Added", 1L..8L) }) = compose.setTvContent {
        nav = rememberNavController()
        KeptShell(
            nav,
            shell = { entry ->
                val tab by entry.shellTab()
                MainShell(
                    tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                    current = tab,
                    onSelect = { nav.selectShellTab(it) },
                    onOpenSearch = {},
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onOpenProfiles = {},
                    activeProfileName = "Sam",
                    onExit = {},
                    modifier = Modifier.fillMaxSize(),
                ) { shown, takeFocus, onNothingToShow ->
                    if (shown == Tab.MOVIES) {
                        ScreenFrame {
                            MovieShelves(
                                resume = emptyList(),
                                favourites = (1L..8L).map { movie(it, "Favourite $it") },
                                recommended = emptyList(),
                                recentlyAdded = (11L..18L).map { movie(it, "New $it") },
                                genreRows = listOf(GenreGroup("Drama", (21L..28L).map { movie(it, "Drama $it") })),
                                onResume = {},
                                onOpenMovie = ::open,
                            )
                        }
                    } else {
                        HomeContent(
                            continueWatching = emptyList(),
                            favouriteShows = emptyList(),
                            favouriteMovies = emptyList(),
                            favouriteChannels = emptyList(),
                            recentMovies = recent(),
                            onResume = {},
                            onOpenSeries = {},
                            onOpenMovie = ::open,
                            onPlayChannel = {},
                            takeFocus = takeFocus,
                            onNothingToShow = onNothingToShow,
                        )
                    }
                }
            },
        ) {
            NavHost(nav, startDestination = Routes.MAIN) {
                mainShellRoute()
                composable(Routes.SETTINGS) { StandIn("Settings") }
                composable(Routes.MOVIE_DETAIL) { StandIn("Movie") }
                composable(Routes.VOD_PLAYER) { StandIn("Player") }
            }
        }
    }

    /** A screen with two options, one above the other, that puts the highlight on the first as it opens. */
    @Composable
    private fun StandIn(name: String) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            first.requestFocus()
        }
        Column(Modifier.fillMaxSize().background(SetteeColors.Background)) {
            Option("$name option 1", Modifier.focusRequester(first))
            Option("$name option 2")
        }
    }

    @Composable
    private fun Option(label: String, modifier: Modifier = Modifier) {
        Box(modifier.testTag(label).focusable()) { Text(label, SetteeType.Body) }
    }

    /** A rail item, by its icon's description while the rail is closed and by its label while it's open. */
    private fun railItem(name: String) = compose.onNode(hasContentDescription(name) or hasText(name))

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun railItemFocused(name: String) =
        compose.onAllNodes((hasContentDescription(name) or hasText(name)) and isFocused())
            .fetchSemanticsNodes()
            .isNotEmpty()

    /** Back as the remote sends it, through the activity, so whoever handles Back on screen gets it. */
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun focused(tag: String) = compose.onAllNodes(isFocused()).fetchSemanticsNodes().any {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" } == tag
    }

    private fun openSettingsFromRail() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Added 1")).fetchSemanticsNodes().isNotEmpty() }
        // By the remote, as a viewer does: requestFocus enters the rail on the open tab's item.
        press(Key.DirectionLeft)
        repeat(RAIL_ITEMS) {
            if (railItemFocused("Settings")) return@repeat
            press(Key.DirectionDown)
        }
        railItem("Settings").assertIsFocused()
        press(Key.DirectionCenter)
        compose.waitUntil(5_000) { focused("Settings option 1") }
    }

    @Test
    fun `Back from Settings puts the highlight back on the rail's Settings item`() {
        setApp()
        openSettingsFromRail()

        back()

        railItem("Settings").assertIsFocused()
        captureScreen("main_back_from_settings")
    }

    @Test
    fun `Back to Home from a title's page after playing it puts the highlight on the card picked`() {
        setApp()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Added 1")).fetchSemanticsNodes().isNotEmpty() }
        press(Key.DirectionRight, times = 2)
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Added 3")
        compose.waitUntil(5_000) { focused("Movie option 1") }
        // Play from the page, then Back from the player, which puts the page back: the player let
        // the main screen go, so it was built again under the page.
        compose.runOnIdle { nav.navigate(Routes.vodPlayer(MediaKey.Movie(3))) }
        compose.waitUntil(5_000) { focused("Player option 1") }
        compose.runOnIdle { nav.leaveVodPlayer(nav.currentBackStackEntry!!, Routes.movieDetail(3)) }
        compose.waitUntil(5_000) { focused("Movie option 1") }

        back()
        press(Key.DirectionCenter)

        assertThat(openedCount).isEqualTo(2)
        assertThat(opened).isEqualTo("Added 3")
    }

    @Test
    fun `while Settings is open the remote never reaches the rail or Home under it`() {
        var recent by mutableStateOf(covers("Added", 1L..8L))
        setApp(recent = { recent })
        openSettingsFromRail()

        press(Key.DirectionLeft, times = 3)
        press(Key.DirectionUp, times = 3)
        press(Key.DirectionRight, times = 3)

        compose.onNodeWithTag("Settings option 1").assertIsFocused()
    }

    @Test
    fun `Home's rows changing under Settings never take the highlight from it`() {
        var recent by mutableStateOf(covers("Added", 1L..8L))
        setApp(recent = { recent })
        openSettingsFromRail()

        // With nothing left to show, Home on screen hands the highlight to the rail.
        recent = emptyList()
        compose.waitForIdle()

        compose.onNodeWithTag("Settings option 1").assertIsFocused()
    }

    @Test
    fun `Back from a title opened on Movies is still on Movies, with the highlight on that title`() {
        setApp()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Added 1")).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { nav.selectShellTab(Tab.MOVIES) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Drama 21")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Favourite 1").requestFocus()
        press(Key.DirectionDown, times = 2)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertThat(opened).isEqualTo("Drama 22")
        compose.waitUntil(5_000) { focused("Movie option 1") }

        back()
        press(Key.DirectionCenter)

        assertThat(openedCount).isEqualTo(2)
        assertThat(opened).isEqualTo("Drama 22")
    }

    private fun covers(name: String, ids: LongRange) = ids.map { Cover(VodTitle.OfMovie(movie(it, "$name $it"))) }

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

    private companion object {
        /** More than the rail has, so walking Down through it always reaches the bottom. */
        const val RAIL_ITEMS = 10
    }
}
