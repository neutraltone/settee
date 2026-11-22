/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.pairing.PairingServer
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.Tab
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.STEP_ACTIONS
import app.opentv.ui.settings.SettingsMemory
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private fun setApp(start: String, sources: SourcesViewModel = SourcesViewModel(app)) =
        compose.setTvContent(appContent(start, sources))

    /** The app [setApp] shows, in a screen Android can rebuild, as it does when a phone turns round. */
    private fun setRebuildableApp(start: String, sources: SourcesViewModel) =
        StateRestorationTester(compose).apply { setTvContent(appContent(start, sources)) }

    private fun appContent(start: String, sources: SourcesViewModel): @Composable () -> Unit = {
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

    private fun route() = compose.runOnIdle { nav.currentDestination?.route }

    /** How many entries of [route] are on the back stack. */
    private fun entriesOf(route: String) =
        compose.runOnIdle { nav.currentBackStack.value.count { it.destination.route == route } }

    /**
     * Waits for the save to reach [hold]. Through runOnIdle: the save runs on the main looper, and
     * a condition that doesn't idle Compose never lets it turn.
     */
    private fun held(hold: FakePanel.Hold) = compose.waitUntil(UI_WAIT_MILLIS) {
        compose.runOnIdle { hold.reached.count == 0L }
    }

    /** Waits for the save started from a provider's form to finish, channels and all. */
    private fun saveFinishes(sources: SourcesViewModel) {
        compose.waitUntil(UI_WAIT_MILLIS) { compose.runOnIdle { !sources.ui.value.adding } }
    }

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

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.SETUP }
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty()
        }
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
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Watch live TV now").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Watch live TV now").performClick()

        compose.onNodeWithText("Main on LIVE").assertExists()
        guide.release()
    }

    @Test
    fun `a first provider whose login fails goes back to its form, which says why`() {
        panel.replies[""] = "#EXTM3U\n"
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(UI_WAIT_MILLIS) {
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
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText(NO_CHANNELS, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        panel.replies[""] = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n"
        compose.onNodeWithText("Save and load").performClick()

        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(route()).isEqualTo(Routes.SETUP)
    }

    @Test
    fun `a provider left by Setup closed before its channels came in doesn't stop the next being the first`() {
        // As Setup leaves it when Settee closes during its first step.
        val left = runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        graph.settings.setSetupSourceId(left)
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.SETUP }
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(runBlocking { graph.sourceRepository.observeAll().first() }.map { it.kind })
            .containsExactly(SourceKind.M3U)
    }

    @Test
    fun `a provider that never loaded but wasn't left by Setup is kept when the next is saved`() {
        // Added before Setup existed, while its panel was down, say.
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty()
        }
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

    /** Saves a first playlist in a build with OpenSubtitles, and waits for its step. */
    private fun openStep() {
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))
        savePlaylist()
        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.OPENSUBTITLES_STEP }
    }

    /** Sends a playlist from the phone's form to the pairing server's [session], as the viewer would. */
    private fun submitFromPhone(session: PairingServer.Session) {
        val form = FormBody.Builder()
            .add("t", session.url.substringAfter("t="))
            .add("code", session.confirmCode)
            .add("kind", "m3u")
            .add("url", "http://example.com/list.m3u")
            .build()
        val request = Request.Builder().url("http://${session.host}:${session.port}/submit").post(form).build()
        OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().close()
    }

    @Test
    fun `with OpenSubtitles, the first provider goes on to the OpenSubtitles step, and Not now to Setup`() {
        openStep()

        compose.onNodeWithText("Find subtitles with OpenSubtitles").assertExists()
        compose.onNodeWithText("5 a day work without an account", substring = true).assertExists()
        compose.onNodeWithText("Sign in on this TV").assertExists()
        compose.onNodeWithText("Subtitles provided by OpenSubtitles.com").assertExists()
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodes(hasText("Not now") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        captureScreen("onboarding_opensubtitles_step")

        openSubtitlesStepButton("Not now").performClick()

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.SETUP }
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route })
            .isNotEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    @Test
    fun `Back on the OpenSubtitles step goes back to the form, still filled in, and takes the provider away`() {
        // Setup still loading, as it is when the viewer reads the step.
        val guide = panel.hold("xmltv.php")
        openStep()

        Espresso.pressBack()
        // Undoing waits for the download it stopped to let go.
        guide.release()

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.ADD_SOURCE }
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.runOnIdle { runBlocking { withOpenSubtitles.sourceRepository.all() } }.isEmpty()
        }
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Save and load").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(device.textShownBy(device.box(1))).isEqualTo("http://example.com/list.m3u")
        assertThat(compose.runOnIdle { withOpenSubtitles.settings.setupSourceId }).isEqualTo(0L)
    }

    @Test
    fun `saving again after Back on the OpenSubtitles step adds the provider once`() {
        openStep()
        Espresso.pressBack()
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Save and load").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Save and load").performClick()

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.OPENSUBTITLES_STEP }
        openSubtitlesStepButton("Not now").performClick()
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(runBlocking { withOpenSubtitles.sourceRepository.all() }).hasSize(1)
    }

    @Test
    fun `Back on the step after a provider sent from the phone goes back to the phone screen, still the first`() {
        // Only a TV offers the phone.
        shadowOf(app.packageManager).setSystemFeature("android.software.leanback", true)
        // The guide keeps Setup going, so taking it back waits while the phone screen comes back.
        val guide = panel.hold("xmltv.php")
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))
        compose.onNodeWithText("Set up from your phone").performClick()
        // The phone screen's server is the app's own, not the test graph's.
        val server = ServiceLocator.get(app).pairingServer
        compose.waitUntil(UI_WAIT_MILLIS) { compose.runOnIdle { server.state.value !is PairingServer.State.Idle } }
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        val phone = thread { submitFromPhone(session!!) }
        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.OPENSUBTITLES_STEP }

        Espresso.pressBack()

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.ADD_SOURCE }
        guide.release()
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.runOnIdle { runBlocking { withOpenSubtitles.sourceRepository.all() } }.isEmpty()
        }
        // The step stops the server as it goes, after the phone screen is back: in the app they share
        // it, but here the step has the test graph's, so it's stopped as the step would.
        compose.runOnIdle { server.stop() }
        // Listening again, with its code showing, for the phone to send a provider again.
        compose.waitUntil(UI_WAIT_MILLIS) { compose.runOnIdle { server.state.value is PairingServer.State.Listening } }
        compose.onNodeWithText("Then enter this code on your phone").assertExists()
        phone.join(5_000)

        // Sent again, it's the first provider again, so the phone carries on to OpenSubtitles too.
        val again = (server.state.value as PairingServer.State.Listening).session
        val second = thread { submitFromPhone(again) }
        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.OPENSUBTITLES_STEP }
        assertThat(compose.runOnIdle { server.state.value }).isInstanceOf(PairingServer.State.Listening::class.java)
        second.join(5_000)
        server.stop()
    }

    @Test
    fun `a login that fails while the OpenSubtitles step is open goes back to the form, which says why`() {
        panel.replies[""] = "#EXTM3U\n"
        val playlist = panel.hold("")
        openStep()

        playlist.release()

        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText(NO_CHANNELS, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.onNodeWithText("Playlist URL").assertExists()
    }

    @Test
    fun `Back in Sign in on this TV's window closes the window and stays on the OpenSubtitles step`() {
        openStep()
        compose.onNodeWithText("Sign in on this TV").performClick()
        compose.onNodeWithText("Username").assertExists()

        Espresso.pressBack()

        compose.onNodeWithText("Username").assertDoesNotExist()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    @Test
    fun `a provider added in Settings while there's one already goes back to Settings, not to Setup`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }

        savePlaylist()
        saveFinishes(sources)

        assertThat(route()).isEqualTo(Routes.SETTINGS)
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
    }

    @Test
    fun `a provider added again after its channels failed goes back to Settings once they're in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        panel.replies[""] = "#EXTM3U\n"
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }
        savePlaylist()
        saveFinishes(sources)
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)

        panel.replies[""] = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n"
        compose.onNodeWithText("Save and load").performClick()
        saveFinishes(sources)

        assertThat(route()).isEqualTo(Routes.SETTINGS)
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind })
            .containsExactly(SourceKind.XTREAM, SourceKind.M3U)
    }

    @Test
    fun `a provider added on an add page with nothing under it goes to Home`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.ADD_SOURCE, sources = sources)

        savePlaylist()
        saveFinishes(sources)

        compose.onNodeWithText("Main on HOME").assertExists()
        assertThat(compose.runOnIdle { nav.previousBackStackEntry }).isNull()
    }

    @Test
    fun `a provider added from Live TV while there's one already goes back to Live TV`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.selectShellTab(Tab.LIVE)
            nav.openAddProvider()
        }

        savePlaylist()
        saveFinishes(sources)

        compose.onNodeWithText("Main on LIVE").assertExists()
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
    }

    @Test
    fun `a save that finishes after the viewer left the add page doesn't take them anywhere`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val playlist = panel.hold("")
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }

        savePlaylist()
        held(playlist)
        compose.runOnIdle {
            nav.popBackStack()
            nav.popBackStack()
            nav.navigate(Routes.movieDetail(9))
        }
        playlist.release()
        saveFinishes(sources)

        assertThat(route()).isEqualTo(Routes.MOVIE_DETAIL)
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
    }

    /** A playlist provider already saved, for the tests that edit one. */
    private fun savedPlaylist() = runBlocking {
        graph.sourceRepository.save(
            Source(
                name = "Playlist",
                kind = SourceKind.M3U,
                url = "http://example.com/list.m3u",
                lastCatalogSyncMillis = 1,
            ),
        )
    }

    /** Saves the provider on the form already open, once it has filled in from the provider. */
    private fun saveEdited() {
        compose.waitUntil(UI_WAIT_MILLIS) {
            compose.onAllNodesWithText("Edit provider").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save and load").performClick()
    }

    @Test
    fun `an add page opened again while the last save runs goes back once that save is in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val playlist = panel.hold("")
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }
        savePlaylist()
        held(playlist)

        compose.runOnIdle {
            nav.popBackStack()
            nav.openAddProvider()
        }
        playlist.release()
        saveFinishes(sources)

        assertThat(route()).isEqualTo(Routes.SETTINGS)
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind })
            .containsExactly(SourceKind.XTREAM, SourceKind.M3U)
    }

    @Test
    fun `an add page rebuilt while its save runs, as a phone turning round does, still goes back once it's in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val playlist = panel.hold("")
        val sources = SourcesViewModel(app, graph)
        val screen = setRebuildableApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openAddProvider()
        }
        savePlaylist()
        held(playlist)

        screen.emulateSavedInstanceStateRestore()
        playlist.release()
        saveFinishes(sources)

        assertThat(route()).isEqualTo(Routes.SETTINGS)
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
    }

    @Test
    fun `a save that finishes after the viewer left a provider's form doesn't close the screen they went to`() {
        val id = savedPlaylist()
        val channels = panel.hold("")
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle { nav.openEditProvider(id) }

        saveEdited()
        held(channels)
        compose.runOnIdle {
            nav.popBackStack()
            nav.navigate(Routes.movieDetail(9))
        }
        channels.release()
        saveFinishes(sources)

        assertThat(sources.ui.value.addFailure).isNull()
        assertThat(route()).isEqualTo(Routes.MOVIE_DETAIL)
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route }).isEqualTo(Routes.MAIN)
    }

    @Test
    fun `a provider's form saved while still on it goes back to Settings`() {
        val id = savedPlaylist()
        val sources = SourcesViewModel(app, graph)
        setApp(start = Routes.MAIN, sources = sources)
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.openEditProvider(id)
        }

        saveEdited()
        saveFinishes(sources)

        assertThat(sources.ui.value.addFailure).isNull()
        assertThat(route()).isEqualTo(Routes.SETTINGS)
        assertThat(entriesOf(Routes.MAIN)).isEqualTo(1)
    }

    @Test
    fun `Setup restored by Android with nothing running opens on the provider choice when there's no provider`() {
        setApp(start = Routes.SETUP, sources = SourcesViewModel(app, graph))

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.ADD_SOURCE }
        compose.onNodeWithText("Add your provider").assertExists()
    }

    @Test
    fun `Setup restored by Android with nothing running opens on Home once the channels were in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.SETUP, sources = SourcesViewModel(app, graph))

        compose.waitUntil(UI_WAIT_MILLIS) { route() == Routes.MAIN }
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
