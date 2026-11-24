/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings.RecordingTarget
import app.opentv.ui.design.ERROR_DETAIL_TAG
import app.opentv.ui.settings.SettingsMemory
import app.opentv.ui.settings.SettingsScreen
import app.opentv.ui.settings.SettingsSection
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Recording: choosing a USB folder where it can't be done, or can't be kept (#486). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingPaneTest {

    private val compose = createComposeRule()

    @get:Rule
    val testGraph = TestGraph(compose)

    private lateinit var view: View
    private val device = DeviceKeyboard(compose) { view }

    private val graph get() = testGraph.graph

    private fun show(registry: ActivityResultRegistry, wrap: (Context) -> Context = { it }) = compose.setTvContent {
        view = LocalView.current
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        CompositionLocalProvider(
            LocalActivityResultRegistryOwner provides owner,
            LocalContext provides wrap(LocalContext.current),
        ) {
            SettingsScreen(
                memory = SettingsMemory(SettingsSection.Recording),
                onBack = {},
                onAddProvider = {},
                onEditProvider = {},
                onOpenChannels = {},
                onOpenLockedCategories = {},
                openSubtitles = null,
            )
        }
    }

    /** Saves to: USB, from the panel Save recordings to opens, its Highlight starting on This TV. */
    private fun chooseUsb() {
        device.remote(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.remote(KeyEvent.KEYCODE_DPAD_CENTER)
        pressInPanel("This TV", Key.DirectionDown)
        pressInPanel("Your network drive", Key.DirectionDown)
        pressInPanel("A USB drive", Key.DirectionCenter)
    }

    /**
     * A press inside the side panel, on its row showing [text], once that row has the Highlight. The
     * panel is a window of its own, which [device] can't reach.
     */
    private fun pressInPanel(text: String, key: Key) {
        val row = hasText(text) and hasAnyAncestor(isDialog())
        compose.onAllNodes(row and isFocused()).onFirst().assertExists()
        compose.onNode(row).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `a TV with no folder picker says so on Save recordings to, and keeps recording to this TV`() {
        // Robolectric's television qualifier doesn't reach UiModeManager, so say it the Stick's way.
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        show(registry { _, _, _ -> throw ActivityNotFoundException() })

        chooseUsb()

        val noted = hasText("Save recordings to") and
            hasText("This TV can't choose a folder on a USB drive", substring = true)
        // The note is on the row the Highlight comes back to, so the remote stays where it was.
        compose.onAllNodes(noted and isFocused()).onFirst().assertExists()
        compose.onNode(hasTestTag(ERROR_DETAIL_TAG) and hasText("ActivityNotFound"), useUnmergedTree = true)
            .assertExists()
        assertThat(graph.settings.recordingTarget.value).isEqualTo(RecordingTarget.INTERNAL)
        captureScreen("recording_usb_no_picker")
    }

    @Test
    fun `a folder whose access couldn't be kept says so, and isn't switched to`() {
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/USB%3ARecordings")
        show(
            registry { registry, requestCode, _ -> registry.dispatchResult(requestCode, folder) },
            wrap = { base -> RefusingGrants(base) },
        )

        chooseUsb()

        val noted = hasText("Save recordings to") and
            hasText("Couldn't keep access to that folder", substring = true)
        compose.onAllNodes(noted and isFocused()).onFirst().assertExists()
        assertThat(graph.settings.recordingTarget.value).isEqualTo(RecordingTarget.INTERNAL)
        assertThat(graph.settings.usbTreeUri.value).isNull()
    }

    private fun registry(launch: (ActivityResultRegistry, Int, Any?) -> Unit) = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) = launch(this, requestCode, input)
    }

    /** Android refusing to keep a picked folder's grant, as a provider that offers none does. */
    private class RefusingGrants(base: Context) : ContextWrapper(base) {
        private val resolver = object : ContentResolver(base) {
            override fun takePersistableUriPermission(uri: Uri, modeFlags: Int): Unit =
                throw SecurityException("No persistable permission grants found")
        }

        override fun getContentResolver(): ContentResolver = resolver
    }
}
