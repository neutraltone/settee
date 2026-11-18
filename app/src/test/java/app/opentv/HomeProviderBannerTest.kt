/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.core.ViewerError
import app.opentv.data.model.Movie
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.HomeScreen
import app.opentv.ui.HomeViewModel
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.settings.SettingsMemory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home's banner for a provider whose last refresh failed in a way only the viewer can fix (#464). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeProviderBannerTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    @get:Rule
    val secrets = FixedServerSecrets()

    private val app: Application = ApplicationProvider.getApplicationContext()

    // The process's graph, which Settings › Providers reads too.
    private val graph = ServiceLocator.get(app)
    private val db = graph.database
    private val viewModels = ViewModelsInTest()
    private val memory = SettingsMemory()

    private lateinit var nav: NavHostController
    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val login = ViewerError(app.getString(R.string.error_login), "LoginRejected")

    @Before
    fun setUp(): Unit = runBlocking {
        // Other tests' providers share the database: only these tests' failures count here.
        db.sources().all().forEach { db.sources().setRefreshFailure(it.id, null) }
        db.sources().insert(Source(id = FINE, name = "Fine TV", kind = SourceKind.XTREAM, url = "http://example.com"))
        db.movies().upsertAll(listOf(superbad))
    }

    @After
    fun close() {
        runBlocking {
            viewModels.stopAll()
            listOf(FINE, ACME, OTHER).forEach { db.sources().delete(it) }
        }
    }

    private fun provider(id: Long, name: String, failure: ViewerError, enabled: Boolean = true) = runBlocking {
        db.sources().insert(
            Source(
                id = id,
                name = name,
                kind = SourceKind.XTREAM,
                url = "http://example.com",
                enabled = enabled,
                lastRefreshFailure = failure.stored(),
            ),
        )
    }

    private fun setApp() {
        val home = viewModels.track(HomeViewModel(app, graph))
        compose.setTvContent {
            view = LocalView.current
            nav = rememberNavController()
            // As MainActivity has it: the activity's, already holding the providers when Settings opens.
            viewModel<SourcesViewModel>().ui.collectAsState()
            KeptShell(
                nav,
                shell = {
                    HomeScreen(
                        onResume = {},
                        onOpenSeries = {},
                        onOpenMovie = {},
                        onPlayChannel = {},
                        onOpenLive = {},
                        takeFocus = true,
                        onNothingToShow = {},
                        onOpenProviders = { id -> nav.openProviders(memory, id) },
                        viewModel = home,
                    )
                },
            ) {
                ScreensNavHost(nav, startDestination = Routes.MAIN) {
                    mainShellRoute()
                    settingsRoutes(nav, memory)
                }
            }
        }
    }

    private fun shown(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(5_000) { shown(text) }

    private fun highlighted() = compose.onNode(isFocused())

    @Test
    fun `a rejected login shows above the first shelf, and Up reaches Open Providers, which opens its row`() {
        provider(ACME, "Acme TV", login)
        setApp()
        waitFor("Superbad")

        compose.onNodeWithText("Acme TV: ${login.sentence}").assertExists()
        compose.onNode(hasText("LoginRejected", substring = true)).assertDoesNotExist()
        // The banner doesn't take the opening Highlight: Home opens on its first card, as it always has.
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        highlighted().assertTextContains("Superbad", substring = true)
        captureScreen("home_provider_banner")

        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        highlighted().assertTextContains("Open Providers")
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.waitUntil(5_000) { compose.runOnIdle { nav.currentDestination?.route } == Routes.SETTINGS }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Acme TV") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `a provider that timed out raises no banner, since the next refresh usually fixes it`() {
        provider(ACME, "Acme TV", ViewerError(app.getString(R.string.error_timed_out), "SocketTimeout"))
        setApp()
        waitFor("Superbad")

        assertThat(shown("Open Providers")).isFalse()
    }

    @Test
    fun `two providers to fix are counted rather than named`() {
        provider(ACME, "Acme TV", login)
        provider(OTHER, "Other TV", ViewerError(app.getString(R.string.error_account_inactive), "AccountInactive"))
        setApp()

        waitFor("2 providers need attention")
        assertThat(shown("Acme TV")).isFalse()
    }

    @Test
    fun `a disabled provider raises no banner, and removing the one that did takes it away`() {
        provider(OTHER, "Other TV", login, enabled = false)
        provider(ACME, "Acme TV", login)
        setApp()
        waitFor("Acme TV: ")

        runBlocking { graph.catalogRepository.deleteSource(ACME) }

        compose.waitUntil(5_000) { !shown("Open Providers") }
        assertThat(shown("Other TV")).isFalse()
    }

    private val superbad = Movie(
        id = 4640,
        sourceId = FINE,
        streamId = "4640",
        name = "EN - Superbad (2007)",
        categoryId = null,
        posterUrl = null,
        rating = null,
        year = 2007,
        plot = null,
        durationSeconds = null,
        containerExtension = "mkv",
        streamUrl = "https://example.com/4640",
        addedMillis = 1,
    )

    private companion object {
        const val FINE = 4640L
        const val ACME = 4641L
        const val OTHER = 4642L
    }
}
