/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.repo.EpgRepository
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings names a source by its host, and shows nothing at all, never the address, when the
 * address has no host to show: the address can carry the login (#487).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHideAddressesTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val secrets = FixedServerSecrets()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun `a provider whose address has no host shows no note, not its address or an empty line`() {
        runBlocking {
            graph.sourceRepository.save(
                Source(name = "No host 487", kind = SourceKind.M3U, url = "no host 487 password=p"),
            )
        }
        show()
        waitFor("No host 487")

        val row = compose.onNode(hasText("No host 487")).fetchSemanticsNode()
        assertThat(row.config[SemanticsProperties.Text].map { it.text }).containsExactly("No host 487")
    }

    @Test
    fun `a guide whose address has no host to name it by is called Your own guide, not its address`() {
        show(SettingsMemory(SettingsSection.Guide))
        waitFor(EpgRepository.BUILT_IN_FEEDS.first().first)
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Add your own guide").requestFocus()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type("guide 487 password=p")
            pressAction()
        }

        waitUntilSaved { feed("guide 487 password=p") != null }
        assertThat(feed("guide 487 password=p")?.name).isEqualTo("Your own guide")
        compose.onNode(hasText("password", substring = true)).assertDoesNotExist()
    }

    private fun show(memory: SettingsMemory = SettingsMemory()) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(
            memory = memory,
            onBack = {},
            onAddProvider = {},
            onEditProvider = {},
            onOpenChannels = {},
            onOpenLockedCategories = {},
            openSubtitles = null,
        )
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    // The save resumes on the main thread between its steps, so each check lets it catch up first.
    private fun waitUntilSaved(saved: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            compose.waitForIdle()
            if (saved()) return
            check(System.currentTimeMillis() < until) { "what was saved still isn't there after 5 seconds" }
            Thread.sleep(10)
        }
    }

    private fun feed(url: String) =
        runBlocking { graph.epgRepository.observeFeeds().first() }.firstOrNull { it.url == url }

    /** The keyboard over an entry screen, which is a window of its own over Settings. */
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
}
