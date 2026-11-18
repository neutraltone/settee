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
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StremioAddon
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

    // An address with no host to name it by isn't saved at all now (#471), so the one to check is a
    // login in an address typed without its scheme: named by its host, the login nowhere on screen.
    @Test
    fun `a guide whose address carries a login is named by its host, not its address`() {
        show(SettingsMemory(SettingsSection.Guide))
        waitFor(EpgRepository.BUILT_IN_FEEDS.first().first)
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Add your own guide").requestFocus()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.entryKeyboard().run {
            type("guide487.example.com/xmltv.php?password=p")
            pressAction()
        }

        val saved = "http://guide487.example.com/xmltv.php?password=p"
        compose.waitUntilSaved { graph.epgRepository.savedFeed(saved) != null }
        assertThat(graph.epgRepository.savedFeed(saved)?.name).isEqualTo("guide487.example.com")
        compose.onNode(hasText("password", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `an add-on whose address has no host shows no address line in its details`() {
        graph.settings.addStremioAddon(StremioAddon("odd add-on 487 password=p", "Odd add-on 487"))
        show(SettingsMemory(SettingsSection.Addons))
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Odd add-on 487").requestFocus()
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Remove") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()
        }

        val shown = compose.onAllNodes(hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        assertThat(shown).contains("Odd add-on 487")
        assertThat(shown).doesNotContain("")
        assertThat(shown.filter { "password" in it }).isEmpty()
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
}
