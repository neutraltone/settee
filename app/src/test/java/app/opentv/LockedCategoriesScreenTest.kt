/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.CategoryGroup
import app.opentv.ui.settings.LockedCategoriesScreen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Locked categories, the Parental lock's own screen: a switch per category, many more than fit on
 * the screen on a big provider, so the list is reached with the remote (#267).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class LockedCategoriesScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private var left = false

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    // AppSettings is one per process, so the session's lock state outlives each test: every test
    // starts locked, with no PIN, and leaves it that way (clearing the PIN also unlocks).
    @Before
    fun locked() {
        graph.settings.clearPin()
        graph.settings.setHiddenUnlocked(false)
    }

    @After
    fun close() {
        graph.settings.setHiddenCategories(emptySet())
        locked()
        graph.database.close()
    }

    /** Far more than fit on the screen at once: the last is well below it. */
    private val genres = listOf(
        "Arts", "Business", "Cartoons", "Documentaries", "Drama", "Education", "Entertainment", "Fashion",
        "Food", "Gaming", "History", "Kids", "Lifestyle", "Movies", "Music", "Nature", "Religion", "Science",
        "Shopping", "Travel", "Weather", "Comedy", "Crime", "Cooking", "Motors", "Outdoors", "Pets",
        "Politics", "Reality", "Classics",
    )

    /** News, a locked category, Sport, then the genres. */
    private val names = listOf("News", "UK| Adult", "Sport") + genres

    private fun library() = runBlocking {
        val db = graph.database
        db.sources().insert(Source(id = 1, name = "Provider A", kind = SourceKind.XTREAM, url = "https://example.com"))
        db.categories().upsertAll(
            names.mapIndexed { index, name -> Category("${index + 10}", 1, name, StreamKind.LIVE, sortIndex = index) },
        )
        graph.settings.setHiddenCategories(setOf("adult"))
    }

    private fun setScreen(
        categories: Flow<List<CategoryGroup>> = graph.channelLineup.categoryGroups(includeLocked = true),
    ) {
        compose.setTvContent {
            view = LocalView.current
            LockedCategoriesScreen(onBack = { left = true }, categories = categories, settings = graph.settings)
        }
    }

    private fun show() {
        setScreen()
        awaitHighlight("News", 10_000)
    }

    private fun awaitHighlight(label: String, millis: Long) = compose.waitUntil(millis) {
        compose.onAllNodes(hasText(label) and isFocused()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun press(key: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun row(label: String) = compose.onNode(hasText(label) and hasClickAction())

    /** What Live TV's Categories Sidebar lists: locked categories drop out of it. */
    private fun liveSidebar(): List<String> = runBlocking {
        graph.channelLineup.categoryGroups().first()
    }.map { it.label }

    @Test
    fun `it opens on the first category, each one's switch on when it's locked`() {
        library()
        show()

        compose.onNodeWithText("Locked categories").assertExists()
        compose.onNodeWithText("also locked on your other devices", substring = true).assertExists()
        row("News").assertIsFocused()
        row("News").assertIsOff()
        row("Adult").assertIsOn()
        captureScreen("locked_categories")
    }

    @Test
    fun `OK on a category far down the list locks it, and OK again unlocks it`() {
        library()
        show()
        assertThat(liveSidebar()).contains("Classics")

        // Classics is the last row.
        press(Key.DirectionDown, times = names.size - 1)
        row("Classics").assertIsFocused()
        press(Key.DirectionCenter)

        row("Classics").assertIsOn()
        assertThat(liveSidebar()).doesNotContain("Classics")
        captureScreen("locked_categories_scrolled")

        press(Key.DirectionCenter)

        row("Classics").assertIsOff()
        assertThat(liveSidebar()).contains("Classics")
    }

    @Test
    fun `with no categories yet, it says where they come from`() {
        setScreen()

        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("No categories yet", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        captureScreen("locked_categories_empty")
    }

    @Test
    fun `with a PIN set but not entered, as when Android brings the app back on this screen, it leaves at once`() {
        library()
        graph.settings.setPin("1234")
        graph.settings.forgetPinEntry()
        setScreen()

        compose.runOnIdle { assertThat(left).isTrue() }
        compose.onNodeWithText("Classics").assertDoesNotExist()
    }

    @Test
    fun `while the categories are still being read it doesn't say there are none, then opens on the first`() {
        val categories = MutableSharedFlow<List<CategoryGroup>>()
        setScreen(categories)
        compose.waitForIdle()

        compose.onNodeWithText("No categories yet", substring = true).assertDoesNotExist()

        runBlocking { categories.emit(listOf(CategoryGroup("news", "News", listOf("10")))) }

        awaitHighlight("News", 5_000)
        compose.onNodeWithText("No categories yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Up from the first category reaches Find a category, and Down comes back to the list`() {
        library()
        show()

        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        assertThat(device.highlighted()).isTrue()
        assertThat(device.showing).isFalse()

        device.remote(KeyEvent.KEYCODE_DPAD_DOWN)
        assertThat(device.highlighted()).isFalse()
        row("News").assertIsFocused()
    }

    @Test
    fun `Find a category narrows the list by name, whatever the case, and says how many are showing`() {
        library()
        show()
        device.remote(KeyEvent.KEYCODE_DPAD_UP)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)

        device.type("CO")

        row("Comedy").assertExists()
        row("Cooking").assertExists()
        compose.onAllNodesWithText("News").assertCountEquals(0)
        compose.onAllNodesWithText("Sport").assertCountEquals(0)
        compose.onNodeWithText("2 of ${names.size}").assertExists()
        captureScreen("locked_categories_find")
        device.close()
    }

    @Test
    fun `Back leaves it`() {
        library()
        show()

        Espresso.pressBack()

        compose.runOnIdle { assertThat(left).isTrue() }
    }
}
