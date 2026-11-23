/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import app.opentv.core.CrashReports
import app.opentv.core.ServiceLocator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val compose = createComposeRule()

    /** A panel answering a playlist, for the tests that save a provider. */
    private val panel = FakePanel("" to "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n")

    /** The same app as [graph], in a build with an OpenSubtitles key; its database opens only if used. */
    @get:Rule(order = 0)
    val openSubtitlesGraph = TestGraph(name = "provider-routes-opensubtitles", http = panel.http, swapIn = false)

    /** The same app as [graph], in a build with Settee's Sentry DSN; nothing it'd send leaves the test. */
    @get:Rule(order = 0)
    val crashReportsGraph =
        TestGraph(name = "provider-routes-crash-reports", http = panel.http, swapIn = false, crashReportsTo = CRASH_REPORTS)

    /** And with both OpenSubtitles' key and Sentry's DSN. */
    @get:Rule(order = 0)
    val bothGraph = TestGraph(name = "provider-routes-both", http = panel.http, swapIn = false, crashReportsTo = CRASH_REPORTS)

    @get:Rule(order = 1)
    val testGraph = TestGraph(compose, name = "provider-routes-test", http = panel.http)

    @get:Rule(order = 2)
    val secrets = FixedServerSecrets()

    private lateinit var nav: NavHostController
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val app = testGraph.app
    private val graph = testGraph.graph

    private fun setApp(start: String, sources: SourcesViewModel = testGraph.track(SourcesViewModel(app))) =
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

    /** Settles with the save held at [hold]. */
    private fun held(hold: FakePanel.Hold) {
        compose.waitForIdle()
        assertThat(hold.reached.count).isEqualTo(0L)
    }

    /** Settles once the save started from a provider's form has finished, channels and all. */
    private fun saveFinishes(sources: SourcesViewModel) {
        compose.waitForIdle()
        assertThat(sources.ui.value.adding).isFalse()
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
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))

        savePlaylist()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
        compose.onNodeWithText("Take me to Settee").performClick()
        compose.onNodeWithText("Main on HOME").assertExists()
        // Nothing to go back to: Setup and the page are gone.
        assertThat(compose.runOnIdle { nav.previousBackStackEntry }).isNull()
    }

    @Test
    fun `Watch live TV now goes to Live TV with setup carrying on`() {
        val guide = panel.hold("xmltv.php")
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))

        savePlaylist()
        compose.onAllNodesWithText("Watch live TV now").onFirst().assertExists()
        compose.onNodeWithText("Watch live TV now").performClick()

        compose.onNodeWithText("Main on LIVE").assertExists()
        guide.release()
    }

    @Test
    fun `a first provider whose login fails goes back to its form, which says why`() {
        panel.replies[""] = "#EXTM3U\n"
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))

        savePlaylist()

        compose.onAllNodesWithText(NO_CHANNELS, substring = true).onFirst().assertExists()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.onNodeWithText("Playlist URL").assertExists()
        assertThat(runBlocking { graph.sourceRepository.observeAll().first() }).isEmpty()
    }

    @Test
    fun `after a failed login, trying again from the form opens Setup and stays there`() {
        panel.replies[""] = "#EXTM3U\n"
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))
        savePlaylist()
        compose.onAllNodesWithText(NO_CHANNELS, substring = true).onFirst().assertExists()

        panel.replies[""] = "#EXTM3U\n#EXTINF:-1,BBC One\nhttp://example.com/1.ts\n"
        compose.onNodeWithText("Save and load").performClick()

        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
        assertThat(route()).isEqualTo(Routes.SETUP)
    }

    @Test
    fun `a provider left by Setup closed before its channels came in doesn't stop the next being the first`() {
        // As Setup leaves it when Settee closes during its first step.
        val left = runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        graph.settings.setSetupSourceId(left)
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))

        savePlaylist()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
        assertThat(runBlocking { graph.sourceRepository.observeAll().first() }.map { it.kind })
            .containsExactly(SourceKind.M3U)
    }

    @Test
    fun `a provider that never loaded but wasn't left by Setup is kept when the next is saved`() {
        // Added before Setup existed, while its panel was down, say.
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 0)) }
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, graph)))

        savePlaylist()

        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind })
            .containsExactly(SourceKind.XTREAM, SourceKind.M3U)
    }

    private val withOpenSubtitles by lazy {
        compose.registerIdlingResource(openSubtitlesGraph)
        openSubtitlesGraph.graph.apply { openSubtitlesKey = "k" }
    }

    private fun openSubtitlesStepButton(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(STEP_ACTIONS)),
    )

    /** Saves a first playlist in a build with OpenSubtitles, and waits for its step. */
    private fun openStep() {
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, withOpenSubtitles)))
        savePlaylist()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    /**
     * Sends a playlist from the phone, on one of the graph's threads: the phone waits for the TV to
     * answer, so while it waits the request counts as the graph's work, and a settle covers what the
     * pairing server's own plain threads hand on to the screen.
     */
    private fun fromPhone(session: PairingServer.Session) {
        CoroutineScope(graph.dispatchers.io).launch { submitFromPhone(session) }
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
        compose.onAllNodes(hasText("Not now") and isFocused()).onFirst().assertExists()
        captureScreen("onboarding_opensubtitles_step")

        openSubtitlesStepButton("Not now").performClick()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route })
            .isNotEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    private val crashReportsGraphs = mutableListOf<ServiceLocator.Graph>()

    /** The app in a build with Settee's Sentry DSN. */
    private val withCrashReports by lazy {
        compose.registerIdlingResource(crashReportsGraph)
        crashReportsGraph.graph.also { crashReportsGraphs += it }
    }

    /** The app in a build with OpenSubtitles' key and Settee's Sentry DSN. */
    private val withBoth by lazy {
        compose.registerIdlingResource(bothGraph)
        bothGraph.graph.apply { openSubtitlesKey = "k" }.also { crashReportsGraphs += it }
    }

    @After
    fun turnCrashReportsOff() {
        crashReportsGraphs.forEach { it.crashReports.setOn(false) }
    }

    @Test
    fun `with crash reports, the first provider goes on to their step, and Not now to Setup with them off`() {
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, withCrashReports)))

        savePlaylist()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.CRASH_REPORTS_STEP)
        compose.onAllNodes(hasText("Not now") and isFocused()).onFirst().assertExists()
        openSubtitlesStepButton("Not now").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
        assertThat(withCrashReports.crashReports.on.value).isFalse()
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route })
            .isNotEqualTo(Routes.CRASH_REPORTS_STEP)
        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
    }

    @Test
    fun `Send crash reports on their step turns them on and goes on to Setup`() {
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, withCrashReports)))
        savePlaylist()
        compose.waitForIdle()

        openSubtitlesStepButton("Send crash reports").performClick()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
        assertThat(withCrashReports.crashReports.on.value).isTrue()
    }

    @Test
    fun `with OpenSubtitles and crash reports, OpenSubtitles' Not now goes on to the crash reports step`() {
        setApp(
            start = Routes.ADD_SOURCE,
            sources = testGraph.track(SourcesViewModel(app, withBoth)),
        )
        savePlaylist()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)

        openSubtitlesStepButton("Not now").performClick()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.CRASH_REPORTS_STEP)
        openSubtitlesStepButton("Not now").performClick()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.SETUP)
    }

    @Test
    fun `Back on the crash reports step goes back to the form and takes the provider away`() {
        val guide = panel.hold("xmltv.php")
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, withCrashReports)))
        savePlaylist()
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.CRASH_REPORTS_STEP)

        Espresso.pressBack()
        guide.release()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.waitForIdle()
        assertThat(runBlocking { withCrashReports.sourceRepository.all() }).isEmpty()
        assertThat(withCrashReports.crashReports.on.value).isFalse()
    }

    @Test
    fun `Back on the OpenSubtitles step goes back to the form, still filled in, and takes the provider away`() {
        // Setup still loading, as it is when the viewer reads the step.
        val guide = panel.hold("xmltv.php")
        openStep()

        Espresso.pressBack()
        // Undoing waits for the download it stopped to let go.
        guide.release()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.waitForIdle()
        assertThat(runBlocking { withOpenSubtitles.sourceRepository.all() }).isEmpty()
        compose.onAllNodesWithText("Save and load").onFirst().assertExists()
        assertThat(device.textShownBy(device.box(1))).isEqualTo("http://example.com/list.m3u")
        assertThat(compose.runOnIdle { withOpenSubtitles.settings.setupSourceId }).isEqualTo(0L)
    }

    @Test
    fun `saving again after Back on the OpenSubtitles step adds the provider once`() {
        openStep()
        Espresso.pressBack()
        compose.onAllNodesWithText("Save and load").onFirst().assertExists()

        compose.onNodeWithText("Save and load").performClick()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)
        openSubtitlesStepButton("Not now").performClick()
        compose.onAllNodesWithText("Take me to Settee").onFirst().assertExists()
        assertThat(runBlocking { withOpenSubtitles.sourceRepository.all() }).hasSize(1)
    }

    @Test
    fun `Back on the step after a provider sent from the phone goes back to the phone screen, still the first`() {
        // Only a TV offers the phone.
        shadowOf(app.packageManager).setSystemFeature("android.software.leanback", true)
        // The guide keeps Setup going, so taking it back waits while the phone screen comes back.
        val guide = panel.hold("xmltv.php")
        setApp(start = Routes.ADD_SOURCE, sources = testGraph.track(SourcesViewModel(app, withOpenSubtitles)))
        compose.onNodeWithText("Set up from your phone").performClick()
        // The phone screen's server is the app's own, not the OpenSubtitles graph's.
        val server = graph.pairingServer
        compose.waitForIdle()
        assertThat(server.state.value).isNotEqualTo(PairingServer.State.Idle)
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        fromPhone(session!!)
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)

        Espresso.pressBack()

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        guide.release()
        compose.waitForIdle()
        assertThat(runBlocking { withOpenSubtitles.sourceRepository.all() }).isEmpty()
        // The step stops the server as it goes, after the phone screen is back: in the app they share
        // it, but here the step has the test graph's, so it's stopped as the step would.
        compose.runOnIdle { server.stop() }
        // Listening again, with its code showing, for the phone to send a provider again.
        compose.waitForIdle()
        assertThat(server.state.value).isInstanceOf(PairingServer.State.Listening::class.java)
        compose.onNodeWithText("Then enter this code on your phone").assertExists()

        // Sent again, it's the first provider again, so the phone carries on to OpenSubtitles too.
        val again = (server.state.value as PairingServer.State.Listening).session
        fromPhone(again)
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)
        assertThat(compose.runOnIdle { server.state.value }).isInstanceOf(PairingServer.State.Listening::class.java)
        server.stop()
    }

    @Test
    fun `a login that fails while the OpenSubtitles step is open goes back to the form, which says why`() {
        panel.replies[""] = "#EXTM3U\n"
        val playlist = panel.hold("")
        openStep()

        playlist.release()

        compose.onAllNodesWithText(NO_CHANNELS, substring = true).onFirst().assertExists()
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
        setApp(start = Routes.ADD_SOURCE, sources = sources)

        savePlaylist()
        saveFinishes(sources)

        compose.onNodeWithText("Main on HOME").assertExists()
        assertThat(compose.runOnIdle { nav.previousBackStackEntry }).isNull()
    }

    @Test
    fun `a provider added from Live TV while there's one already goes back to Live TV`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        compose.onAllNodesWithText("Edit provider").onFirst().assertExists()
        compose.onNodeWithText("Save and load").performClick()
    }

    @Test
    fun `an add page opened again while the last save runs goes back once that save is in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        val playlist = panel.hold("")
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        val sources = testGraph.track(SourcesViewModel(app, graph))
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
        setApp(start = Routes.SETUP, sources = testGraph.track(SourcesViewModel(app, graph)))

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.ADD_SOURCE)
        compose.onNodeWithText("Add your provider").assertExists()
    }

    @Test
    fun `Setup restored by Android with nothing running opens on Home once the channels were in`() {
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.SETUP, sources = testGraph.track(SourcesViewModel(app, graph)))

        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.MAIN)
        compose.onNodeWithText("Main on HOME").assertExists()
    }

    @Test
    fun `Provider setup on Movies or Shows that failed opens that provider's form`() {
        runBlocking { graph.database.sources().insert(PANEL_SOURCE) }
        setApp(start = Routes.MAIN, sources = testGraph.track(SourcesViewModel(app, graph)))

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

/** Settee's Sentry project, as a release build has it; nothing sent there leaves the test. */
private val CRASH_REPORTS = CrashReports.Destination("https://k@sentry.example.org/1", CrashReports.Outbox {})
