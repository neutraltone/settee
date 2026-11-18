/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.ui.MainShell
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.ProvidersCarry
import app.opentv.ui.vod.SeriesScreen
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Movies and Shows with no provider that can carry them say so, with a button that does something
 * about it, rather than asking the viewer to wait for titles that never come (#466).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class VodWithoutXtreamTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModels = TestViewModels()
    private val viewModel by lazy { viewModels.vod(app, graph) }

    private var settingsOpened = 0
    private var addProviderOpened = 0

    @After
    fun close() {
        viewModels.clear()
        graph.settings.forgetDatabaseRows()
        graph.database.close()
    }

    /** Movies' or Shows' [tab] open behind the rail, as the app draws it, with nothing highlighted yet. */
    private fun setTab(tab: Tab, providers: ProvidersCarry, isSyncing: Boolean = false) {
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = tab,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                if (tab == Tab.MOVIES) {
                    MoviesScreen(
                        onOpenMovie = {},
                        onResume = {},
                        providers = providers,
                        isSyncing = isSyncing,
                        onAddProvider = { addProviderOpened++ },
                        onOpenSettings = { settingsOpened++ },
                        viewModel = viewModel,
                    )
                } else {
                    SeriesScreen(
                        onOpenSeries = {},
                        onResume = {},
                        providers = providers,
                        isSyncing = isSyncing,
                        onAddProvider = { addProviderOpened++ },
                        onOpenSettings = { settingsOpened++ },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertNone(text: String) {
        compose.waitForIdle()
        val found = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes()
        check(found.isEmpty()) { "Didn't expect \"$text\" on screen" }
    }

    @Test
    fun `Movies with only playlists or portals says films come only from an Xtream login`() {
        setTab(Tab.MOVIES, ProvidersCarry.LIVE_ONLY)

        awaitText(app.getString(R.string.vod_no_movies_xtream_only))
        assertNone(app.getString(R.string.vod_no_movies_provider))
        captureScreen("movies_playlist_provider")
    }

    @Test
    fun `while a playlist's channels sync, Movies says films need an Xtream login rather than loading`() {
        setTab(Tab.MOVIES, ProvidersCarry.LIVE_ONLY, isSyncing = true)

        awaitText(app.getString(R.string.vod_no_movies_xtream_only))
        assertNone(app.getString(R.string.vod_loading_movies))
    }

    @Test
    fun `Open Settings, beside the playlist sentence, opens Settings`() {
        setTab(Tab.MOVIES, ProvidersCarry.LIVE_ONLY)
        awaitText(openSettings)

        compose.onNode(hasText(openSettings)).performClick()

        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `Movies with no provider offers Add a provider, which opens adding one`() {
        setTab(Tab.MOVIES, ProvidersCarry.NO_PROVIDER)
        awaitText(app.getString(R.string.vod_no_movies_add))
        captureScreen("movies_no_provider")

        compose.onNode(hasText(app.getString(R.string.guide_add_provider_button))).performClick()

        assertThat(addProviderOpened).isEqualTo(1)
    }

    @Test
    fun `OK on Movies in the rail lands on Open Settings when no provider carries movies`() {
        setTab(Tab.MOVIES, ProvidersCarry.LIVE_ONLY)
        awaitText(openSettings)

        enter("Movies")

        compose.onNode(hasText(openSettings)).assertIsFocused()
    }

    @Test
    fun `Right from Movies in the rail lands on Open Settings when no provider carries movies`() {
        setTab(Tab.MOVIES, ProvidersCarry.LIVE_ONLY)
        awaitText(openSettings)

        rightFromRail("Movies")

        compose.onNode(hasText(openSettings)).assertIsFocused()
    }

    @Test
    fun `Right from Movies in the rail lands on Add a provider when there's no provider`() {
        setTab(Tab.MOVIES, ProvidersCarry.NO_PROVIDER)
        awaitText(addProvider)

        rightFromRail("Movies")

        compose.onNode(hasText(addProvider)).assertIsFocused()
    }

    @Test
    fun `Shows with only playlists or portals says shows come only from Xtream, and Right then OK opens Settings`() {
        setTab(Tab.SHOWS, ProvidersCarry.LIVE_ONLY)
        awaitText(app.getString(R.string.vod_no_shows_xtream_only))
        assertNone(app.getString(R.string.vod_no_shows_provider))
        captureScreen("shows_playlist_provider")

        rightFromRail("Shows")
        compose.onNode(hasText(openSettings)).assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(settingsOpened).isEqualTo(1)
    }

    @Test
    fun `Shows with no provider lands on Add a provider from the rail, which opens adding one`() {
        setTab(Tab.SHOWS, ProvidersCarry.NO_PROVIDER)
        awaitText(app.getString(R.string.vod_no_shows_add))
        captureScreen("shows_no_provider")

        rightFromRail("Shows")
        compose.onNode(hasText(addProvider)).assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(addProviderOpened).isEqualTo(1)
    }

    @Test
    fun `Movies with a provider that carries them still says it hasn't sent any yet, with nothing to press`() {
        setTab(Tab.MOVIES, ProvidersCarry.MOVIES_AND_SHOWS)

        awaitText(app.getString(R.string.vod_no_movies_provider))
        assertNone(app.getString(R.string.vod_no_movies_xtream_only))
        assertNone(openSettings)
        assertNone(addProvider)
    }

    @Test
    fun `Shows with a provider that carries them still says it hasn't sent any yet`() {
        setTab(Tab.SHOWS, ProvidersCarry.MOVIES_AND_SHOWS)

        awaitText(app.getString(R.string.vod_no_shows_provider))
        assertNone(app.getString(R.string.vod_no_shows_xtream_only))
    }

    private val openSettings get() = app.getString(R.string.vod_open_settings_button)
    private val addProvider get() = app.getString(R.string.guide_add_provider_button)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** OK on [tab] in the rail, which hands the Highlight to the tab after a moment. */
    private fun enter(tab: String) {
        compose.onNode(hasContentDescription(tab) or hasText(tab)).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** Right from [tab] in the rail, into the tab's content. */
    private fun rightFromRail(tab: String) {
        compose.onNode(hasContentDescription(tab) or hasText(tab)).requestFocus()
        press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
}
