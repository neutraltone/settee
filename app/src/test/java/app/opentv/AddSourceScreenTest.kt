/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.onboarding.AddSourceScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A TV-sized screen that isn't in TV mode unless a test says so: only a TV offers setting up from a phone.
// Plain Application: OpenTvApp schedules WorkManager on start, which the screen doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddSourceScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private fun show(tv: Boolean = false, editing: Long? = null) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        if (tv) shadowOf(app.packageManager).setSystemFeature("android.software.leanback", true)
        val viewModel = SourcesViewModel(app)
        compose.setTvContent {
            view = LocalView.current
            AddSourceScreen(viewModel = viewModel, onFinished = {}, editingSourceId = editing)
        }
    }

    @Test
    fun `editing a provider opens its kind's form filled in, and saving keeps its id`() {
        val graph = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())
        // Port 9 refuses at once, so the refresh after saving fails fast rather than reaching out.
        val id = runBlocking {
            graph.sourceRepository.save(
                Source(name = "Films", kind = SourceKind.M3U, url = "http://127.0.0.1:9/list.m3u"),
            )
        }
        show(tv = true, editing = id)

        compose.onNodeWithText("Edit provider").assertExists()
        compose.onNodeWithText(PHONE).assertDoesNotExist()
        compose.onNodeWithText("Playlist URL").assertExists()
        compose.onNodeWithText("Username").assertDoesNotExist()
        assertThat(device.textShownBy(device.box(0))).isEqualTo("Films")
        assertThat(device.textShownBy(device.box(1))).isEqualTo("http://127.0.0.1:9/list.m3u")
        captureScreen("add_source_edit")

        device.replaceAll("Old films", device.box(0))
        compose.onNodeWithText("Save and load").performClick()

        compose.waitUntil(5_000) { runBlocking { graph.sourceRepository.byId(id)?.name == "Old films" } }
        val named = runBlocking {
            graph.sourceRepository.observeAll().first()
        }.filter { it.name.endsWith("films", ignoreCase = true) }
        assertThat(named.map { it.id }).containsExactly(id)
    }

    private fun showXtreamForm() {
        show()
        compose.onNodeWithText(XTREAM).performClick()
    }

    @Test
    fun `on a TV the page opens with the Highlight on Set up from your phone, and the three ways under it`() {
        show(tv = true)

        compose.onNodeWithText(PHONE).assertIsFocused()
        val tops = listOf(PHONE, XTREAM, M3U, STALKER).map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertThat(tops).isInStrictOrder()
        captureScreen("add_source_page_tv")
    }

    @Test
    fun `off a TV the page has no phone option, and the Highlight starts on the first way of typing it`() {
        show()

        compose.onNodeWithText(PHONE).assertDoesNotExist()
        compose.onNodeWithText(XTREAM).assertIsFocused()
        captureScreen("add_source_page_phone")
    }

    @Test
    fun `Xtream login opens a form of only its fields, and Back returns to it on the page`() {
        opensItsOwnForm(
            option = XTREAM,
            downs = 0,
            shown = listOf("Server address", "Username", "Password"),
            hidden = listOf("Playlist URL", "Portal URL", "MAC address"),
            screenshot = "add_source_form_xtream",
        )
    }

    @Test
    fun `M3U playlist opens a form of only its fields, and Back returns to it on the page`() {
        opensItsOwnForm(
            option = M3U,
            downs = 1,
            shown = listOf("Playlist URL"),
            hidden = listOf("Server address", "Username", "Password", "Portal URL", "MAC address"),
            screenshot = "add_source_form_m3u",
        )
    }

    @Test
    fun `Stalker portal opens a form of only its fields, and Back returns to it on the page`() {
        opensItsOwnForm(
            option = STALKER,
            downs = 2,
            shown = listOf("Portal URL", "MAC address"),
            hidden = listOf("Server address", "Username", "Password", "Playlist URL"),
            screenshot = "add_source_form_stalker",
        )
    }

    @Test
    fun `Set up from your phone opens the QR screen, and Back returns to it on the page`() {
        show(tv = true)

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.onNodeWithText(XTREAM).assertDoesNotExist()
        compose.onNodeWithText("Then enter this code on your phone").assertExists()
        captureScreen("add_source_qr")

        Espresso.pressBack()

        compose.onNodeWithText(PHONE).assertIsFocused()
    }

    /** Opens [option] with the remote from the page, [downs] rows below the first, and checks its form. */
    private fun opensItsOwnForm(
        option: String,
        downs: Int,
        shown: List<String>,
        hidden: List<String>,
        screenshot: String,
    ) {
        show()
        repeat(downs) { device.remote(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(option).assertIsFocused()

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        shown.forEach { compose.onNodeWithText(it).assertExists() }
        hidden.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        // Advanced starts collapsed.
        compose.onNodeWithText("User-Agent").assertDoesNotExist()
        captureScreen(screenshot)

        Espresso.pressBack()

        compose.onNodeWithText(option).assertIsFocused()
    }

    @Test
    fun `the provider password is typed on a keyboard that hides it`() {
        showXtreamForm()

        val (info, _) = device.connection(device.box(PASSWORD))
        captureScreen("add_source_xtream")

        assertThat(info.inputType).isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    }

    // On a phone or tablet there's no OK to press: a tap is how the keyboard opens.
    @Test
    fun `tapping a box opens the keyboard`() {
        showXtreamForm()

        device.tap(device.box(SERVER))

        assertThat(device.showing).isTrue()
    }

    @Test
    fun `OK on a box's Paste button pastes rather than opening the keyboard`() {
        showXtreamForm()
        ApplicationProvider.getApplicationContext<Application>().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(null, "http://example.com"))
        compose.onAllNodesWithContentDescription("Paste")[0].requestFocus()

        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(device.showing).isFalse()
        assertThat(device.textShownBy(device.box(SERVER))).isEqualTo("http://example.com")
    }

    @Test
    fun `a screen reader hears each box's label`() {
        showXtreamForm()

        val heard = compose.runOnIdle { device.box(PASSWORD).createAccessibilityNodeInfo().hintText?.toString() }

        assertThat(heard).isEqualTo("Password")
    }
}

private const val PHONE = "Set up from your phone"
private const val XTREAM = "Xtream login"
private const val M3U = "M3U playlist"
private const val STALKER = "Stalker portal"

private const val SERVER = 1

/** Name, server address, username, password: the order an Xtream login lays them out in. */
private const val PASSWORD = 3
