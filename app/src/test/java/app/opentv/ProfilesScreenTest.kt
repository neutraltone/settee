/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers
import app.opentv.core.ServiceLocator
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.repo.Profiles
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.settings.ProfilesScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Profiles, opened from the Rail's Profile: a row per Profile and one to add another (#168). */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfilesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }
    private val app: Application = ApplicationProvider.getApplicationContext()

    /**
     * A graph of its own, with its own database, rather than the app's: a test that ends while a
     * Profile write is under way leaves that write holding Profiles' lock for good, which would stop
     * every test after it in the app's shared one.
     */
    private val graph = ServiceLocator.Graph(
        app,
        settingsFile = "profiles-screen-test",
        openDatabase = {
            Room.inMemoryDatabaseBuilder(app, OpenTvDatabase::class.java).allowMainThreadQueries().build()
        },
    )

    @After
    fun close() = graph.database.close()

    private lateinit var viewModel: ProfilesViewModel

    /**
     * The Profile called [name], as the screen's list has it. Never asked of Profiles itself with
     * runBlocking: a rename or removal holds its lock while it waits for this, the main thread, so
     * blocking here waits for that and it for this, and the run never ends.
     */
    private fun profile(name: String) = viewModel.profiles.value.firstOrNull { it.name == name }

    private fun show(vararg names: String) {
        // A name already there is refused, which leaves it as it is.
        runBlocking { names.forEach { graph.profiles.create(it) } }
        graph.settings.setActiveProfile(Profiles.BASE_PROFILE_ID)
        viewModel = ProfilesViewModel(app, graph)
        compose.setTvContent {
            view = LocalView.current
            ProfilesScreen(onBack = {}, viewModel = viewModel)
        }
        compose.waitUntil(5_000) {
            viewModel.profiles.value.any { it.id == Profiles.BASE_PROFILE_ID } &&
                (names.toList() + baseName()).all { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        // The list's first Highlight lands a frame after it loads.
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun baseName() = viewModel.profiles.value.first { it.id == Profiles.BASE_PROFILE_ID }.name

    private fun press(vararg keys: Int) = keys.forEach(device::remote)

    private fun pressInPanel(text: String, key: Key) {
        val row = hasText(text) and hasAnyAncestor(isDialog())
        compose.waitUntil(5_000) { compose.onAllNodes(row and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun holdSelectOn(text: String) {
        compose.onNodeWithText(text).requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            advanceEventTime(1_000)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
    }

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

    @Test
    fun `Profiles has a row per Profile, the one watching marked, and an Add a profile row`() {
        show("Sam")

        compose.onNode(isFocused()).assertExists()
        compose.onNode(hasText(baseName()) and hasText("Watching now")).assertExists()
        compose.onNodeWithText("Add a profile").assertExists()
        captureScreen("profiles")
    }

    @Test
    fun `OK on a Profile renames it on an entry screen, which refuses another Profile's name`() {
        show("Rename me 168", "Taken 168")

        compose.onNodeWithText("Rename me 168").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            assertThat(textShownBy()).isEqualTo("Rename me 168")
            replaceAll("Taken 168")
            compose.onNodeWithText("Another profile is already called Taken 168.").assertExists()
            pressAction()
            replaceAll("Renamed 168")
            pressAction()
        }

        compose.waitUntil(5_000) { profile("Renamed 168") != null }
        assertThat(profile("Rename me 168")).isNull()
    }

    @Test
    fun `Add a profile names a new one on an entry screen, and switches to it`() {
        show()

        compose.onNodeWithText("Add a profile").requestFocus()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entryKeyboard().run {
            type("New one 168")
            pressAction()
        }

        compose.waitUntil(5_000) { profile("New one 168") != null }
        compose.waitUntil(5_000) { graph.settings.activeProfileId.value == profile("New one 168")?.id }
    }

    @Test
    fun `holding Select on a Profile switches to it from its options`() {
        show("Switch to 168")

        holdSelectOn("Switch to 168")
        pressInPanel("Watch as Switch to 168", Key.DirectionCenter)

        assertThat(graph.settings.activeProfileId.value).isEqualTo(profile("Switch to 168")?.id)
        compose.onNode(hasText("Switch to 168") and hasText("Watching now")).assertExists()
    }

    @Test
    fun `removing a Profile asks first, starting on Keep it, and the first Profile can't be removed`() {
        show("Remove me 168")

        holdSelectOn("Remove me 168")
        pressInPanel("Watch as Remove me 168", Key.DirectionDown)
        pressInPanel("Remove", Key.DirectionCenter)
        compose.onNode(hasText("Keep it") and isFocused()).assertExists()
        captureScreen("profiles_remove_question")
        pressInPanel("Keep it", Key.DirectionUp)
        pressInPanel("Yes, remove Remove me 168", Key.DirectionCenter)
        compose.waitUntil(5_000) { profile("Remove me 168") == null }
        // Its row has gone, and the Highlight goes back into the list rather than vanishing.
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }

        val base = baseName()
        holdSelectOn(base)
        compose.onNode(hasText("Watch as $base") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Remove") and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test
    fun `Back leaves Profiles`() {
        var left = 0
        viewModel = ProfilesViewModel(app, graph)
        compose.setTvContent {
            view = LocalView.current
            ProfilesScreen(onBack = { left++ }, viewModel = viewModel)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }

        Espresso.pressBack()

        assertThat(left).isEqualTo(1)
    }
}
