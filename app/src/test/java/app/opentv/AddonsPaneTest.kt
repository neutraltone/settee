/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import app.opentv.data.model.StremioAddon
import app.opentv.pairing.PairingServer
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Add-ons: the viewer's add-ons, and adding one by its address. Placeholder addresses only. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddonsPaneTest {

    private val compose = createComposeRule()

    /** The real network, but for a placeholder add-on at [ADDON_HOST], whose manifest is [FILMS_MANIFEST]. */
    private val network = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host != ADDON_HOST) return@addInterceptor chain.proceed(request)
        val manifest = if (request.url.encodedPath.startsWith("/shows")) SHOWS_MANIFEST else FILMS_MANIFEST
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(manifest.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    @get:Rule
    val testGraph = TestGraph(compose, http = network)

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = testGraph.graph

    private fun show(memory: SettingsMemory) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(memory = memory, onBack = {}, openSubtitles = null)
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    /** A press inside a side panel, on its row showing [text], once that row has the Highlight. */
    private fun pressInPanel(text: String, key: Key) {
        val row = hasText(text) and hasAnyAncestor(isDialog())
        compose.onAllNodes(row and isFocused()).onFirst().assertExists()
        compose.onNode(row).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `OK on an add-on opens its details, where it's removed after asking`() {
        graph.settings.addStremioAddon(StremioAddon("https://example.com/films168/manifest.json", "Films add-on 168"))
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        captureScreen("settings_addons")

        compose.onNodeWithText("Films add-on 168").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Films add-on 168") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("example.com") and hasAnyAncestor(isDialog())).assertExists()
        pressInPanel("Remove", Key.DirectionCenter)
        pressInPanel("Keep it", Key.DirectionUp)
        pressInPanel("Yes, remove Films add-on 168", Key.DirectionCenter)

        compose.runOnIdle {
            assertThat(graph.settings.stremioAddons.value.map { it.name }).doesNotContain("Films add-on 168")
        }
    }

    @Test
    fun `an add-on address that's refused keeps its window open, with what was typed and why`() {
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Add an add-on").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val keyboard = compose.entryKeyboard()
        keyboard.run {
            assertThat(connection().first.inputType)
                .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            // Nothing listens on port 9 here, so the connection is refused.
            type("http://127.0.0.1:9/manifest.json")
            pressAction()
        }

        val inWindow = hasAnyAncestor(isDialog())
        compose.onAllNodes(hasText("Can't reach the add-on.", substring = true) and inWindow).onFirst().assertExists()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("Connect") and inWindow, useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("That address didn't give an add-on", substring = true).assertDoesNotExist()
        assertThat(keyboard.textShownBy()).isEqualTo("http://127.0.0.1:9/manifest.json")
        assertThat(keyboard.highlighted()).isTrue()
        assertThat(graph.settings.stremioAddons.value).isEmpty()
        captureScreen("settings_addons_failed")
    }

    @Test
    fun `an add-on's install link is added, and its window closes once it's in the list`() {
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Add an add-on").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.entryKeyboard().run {
            type("stremio://$ADDON_HOST/films/manifest.json")
            pressAction()
        }

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Films add-on").assertExists()
        compose.onNode(hasText("Add an add-on") and isFocused()).assertExists()
        assertThat(graph.settings.stremioAddons.value.map { it.manifestUrl })
            .containsExactly("https://$ADDON_HOST/films/manifest.json")
        captureScreen("settings_addons_added")
    }

    /** The phone server once this window has started it, or the test is skipped where there's no LAN to serve on. */
    private fun phoneSession(): PairingServer.Session {
        val server = graph.pairingServer
        // The window waits a moment before starting it, for the screen before to have stopped it.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertThat(server.state.value).isNotEqualTo(PairingServer.State.Idle)
        val session = (server.state.value as? PairingServer.State.Listening)?.session
        assumeTrue("needs a private LAN address to serve on", session != null)
        return session!!
    }

    /** Posts [fields] to [path] on the phone's page, as the viewer's phone would. */
    private fun fromPhone(session: PairingServer.Session, path: String, vararg fields: Pair<String, String>) {
        val form = FormBody.Builder().add("t", session.url.substringAfter("t="))
        fields.forEach { (name, value) -> form.add(name, value) }
        val request = Request.Builder().url("http://${session.host}:${session.port}$path").post(form.build()).build()
        OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().close()
    }

    private fun openAddWindow() {
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Add an add-on").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun `Add an add-on offers the phone's QR code beside the address box, and Back stops it`() {
        openAddWindow()
        phoneSession()

        val inWindow = hasAnyAncestor(isDialog())
        compose.onNode(hasContentDescription("QR code for adding add-ons from your phone") and inWindow)
            .assertExists()
        compose.onNode(hasText("Camera won't scan it?", substring = true) and inWindow).assertExists()
        // The code takes no Highlight: it stays on the box, with Save below.
        val window = compose.entryKeyboard()
        assertThat(window.highlighted()).isTrue()
        captureScreen("settings_addons_phone")
        window.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNode(hasText("Save") and isFocused() and inWindow).assertExists()
        window.remote(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(window.highlighted()).isTrue()

        window.remote(KeyEvent.KEYCODE_BACK)

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertThat(graph.pairingServer.state.value).isEqualTo(PairingServer.State.Idle) }
    }

    @Test
    fun `add-ons sent from the phone are listed under the code, and in the list behind, until Done`() {
        openAddWindow()
        val session = phoneSession()

        CoroutineScope(graph.dispatchers.io).launch {
            fromPhone(session, "/addon", "url" to "$ADDON_HOST/films")
            fromPhone(session, "/addon", "url" to "stremio://$ADDON_HOST/shows/manifest.json")
        }
        compose.waitForIdle()

        val inWindow = hasAnyAncestor(isDialog())
        compose.onNode(hasText("Added Films add-on") and inWindow).assertIsDisplayed()
        compose.onNode(hasText("Added Shows add-on") and inWindow).assertIsDisplayed()
        assertThat(graph.settings.stremioAddons.value.map { it.manifestUrl }).containsExactly(
            "https://$ADDON_HOST/films/manifest.json",
            "https://$ADDON_HOST/shows/manifest.json",
        )
        captureScreen("settings_addons_phone_two")

        CoroutineScope(graph.dispatchers.io).launch { fromPhone(session, "/done") }
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("Shows add-on").assertExists()
        compose.onNode(hasText("Add an add-on") and isFocused()).assertExists()
        // The graph's settings are the process's, which the other tests here start from.
        graph.settings.stremioAddons.value.forEach { graph.settings.removeStremioAddon(it.manifestUrl) }
    }

    @Test
    fun `torrents are off until turned on, after a panel says who can see your address`() {
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val torrents = hasText("Torrents") and isToggleable()
        compose.onNode(torrents).assertIsOff()
        assertThat(graph.settings.torrentsEnabled.value).isFalse()

        compose.onNode(torrents).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("internet address", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        captureScreen("settings_addons_torrents_panel")
        // The Highlight starts on keeping them off, so a stray OK changes nothing.
        pressInPanel("Keep them off", Key.DirectionUp)
        pressInPanel("Turn on torrents", Key.DirectionCenter)

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(torrents).assertIsOn()
        assertThat(graph.settings.torrentsEnabled.value).isTrue()

        // Turning them off needs no asking.
        compose.onNode(torrents).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(torrents).assertIsOff()
        assertThat(graph.settings.torrentsEnabled.value).isFalse()
    }

    private companion object {
        const val ADDON_HOST = "addons.example.com"
        const val SHOWS_MANIFEST =
            """{"id": "org.example.shows", "name": "Shows add-on", "resources": ["stream"], "types": ["series"]}"""
        const val FILMS_MANIFEST =
            """{"id": "org.example.films", "name": "Films add-on", "resources": ["stream"], "types": ["movie"]}"""
    }
}
