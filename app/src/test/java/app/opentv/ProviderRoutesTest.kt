/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.Tab
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.STEP_ACTIONS
import app.opentv.ui.settings.SettingsMemory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every way into adding a provider reaches the page first run opens on, through the app's real
 * provider routes. The main screen is a stand-in: Live TV's empty state hands its Add to
 * [openAddProvider], as MainActivity wires it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderRoutesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val secrets = FixedServerSecrets()

    private lateinit var nav: NavHostController
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** A panel answering a playlist, for the tests that save a provider. */
    private val panel = FakePanel("" to "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n")
    private val graph = ServiceLocator.Graph(
        app,
        http = panel.http,
        settingsFile = "provider-routes-test",
        openDatabase = {
            Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        },
    )

    @After
    fun close() = graph.database.close()

    private fun setApp(start: String, sources: SourcesViewModel = SourcesViewModel(app)) {
        compose.setTvContent {
            view = LocalView.current
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                providerRoutes(nav, sources)
                settingsRoutes(nav, SettingsMemory())
                composable(Routes.MAIN) { entry ->
                    val tab by entry.shellTab()
                    Text("Main on $tab", SetteeType.Body)
                }
                composable(Routes.MOVIE_DETAIL) { Text("A movie", SetteeType.Body) }
            }
        }
    }

    private fun route() = compose.runOnIdle { nav.currentDestination?.route }

    /** Saves a playlist from its form, as the viewer would on the TV. */
    private fun savePlaylist() {
        compose.onNodeWithText("M3U playlist").performClick()
        device.type("http://example.com/list.m3u", device.box(1))
        compose.onNodeWithText("Save and load").performClick()
    }

    private fun onThePage() {
        compose.onNodeWithText("Add your provider").assertExists()
        compose.onNodeWithText("Xtream login").assertExists()
        assertThat(compose.runOnIdle { nav.currentDestination?.route }).isEqualTo(Routes.ADD_SOURCE)
    }

    @Test
    fun `first run opens on the page`() {
        setApp(start = Routes.ADD_SOURCE)

        onThePage()
    }

    @Test
    fun `Add a provider in Settings opens the same page, and Back returns to Settings`() {
        setApp(start = Routes.SETTINGS)

        compose.onNodeWithText("Add a provider").performClick()

        onThePage()
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentDestination?.route }).isEqualTo(Routes.SETTINGS)
    }

    @Test
    fun `saving the first provider opens Setup, and Take me to Settee goes to Home`() {
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.SETUP }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Take me to Settee").performClick()
        compose.onNodeWithText("Main on HOME").assertExists()
        // Nothing to go back to: Setup and the page are gone.
        assertThat(compose.runOnIdle { nav.previousBackStackEntry }).isNull()
    }

    @Test
    fun `Watch live TV now goes to Live TV with setup carrying on`() {
        val guide = panel.hold("xmltv.php")
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Watch live TV now").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Watch live TV now").performClick()

        compose.onNodeWithText("Main on LIVE").assertExists()
        guide.release()
    }

    @Test
    fun `a first provider whose login fails goes back to its form, which says why`() {
        panel.replies[""] = "#EXTM3U\n"
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(NO_CHANNELS, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.onNodeWithText("Playlist URL").assertExists()
        assertThat(runBlocking { graph.sourceRepository.observeAll().first() }).isEmpty()
    }

    @Test
    fun `after a failed login, trying again from the form opens Setup and stays there`() {
        panel.replies[""] = "#EXTM3U\n"
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(NO_CHANNELS, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        panel.replies[""] = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n"
        compose.onNodeWithText("Save and load").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty() }
        assertThat(route()).isEqualTo(Routes.SETUP)
    }

    @Test
    fun `a provider left by Setup closed before its channels came in doesn't stop the next being the first`() {
        // As Setup leaves it when Settee closes during its first step.
        val left = runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        graph.settings.setSetupSourceId(left)
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.SETUP }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { graph.sourceRepository.observeAll().first() }.map { it.kind })
            .containsExactly(SourceKind.M3U)
    }

    @Test
    fun `a provider that never loaded but wasn't left by Setup is kept when the next is saved`() {
        // Added before Setup existed, while its panel was down, say.
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty() }
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind })
            .containsExactly(SourceKind.XTREAM, SourceKind.M3U)
    }

    @After
    fun closeOpenSubtitles() {
        // At the end, as [graph]'s is: closed mid-test, Setup's provider list could still be reading it.
        if (openSubtitlesGraph.isInitialized()) openSubtitlesGraph.value.database.close()
    }

    /** The same app as [graph], in a build with an OpenSubtitles key. */
    private val openSubtitlesGraph = lazy {
        ServiceLocator.Graph(
            app,
            http = panel.http,
            settingsFile = "provider-routes-opensubtitles",
            openDatabase = {
                Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
            },
        ).apply { openSubtitlesKey = "k" }
    }

    private val withOpenSubtitles by openSubtitlesGraph

    private fun openSubtitlesStepButton(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(STEP_ACTIONS)),
    )

    @Test
    fun `with OpenSubtitles, the first provider goes on to the OpenSubtitles step, and Not now to Setup`() {
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.OPENSUBTITLES_STEP }
        compose.onNodeWithText("Find subtitles with OpenSubtitles").assertExists()
        compose.onNodeWithText("5 a day work without an account", substring = true).assertExists()
        compose.onNodeWithText("Sign in on this TV").assertExists()
        compose.onNodeWithText("Subtitles provided by OpenSubtitles.com").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Not now") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        captureScreen("onboarding_opensubtitles_step")

        openSubtitlesStepButton("Not now").performClick()

        compose.waitUntil(5_000) { route() == Routes.SETUP }
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route })
            .isNotEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    @Test
    fun `a provider added while there's one already doesn't open Setup, or the OpenSubtitles step`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.MAIN }
    }

    /** How many main screens are on the back stack: more than one and Back from Home asks to leave Settee. */
    private fun mainScreens() = compose.runOnIdle {
        nav.currentBackStack.value.count { it.destination.route == Routes.MAIN }
    }

    /** Saves a playlist with its channels held back, then lets them in once [meanwhile] has run. */
    private fun saveHeld(sources: SourcesViewModel, meanwhile: () -> Unit) {
        val channels = panel.hold("")
        savePlaylist()
        // Through runOnIdle, which lets the main thread take the database's replies; waiting on the
        // latch alone never would.
        compose.waitUntil(5_000) { compose.runOnIdle { channels.reached.count == 0L } }
        meanwhile()
        channels.release()
        compose.waitUntil(5_000) { compose.runOnIdle { !sources.ui.value.adding } }
    }

    @Test
    fun `a provider whose channels come in after the viewer has left its page doesn't take them anywhere`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }

        saveHeld(sources) {
            compose.runOnIdle {
                nav.popBackStack()
                nav.popBackStack()
                nav.navigate(Routes.movieDetail(9))
            }
        }

        assertThat(route()).isEqualTo(Routes.MOVIE_DETAIL)
        assertThat(mainScreens()).isEqualTo(1)
        // The save still finished.
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind })
            .containsExactly(SourceKind.XTREAM, SourceKind.M3U)
    }

    /** A playlist provider the viewer added earlier, whose channels came in. */
    private fun savedPlaylist(): Long = runBlocking {
        graph.sourceRepository.save(
            Source(
                name = "List",
                kind = SourceKind.M3U,
                url = "http://example.com/list.m3u",
                lastCatalogSyncMillis = 1,
            ),
        )
    }

    private fun routes() = compose.runOnIdle { nav.currentBackStack.value.mapNotNull { it.destination.route } }

    @Test
    fun `a provider's form saved and left before its channels came in doesn't close what's open since`() {
        val id = savedPlaylist()
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openEditProvider(id)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Save and load").fetchSemanticsNodes().isNotEmpty() }

        val channels = panel.hold("")
        compose.onNodeWithText("Save and load").performClick()
        compose.waitUntil(5_000) { compose.runOnIdle { channels.reached.count == 0L } }
        compose.runOnIdle {
            nav.popBackStack()
            nav.navigate(Routes.movieDetail(9))
        }
        channels.release()
        compose.waitUntil(5_000) { compose.runOnIdle { !sources.ui.value.adding } }

        assertThat(routes()).containsExactly(Routes.MAIN, Routes.SETTINGS, Routes.MOVIE_DETAIL).inOrder()
    }

    @Test
    fun `another provider added from Settings goes back to Settings once its channels are in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.MAIN, sources = SourcesViewModel(app, graph))
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.SETTINGS }
        assertThat(routes()).containsExactly(Routes.MAIN, Routes.SETTINGS).inOrder()
    }

    @Test
    fun `another provider added from Live TV's empty state goes back to Live TV once its channels are in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.MAIN, sources = SourcesViewModel(app, graph))
        compose.runOnIdle {
            nav.selectShellTab(Tab.LIVE)
            nav.openAddProvider()
        }

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.MAIN }
        compose.onNodeWithText("Main on LIVE").assertExists()
        assertThat(routes()).containsExactly(Routes.MAIN)
    }

    @Test
    fun `Setup restored by Android with nothing running opens on the provider choice when there's no provider`() {
        setApp(start = Routes.SETUP, sources = SourcesViewModel(app, graph))

        compose.waitUntil(5_000) { route() == Routes.ADD_SOURCE }
        compose.onNodeWithText("Add your provider").assertExists()
    }

    @Test
    fun `Setup restored by Android with nothing running opens on Home once the channels were in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.SETUP, sources = SourcesViewModel(app, graph))

        compose.waitUntil(5_000) { route() == Routes.MAIN }
        compose.onNodeWithText("Main on HOME").assertExists()
    }

    @Test
    fun `Provider setup on Movies or Shows that failed opens that provider's form`() {
        runBlocking { graph.database.sources().insert(PANEL_SOURCE) }
        setApp(start = Routes.MAIN, sources = SourcesViewModel(app, graph))

        compose.runOnIdle { nav.openEditProvider(PANEL_SOURCE.id) }

        assertThat(route()).isEqualTo(Routes.EDIT_SOURCE)
        assertThat(compose.runOnIdle { nav.currentBackStackEntry?.arguments?.getString("sourceId") })
            .isEqualTo("${PANEL_SOURCE.id}")
        // Its form, not the empty one for adding a provider.
        compose.onNodeWithText("Xtream login").assertDoesNotExist()
    }

    @Test
    fun `Live TV's Add opens the same page`() {
        setApp(start = Routes.MAIN)

        compose.runOnIdle { nav.openAddProvider() }

        onThePage()
    }
}

/** The start of what a playlist with no channels says (core/ErrorWording). */
private const val NO_CHANNELS = "There are no channels at that address"
