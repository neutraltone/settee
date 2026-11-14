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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.SourceKind
import app.opentv.pairing.PairingServer
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import app.opentv.ui.onboarding.TMDB_STEP_ACTIONS
import app.opentv.ui.settings.SettingsMemory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
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

    // Most tests here are about Setup, so they have a TMDB Key already and see no TMDB step.
    @Before
    fun haveTmdbKey() = graph.settings.setTmdbApiKey(TMDB_KEY)

    @After
    fun close() = graph.database.close()

    /** The keyboard over an entry screen, which is a window of its own. */
    private fun entryKeyboard(): DeviceKeyboard {
        compose.waitForIdle()
        var root: View? = null
        Espresso.onView(ViewMatchers.isRoot()).inRoot(RootMatchers.isDialog()).perform(
            object : ViewAction {
                override fun getConstraints() = ViewMatchers.isRoot()
                override fun getDescription() = "find the entry screen's window"
                override fun perform(controller: UiController, view: View) {
                    root = view
                }
            },
        )
        return DeviceKeyboard(compose) { checkNotNull(root) }
    }

    /** The TMDB step's own button, not the Try again of a phone server that couldn't start. */
    private fun stepButton(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag(TMDB_STEP_ACTIONS)),
    )

    /** Types [key] on the TMDB step's Type it instead and saves it. */
    private fun typeTmdbKey(key: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Type it instead").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Type it instead").performClick()
        entryKeyboard().run {
            type(key)
            pressAction()
        }
    }

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

    @Test
    fun `saving the first provider with no TMDB Key asks for one, with Setup loading behind it`() {
        graph.settings.removeTmdbApiKey()
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        compose.onNodeWithText("Add your TMDB Key").assertExists()
        compose.onNodeWithText("It's optional", substring = true).assertExists()
        compose.onNodeWithText("Type it instead").assertExists()
        compose.onNodeWithText(TMDB_ATTRIBUTION).assertExists()
        compose.onNode(hasText("Skip for now") and isFocused()).assertExists()
        assertWholeStepOnScreen()
        // The first Catalogue Refresh didn't wait for the step.
        compose.waitUntil(5_000) { runBlocking { graph.sourceRepository.all() }.any { it.lastCatalogSyncMillis > 0 } }
        captureScreen("onboarding_tmdb_step")

        compose.onNodeWithText("Skip for now").performClick()

        compose.waitUntil(5_000) { route() == Routes.SETUP }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Take me to Settee").fetchSemanticsNodes().isNotEmpty() }
        assertThat(graph.settings.tmdbApiKey.value).isEmpty()
        // Back from Setup doesn't return to the step.
        assertThat(compose.runOnIdle { nav.previousBackStackEntry?.destination?.route }).isNotEqualTo(Routes.TMDB_STEP)
    }

    @Test
    fun `a key TMDB accepts on the TMDB step is saved and says so, and Carry on goes on to Setup`() {
        graph.settings.removeTmdbApiKey()
        panel.replies["/3/authentication"] = """{"success": true}"""
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        compose.waitForIdle()
        val keyRowTop = typeItInstead().top
        val keyRowHeight = typeItInstead().height

        typeTmdbKey(OTHER_TMDB_KEY)

        val savedKey = hasContentDescription("Key saved, ending bbbb")
        compose.waitUntil(5_000) { compose.onAllNodes(savedKey).fetchSemanticsNodes().isNotEmpty() }
        assertThat(graph.settings.tmdbApiKey.value).isEqualTo(OTHER_TMDB_KEY)
        // A field filled with the key, masked but for its end, in the key row's place and size; the
        // QR code goes with the server that answered it.
        compose.onNodeWithText("•".repeat(24) + "bbbb", useUnmergedTree = true).assertExists()
        val field = compose.onNode(savedKey).fetchSemanticsNode().boundsInRoot
        assertThat(field.top).isEqualTo(keyRowTop)
        assertThat(field.height).isEqualTo(keyRowHeight)
        compose.onNode(hasText("Scan with your phone", substring = true)).assertDoesNotExist()
        // Time to read it: the step waits for the viewer rather than moving on by itself.
        assertThat(route()).isEqualTo(Routes.TMDB_STEP)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Carry on") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        captureScreen("onboarding_tmdb_saved")

        assertWholeStepOnScreen()
        stepButton("Carry on").performClick()

        compose.waitUntil(5_000) { route() == Routes.SETUP }
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
        hasText(text) and hasAnyAncestor(hasTestTag(TMDB_STEP_ACTIONS)),
    )

    @Test
    fun `with OpenSubtitles, skipping the TMDB step goes on to the OpenSubtitles step, and Not now to Setup`() {
        withOpenSubtitles.settings.removeTmdbApiKey()
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }

        compose.onNodeWithText("Skip for now").performClick()

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
    fun `with OpenSubtitles and a TMDB Key already, the first provider goes straight to the OpenSubtitles step`() {
        withOpenSubtitles.settings.setTmdbApiKey(TMDB_KEY)
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.OPENSUBTITLES_STEP }
    }

    /**
     * The step fits on a TV screen as it is: nothing scrolled off the top to bring the Highlight into
     * view, and nothing hanging off the bottom.
     */
    private fun assertWholeStepOnScreen() {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Add your TMDB Key").fetchSemanticsNode().boundsInRoot
        val attribution = compose.onNodeWithText(TMDB_ATTRIBUTION).fetchSemanticsNode().boundsInRoot
        assertThat(title.top).isAtLeast(screen.top)
        assertThat(attribution.bottom).isAtMost(screen.bottom)
    }

    /**
     * The phone's address for the TMDB step's paste box, once its server is listening; skips the test
     * where the machine running it has no private network address to listen on.
     */
    private fun phoneAddress(server: PairingServer = graph.pairingServer): PairingServer.Session {
        compose.waitUntil(5_000) { server.state.value !is PairingServer.State.Idle }
        val listening = server.state.value as? PairingServer.State.Listening
        assumeTrue("needs a private LAN address to serve on", listening != null)
        return listening!!.session
    }

    /** Posts [form] to the phone page's [path], as the phone's browser would. */
    private fun fromPhone(session: PairingServer.Session, path: String, vararg form: Pair<String, String>) {
        val body = okhttp3.FormBody.Builder().add("t", session.url.substringAfter("t="))
            .apply { form.forEach { (name, value) -> add(name, value) } }
            .build()
        val request = okhttp3.Request.Builder().url("http://${session.host}:${session.port}$path").post(body).build()
        // Off the main thread: the server waits on the TV, which answers there.
        Thread {
            runCatching { okhttp3.OkHttpClient().newCall(request).execute().close() }
        }.start()
    }

    @Test
    fun `with OpenSubtitles next, a key from the phone waits on Carry on, then that step keeps the same address`() {
        withOpenSubtitles.settings.removeTmdbApiKey()
        panel.replies["/3/authentication"] = """{"success": true}"""
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        val server = withOpenSubtitles.pairingServer
        val session = phoneAddress(server)

        fromPhone(session, "/key", "key" to OTHER_TMDB_KEY)

        // The TV says the key is saved and waits, so there's time to read it, while the phone goes on.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Carry on") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(route()).isEqualTo(Routes.TMDB_STEP)
        assertThat(withOpenSubtitles.settings.tmdbApiKey.value).isEqualTo(OTHER_TMDB_KEY)

        compose.onNodeWithText("Carry on").performClick()

        compose.waitUntil(10_000) { route() == Routes.OPENSUBTITLES_STEP }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // The same address, so the phone needs no new code.
        assertThat((server.state.value as PairingServer.State.Listening).session).isEqualTo(session)
    }

    @Test
    fun `a key sent from the phone is saved on the TV, which says so with Carry on highlighted`() {
        graph.settings.removeTmdbApiKey()
        panel.replies["/3/authentication"] = """{"success": true}"""
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        val session = phoneAddress()

        fromPhone(session, "/key", "key" to OTHER_TMDB_KEY)

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Carry on") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(graph.settings.tmdbApiKey.value).isEqualTo(OTHER_TMDB_KEY)
        assertWholeStepOnScreen()
    }

    @Test
    fun `Skip for now on the phone takes the TV on to Setup`() {
        graph.settings.removeTmdbApiKey()
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        val session = phoneAddress()

        fromPhone(session, "/skip")

        compose.waitUntil(10_000) { route() == Routes.SETUP }
        assertThat(graph.settings.tmdbApiKey.value).isEmpty()
    }

    @Test
    fun `Skip for now on the phone's TMDB page goes on to the OpenSubtitles step, which waits for its own answer`() {
        withOpenSubtitles.settings.removeTmdbApiKey()
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, withOpenSubtitles))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }

        fromPhone(phoneAddress(withOpenSubtitles.pairingServer), "/skip")

        compose.waitUntil(10_000) { route() == Routes.OPENSUBTITLES_STEP }
        // The TMDB page's skip is no answer to this step: it stays until the viewer chooses.
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertThat(route()).isEqualTo(Routes.OPENSUBTITLES_STEP)
    }

    /** Where Type it instead is on screen. */
    private fun typeItInstead() = compose.onNodeWithText("Type it instead").fetchSemanticsNode().boundsInRoot

    /** Where the step's Skip for now is on screen. */
    private fun skipForNow() = stepButton("Skip for now").fetchSemanticsNode().boundsInRoot

    @Test
    fun `the TMDB step holds still while a key is checked and when it's turned down`() {
        graph.settings.removeTmdbApiKey()
        panel.replies["/3/authentication"] = "!401"
        val checking = panel.hold("/3/authentication")
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }
        compose.waitForIdle()
        val typeAt = typeItInstead()
        val skipAt = skipForNow()

        typeTmdbKey(OTHER_TMDB_KEY)
        assertThat(checking.reached.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Checking your key with TMDB…").fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(typeItInstead()).isEqualTo(typeAt)
        assertThat(skipForNow()).isEqualTo(skipAt)
        captureScreen("onboarding_tmdb_checking")
        checking.release()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("TMDB didn't accept", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(typeItInstead()).isEqualTo(typeAt)
        assertThat(skipForNow()).isEqualTo(skipAt)
        captureScreen("onboarding_tmdb_wrong_key")
    }

    @Test
    fun `a key TMDB turns down on the TMDB step offers Try again and Skip for now, and keeps the provider`() {
        graph.settings.removeTmdbApiKey()
        panel.replies["/3/authentication"] = "!401"
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))
        savePlaylist()
        compose.waitUntil(5_000) { route() == Routes.TMDB_STEP }

        typeTmdbKey(OTHER_TMDB_KEY)

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("TMDB didn't accept", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        stepButton("Try again").assertExists()
        stepButton("Skip for now").assertExists()
        assertThat(route()).isEqualTo(Routes.TMDB_STEP)
        assertThat(graph.settings.tmdbApiKey.value).isEmpty()
        assertThat(runBlocking { graph.sourceRepository.all() }.map { it.kind }).containsExactly(SourceKind.M3U)

        stepButton("Try again").performClick()
        compose.onNode(hasText("TMDB didn't accept", substring = true)).assertDoesNotExist()
        stepButton("Skip for now").performClick()
        compose.waitUntil(5_000) { route() == Routes.SETUP }
    }

    @Test
    fun `a provider added while there's one already doesn't open Setup, or the TMDB step`() {
        graph.settings.removeTmdbApiKey()
        runBlocking { graph.sourceRepository.save(PANEL_SOURCE.copy(id = 0, lastCatalogSyncMillis = 1)) }
        setApp(start = Routes.ADD_SOURCE, sources = SourcesViewModel(app, graph))

        savePlaylist()

        compose.waitUntil(5_000) { route() == Routes.MAIN }
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
    fun `Live TV's Add opens the same page`() {
        setApp(start = Routes.MAIN)

        compose.runOnIdle { nav.openAddProvider() }

        onThePage()
    }
}

/** The start of what a playlist with no channels says (core/ErrorWording). */
private const val NO_CHANNELS = "There are no channels at that address"

/** Placeholder TMDB Keys, low-entropy so gitleaks sees no secret. */
private val TMDB_KEY = "a".repeat(32)
private val OTHER_TMDB_KEY = "b".repeat(32)

private const val TMDB_ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
