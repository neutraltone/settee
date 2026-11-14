/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.model.StremioAddon
import app.opentv.data.repo.EpgRepository
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import app.opentv.ui.settings.SettingsTags
import app.opentv.update.UpdateChecker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings: sections on the left, the highlighted one's settings beside them (#168). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val secrets = FixedServerSecrets()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private var leftSettings = 0
    private val opened = mutableListOf<String>()

    private val graph get() = ServiceLocator.get(ApplicationProvider.getApplicationContext<Application>())

    private fun show(memory: SettingsMemory = SettingsMemory()) = compose.setTvContent {
        view = LocalView.current
        SettingsScreen(
            memory = memory,
            onBack = { leftSettings++ },
            onAddProvider = { opened += "add provider" },
            onEditProvider = { opened += "edit provider $it" },
            onOpenChannels = { opened += "channels" },
            onOpenLockedCategories = { opened += "locked categories" },
            // Left out whether or not the build has Settee's key, so every build shows the same sections.
            openSubtitles = null,
        )
    }

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun highlighted() = compose.onNode(isFocused())

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    /** A provider nothing will reach: port 9 refuses at once. */
    private fun saveProvider(name: String): Long = runBlocking {
        graph.sourceRepository.save(Source(name = name, kind = SourceKind.M3U, url = "http://127.0.0.1:9/list.m3u"))
    }

    /**
     * A press inside a side panel, on its row showing [text], once that row has the Highlight. The
     * panel is a window of its own, which [device] can't reach: it presses keys on the screen's window.
     */
    private fun pressInPanel(text: String, key: Key) {
        // A key goes to whatever has the Highlight, so wait for it to reach the row first: a panel
        // moves it there a frame after it opens.
        val row = hasText(text) and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(row and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /**
     * Waits for what a press saved. A condition that only reads the database never lets the main
     * thread run, and the save resumes there between its steps, so on its own it would never finish:
     * each check here lets it catch up first.
     */
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

    private fun holdSelectOn(text: String) {
        compose.onNodeWithText(text).requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
    }

    private fun paneTitle() = compose.onNodeWithTag(SettingsTags.PANE_TITLE)

    @Test
    fun `Settings opens on Providers, with the eleven sections in order and Providers' settings beside them`() {
        show()
        captureScreen("settings_initial")

        highlighted().assertTextContains("Providers")
        paneTitle().assertTextContains("Providers")
        val sections = compose.onAllNodesWithTag(SettingsTags.SECTION).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString("") }
        assertThat(sections).containsExactly(
            "Providers",
            "TV guide",
            "Channels",
            "Display and playback",
            "Recording",
            "Parental lock",
            "Your devices",
            "Add-ons",
            "TMDB",
            "Manage on phone or laptop",
            "About",
        ).inOrder()
        compose.assertSectionWhollyOnScreen("About")
    }

    @Test
    fun `highlighting a section shows its settings straight away, with no OK`() {
        show()

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)

        highlighted().assertTextContains("TV guide")
        paneTitle().assertTextContains("TV guide")
        compose.onNodeWithText("Where your TV guide comes from").assertExists()
    }

    @Test
    fun `Right or OK moves into the pane, and Left comes back to the section`() {
        show(SettingsMemory(SettingsSection.Display))

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Subtitles")
        captureScreen("settings_display")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        highlighted().assertTextContains("Display and playback")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        highlighted().assertTextContains("Subtitles")
    }

    @Test
    fun `Back from the pane goes back to the sections, and Back from the sections leaves Settings`() {
        show(SettingsMemory(SettingsSection.Display))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN)

        Espresso.pressBack()
        highlighted().assertTextContains("Display and playback")
        assertThat(leftSettings).isEqualTo(0)

        Espresso.pressBack()
        assertThat(leftSettings).isEqualTo(1)
    }

    @Test
    fun `OK on a value opens a panel with the current choice ticked, and Back closes it`() {
        show(SettingsMemory(SettingsSection.Display))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)

        // The panel is a window of its own, which keeps its own Highlight: on the ticked choice.
        compose.onNode(hasText("Off") and isSelected() and isFocused()).assertExists()
        captureScreen("settings_value_panel")

        Espresso.pressBack()
        highlighted().assertTextContains("Sleep timer")
    }

    @Test
    fun `Providers has a row for each provider, which OK edits, and an Add a provider row`() {
        val id = saveProvider("Films on 168")
        show()
        waitFor("Films on 168")

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        // Other tests' providers share the database, so this one isn't necessarily first.
        compose.onNodeWithText("Films on 168").requestFocus()
        captureScreen("settings_providers")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(opened).containsExactly("edit provider $id")

        compose.onNodeWithText("Add a provider").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(opened).containsExactly("edit provider $id", "add provider").inOrder()
    }

    @Test
    fun `holding Select on a provider opens its options, and removing it asks first, starting on Keep it`() {
        val id = saveProvider("Old films on 168")
        show()
        waitFor("Old films on 168")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        holdSelectOn("Old films on 168")
        compose.onNode(hasText("Remove") and isFocused()).assertExists()
        assertThat(opened).isEmpty()
        captureScreen("settings_row_options")

        pressInPanel("Remove", Key.DirectionCenter)
        compose.onNode(hasText("Keep it") and isFocused()).assertExists()
        compose.onNodeWithText("Yes, remove Old films on 168").assertExists()
        captureScreen("settings_remove_question")

        Espresso.pressBack()
        compose.onNode(hasText("Old films on 168") and isFocused()).assertExists()
        assertThat(runBlocking { graph.sourceRepository.byId(id) }).isNotNull()

        holdSelectOn("Old films on 168")
        pressInPanel("Remove", Key.DirectionCenter)
        pressInPanel("Keep it", Key.DirectionUp)
        compose.onNode(hasText("Yes, remove Old films on 168") and isFocused()).assertExists()
        pressInPanel("Yes, remove Old films on 168", Key.DirectionCenter)
        waitUntilSaved { runBlocking { graph.sourceRepository.byId(id) } == null }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Old films on 168")).fetchSemanticsNodes().isEmpty() }
        // The row that had the Highlight has gone, so it moves on rather than vanishing with it.
        highlighted().assertExists()
    }

    @Test
    fun `TV guide lists every guide, and Add your own guide asks for its address on an entry screen`() {
        show(SettingsMemory(SettingsSection.Guide))
        waitFor(EpgRepository.BUILT_IN_FEEDS.first().first)

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Update the guide now")
        captureScreen("settings_guide")

        compose.onNodeWithText("Add your own guide").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val entry = entryKeyboard()
        assertThat(entry.connection().first.inputType)
            .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        entry.type("http://127.0.0.1:9/guide168.xml")
        entry.pressAction()

        waitUntilSaved { feed("http://127.0.0.1:9/guide168.xml") != null }
        // Named by its host: a provider's guide link can carry the login.
        assertThat(feed("http://127.0.0.1:9/guide168.xml")?.name).isEqualTo("127.0.0.1")
        compose.onNode(hasText("guide168", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("Add your own guide") and isFocused()).assertExists()
    }

    @Test
    fun `OK on a guide opens its details, which turn it off and remove it`() {
        runBlocking { graph.epgRepository.addCustomFeed("Own guide 168", "http://127.0.0.1:9/own168.xml") }
        show(SettingsMemory(SettingsSection.Guide))
        waitFor("Own guide 168")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        // Scrolled to first: focus scrolls to it too, but sometimes hadn't finished when the screenshot
        // was taken, so the list behind the panel turned up as changed in every PR's screenshots.
        compose.onNodeWithText("Own guide 168").performScrollTo().requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Use this guide") and isFocused()).assertExists()
        compose.onNode(hasText("Own guide 168") and hasAnyAncestor(isDialog())).assertExists()
        captureScreen("settings_guide_details")
        pressInPanel("Use this guide", Key.DirectionCenter)
        waitUntilSaved { feed("http://127.0.0.1:9/own168.xml")?.enabled == false }

        pressInPanel("Use this guide", Key.DirectionDown)
        pressInPanel("Remove", Key.DirectionCenter)
        pressInPanel("Keep it", Key.DirectionUp)
        pressInPanel("Yes, remove Own guide 168", Key.DirectionCenter)
        waitUntilSaved { feed("http://127.0.0.1:9/own168.xml") == null }
        // Its row has gone, so the Highlight goes back into the pane rather than to the sections.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Update the guide now") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        paneTitle().assertTextContains("TV guide")
    }

    @Test
    fun `Recording saves to a network drive once it's picked, with each detail typed on an entry screen`() {
        graph.settings.setRecordingTarget(AppSettings.RecordingTarget.INTERNAL)
        graph.settings.setSmbConfig(host = "", share = "", folder = "", user = "", password = "")
        show(SettingsMemory(SettingsSection.Recording))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Save recordings to")
        captureScreen("settings_recording")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("This TV") and isSelected() and isFocused()).assertExists()
        pressInPanel("This TV", Key.DirectionDown)
        pressInPanel("Your network drive", Key.DirectionCenter)
        assertThat(graph.settings.recordingTarget.value).isEqualTo(AppSettings.RecordingTarget.SMB)

        compose.onNodeWithText("Server").requestFocus()
        val fromPhone = "You can also set this from your phone, in Manage on phone or laptop"
        compose.onNode(hasText("Server") and hasText(fromPhone)).assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type("nas.example.com")
            pressAction()
        }
        assertThat(graph.settings.smbHost.value).isEqualTo("nas.example.com")
        compose.onNode(hasText("Server") and hasText("nas.example.com") and isFocused()).assertExists()

        // Without a shared folder there's nothing to test, and it says so.
        compose.onNodeWithText("Test connection").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Test connection") and hasText("Type the server and shared folder first."))
            .assertExists()

        compose.onNodeWithText("Password").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            assertThat(connection().first.inputType)
                .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            type("p")
            pressAction()
        }
        assertThat(graph.settings.smbPassword.value).isEqualTo("p")
        // A password is never shown, only that there is one.
        compose.onNode(hasText("Password") and hasText("Saved")).assertExists()
    }

    @Test
    fun `Start early is picked from a panel with the current minutes ticked, and the switches flip`() {
        graph.settings.setRecordPadding(2, 5)
        val autoSwitch = graph.settings.recordAutoSwitch.value
        show(SettingsMemory(SettingsSection.Recording))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Start early").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("2 min") and isSelected() and isFocused()).assertExists()
        pressInPanel("2 min", Key.DirectionDown)
        pressInPanel("5 min", Key.DirectionCenter)
        assertThat(graph.settings.recordPadStartMinutes.value).isEqualTo(5)
        assertThat(graph.settings.recordPadEndMinutes.value).isEqualTo(5)

        compose.onNodeWithText("Switch to a recording as it starts").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(graph.settings.recordAutoSwitch.value).isEqualTo(!autoSwitch)
    }

    @Test
    fun `Parental lock shows only Enter your PIN until the PIN is entered, then its settings until the app closes`() {
        graph.settings.setPin("1234")
        graph.settings.forgetPinEntry()
        val memory = SettingsMemory(SettingsSection.Parental)
        var showing by mutableStateOf(true)
        compose.setTvContent {
            view = LocalView.current
            if (showing) SettingsScreen(memory = memory, onBack = { showing = false })
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Enter your PIN")
        compose.onNodeWithText("Show hidden categories now").assertDoesNotExist()
        captureScreen("settings_parental_locked")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            assertThat(connection().first.inputType)
                .isEqualTo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            type("9999")
            compose.onNodeWithText("That isn't the PIN. Try again.").assertExists()
            pressAction()
            replaceAll("1234")
            pressAction()
        }
        waitFor("Show hidden categories now")
        compose.onNodeWithText("Enter your PIN").assertDoesNotExist()
        paneTitle().assertTextContains("Parental lock")
        highlighted().assertTextContains("Show hidden categories now")
        captureScreen("settings_parental")

        // Leaving Settings doesn't lock it again: only closing the app does.
        Espresso.pressBack()
        Espresso.pressBack()
        compose.runOnIdle { assertThat(showing).isFalse() }
        showing = true
        compose.onNodeWithText("Show hidden categories now").assertExists()
    }

    @Test
    fun `Parental lock lists no categories itself, just a Locked categories row that says how many and opens them`() {
        graph.settings.clearPin()
        val provider = saveProvider("Provider A")
        runBlocking {
            graph.database.categories().upsertAll(
                listOf(
                    Category("10", provider, "News", StreamKind.LIVE, sortIndex = 0),
                    Category("11", provider, "UK| Adult", StreamKind.LIVE, sortIndex = 1),
                ),
            )
        }
        graph.settings.setHiddenCategories(setOf("news", "adult"))
        try {
            show(SettingsMemory(SettingsSection.Parental))
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Locked categories").assertExists()
            compose.onNodeWithText("2 locked").assertExists()
            compose.onNodeWithText("News").assertDoesNotExist()

            compose.onNodeWithText("Locked categories").requestFocus()
            press(KeyEvent.KEYCODE_DPAD_CENTER)

            assertThat(opened).containsExactly("locked categories")
        } finally {
            graph.settings.setHiddenCategories(emptySet())
        }
    }

    @Test
    fun `Locked categories counts only the locked categories today's providers list, as its switches show`() {
        graph.settings.clearPin()
        val provider = saveProvider("Provider A")
        runBlocking {
            graph.database.categories().upsertAll(
                listOf(
                    Category("10", provider, "News", StreamKind.LIVE, sortIndex = 0),
                    Category("11", provider, "UK| Adult", StreamKind.LIVE, sortIndex = 1),
                    Category("12", provider, "Weather", StreamKind.LIVE, sortIndex = 2),
                ),
            )
        }
        // Sport was locked on another device, or its provider has since dropped it: it isn't listed here.
        graph.settings.setHiddenCategories(setOf("news", "adult", "sport"))
        try {
            show(SettingsMemory(SettingsSection.Parental))
            press(KeyEvent.KEYCODE_DPAD_RIGHT)

            waitFor("2 locked")
            compose.onNodeWithText("3 locked").assertDoesNotExist()
            captureScreen("settings_parental_locked_count")
            assertThat(graph.settings.hiddenCategories.value).containsExactly("news", "adult", "sport")
        } finally {
            graph.settings.setHiddenCategories(emptySet())
        }
    }

    @Test
    fun `with no PIN, Parental lock shows its settings and sets a PIN typed twice`() {
        graph.settings.clearPin()
        show(SettingsMemory(SettingsSection.Parental))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Show hidden categories now").assertExists()

        compose.onNodeWithText("Set a PIN").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type("2468")
            pressAction()
        }
        compose.onNodeWithText("Type the PIN again").assertExists()
        entryKeyboard().run {
            type("2468")
            pressAction()
        }

        compose.runOnIdle { assertThat(graph.settings.pinIsSet.value).isTrue() }
        assertThat(graph.settings.verifyPin("2468")).isTrue()
        compose.onNodeWithText("Change the PIN").assertExists()
    }

    @Test
    fun `Your devices gets what another device shares from an address typed on an entry screen`() {
        show(SettingsMemory(SettingsSection.Devices))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Share from this device")
        captureScreen("settings_devices")

        compose.onNodeWithText("Get from another device").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            // Without the code, it asks for it rather than letting the other device refuse.
            type("127.0.0.1:9")
            compose.onNodeWithText("Add # and the code the other device shows.").assertExists()
            type("#123456")
            pressAction()
        }

        compose.waitUntil(10_000) {
            val failed = hasText("Get from another device") and hasText("Couldn't get anything", substring = true)
            compose.onAllNodes(failed).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Get from another device") and isFocused()).assertExists()
    }

    @Test
    fun `sharing from this device shows its address and code above Stop sharing, which the Highlight skips`() {
        show(SettingsMemory(SettingsSection.Devices))
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)

        // Sharing needs a network address, which a test machine may not have.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Stop sharing")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("isn't on a network", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assumeTrue(compose.onAllNodes(hasText("Stop sharing")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag(SettingsTags.CODE_BLOCK).assertExists()
        compose.onNode(hasText("Stop sharing") and isFocused()).assertExists()
        captureScreen("settings_devices_sharing")

        press(KeyEvent.KEYCODE_DPAD_UP)
        compose.onNode(hasText("Stop sharing") and isFocused()).assertExists()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag(SettingsTags.CODE_BLOCK).assertDoesNotExist()
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
    fun `Add an add-on asks for its address on an entry screen, and says when it isn't one`() {
        show(SettingsMemory(SettingsSection.Addons))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Add an add-on").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            assertThat(connection().first.inputType)
                .isEqualTo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            type("http://127.0.0.1:9/manifest.json")
            pressAction()
        }

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Add an add-on") and hasText("That isn't an add-on's address", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Add an add-on") and isFocused()).assertExists()
    }

    @Test
    fun `Manage on phone or laptop shows its QR code and short address above Get a new address`() {
        show(SettingsMemory(SettingsSection.Manager))

        // Serving needs a network address, which a test machine may not have.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(SettingsTags.CODE_BLOCK).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty()
        }
        assumeTrue(compose.onAllNodesWithTag(SettingsTags.CODE_BLOCK).fetchSemanticsNodes().isNotEmpty())
        val shown = compose.onAllNodes(hasAnyAncestor(hasTestTag(SettingsTags.CODE_BLOCK)), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() }
            .map { it.text }
        assertThat(shown.any { Regex("""[0-9.]+:[0-9]+/[a-z3-9]{7}""").matches(it) }).isTrue()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Get a new address")
        captureScreen("settings_manager")
    }

    @Test
    fun `About checks for updates from a row, and says when it couldn't, with its detail`() {
        compose.setTvContent {
            view = LocalView.current
            SettingsScreen(
                memory = SettingsMemory(SettingsSection.About),
                onBack = {},
                onOpenFontLicences = { opened += "font licences" },
                checkForUpdate = { UpdateChecker.Outcome.CouldNotCheck("HTTP 503") },
            )
        }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Check for updates")
        compose.onNodeWithText(ATTRIBUTION).assertExists()
        compose.onNodeWithContentDescription("TMDB").assertExists()
        captureScreen("settings_about")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNode(hasText("Check for updates") and hasText("Couldn't check for updates", substring = true))
            .assertExists()
        // The detail goes on its own quiet line, as every error's does.
        compose.onNode(hasText("Check for updates") and hasText("HTTP 503")).assertExists()
        compose.onNode(hasText("You're on the latest version.", substring = true)).assertDoesNotExist()

        compose.onNode(hasText("Fonts")).requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertThat(opened).containsExactly("font licences")
    }

    @Test
    fun `Display and playback no longer has a TMDB row`() {
        show(SettingsMemory(SettingsSection.Display))

        // Only the pane: the sections beside it list TMDB's own.
        compose.onNode(hasText("TMDB", substring = true) and !hasTestTag(SettingsTags.SECTION)).assertDoesNotExist()
    }

    @Test
    fun `Subtitles says which Profile it belongs to, and choosing Off turns them off for that Profile`() {
        runBlocking { graph.profileSettings.setSubtitlesOn(true) }
        show(SettingsMemory(SettingsSection.Display))
        compose.waitUntil(5_000) {
            // Its Profile's name, whichever that is: asking Profiles for it would block on its lock.
            val row = hasText("Subtitles") and hasText("For ", substring = true) and hasText("On")
            compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty()
        }

        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        pressInPanel("On", Key.DirectionUp)
        pressInPanel("Off", Key.DirectionCenter)

        waitUntilSaved { runBlocking { graph.profileSettings.subtitlePreference.first() }?.enabled == false }
    }

    @Test
    fun `where nothing can allow background recording, its row says so and opens nothing`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        BatteryScreens.fireTvStub(app)
        show(SettingsMemory(SettingsSection.Recording))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Recording in the background").requestFocus()
        compose.onNode(
            hasText("Recording in the background") and hasText("Not allowed") and
                hasText("This device has no setting to prevent that.", substring = true),
        ).assertExists()
        captureScreen("settings_recording_background_no_setting")
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    @Test
    fun `where the system can allow background recording, OK on its row opens that screen`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        BatteryScreens.both(app)
        show(SettingsMemory(SettingsSection.Recording))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        compose.onNodeWithText("Recording in the background").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(shadowOf(app).nextStartedActivity.action)
            .isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    @Test
    fun `Channels has one row, which opens the channel manager`() {
        show(SettingsMemory(SettingsSection.Channels))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        highlighted().assertTextContains("Show, hide and star channels")
        captureScreen("settings_channels")

        press(KeyEvent.KEYCODE_DPAD_CENTER)

        assertThat(opened).containsExactly("channels")
    }

    @Test
    fun `leaving Settings and coming back returns to the section it was on`() {
        val memory = SettingsMemory()
        var showing by mutableStateOf(true)
        compose.setTvContent {
            view = LocalView.current
            if (showing) SettingsScreen(memory = memory, onBack = { showing = false })
        }
        press(
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
        Espresso.pressBack()
        compose.runOnIdle { assertThat(showing).isFalse() }

        showing = true

        highlighted().assertTextContains("Recording")
        paneTitle().assertTextContains("Recording")
    }

    private companion object {
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
    }
}
