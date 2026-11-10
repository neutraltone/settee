/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.baselineprofile

import android.util.Log
import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks Settee's main journeys with the remote's keys, as a viewer would, so the baseline profile
 * holds the code they run: cold start into Home, along and between Home's rows, Live TV's guide,
 * the Movies and Shows rows, and a movie's and a series' details page.
 *
 * Run it with `./gradlew :app:generateBaselineProfile` against an Android 13+ emulator
 * (docs/PERFORMANCE.md). Before the profiled passes it adds the [FakeXtreamPanel] as the provider
 * and loads its catalogue; every check names the journey that broke, and a failure leaves the
 * screen and its window hierarchy in
 * baselineprofile/build/outputs/connected_android_test_additional_output.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    // The build passes the profiled build's id (baselineprofile/build.gradle.kts), which differs
    // from the release app's so a run can never replace or uninstall a viewer's install.
    private val packageName =
        InstrumentationRegistry.getArguments().getString("targetAppId")
            ?: error("no targetAppId: run this through ./gradlew :app:generateBaselineProfile")

    @Test
    fun generate() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        FakeXtreamPanel().use { panel ->
            device.evidenceOnFailure { device.setUp(panel.url) }
            rule.collect(packageName, maxIterations = ITERATIONS, stableIterations = STABLE_ITERATIONS) {
                device.evidenceOnFailure {
                    pressHome()
                    startActivityAndWait()
                    device.reachHome()
                    device.movies()
                    device.shows()
                    device.home()
                    device.liveGuide()
                }
            }
        }
    }

    /**
     * Adds the provider and loads its catalogue before the profiled passes, so code a viewer runs
     * once per install stays out of the profile. A profiled pass then starts the way every later
     * launch does, on Home with a catalogue.
     */
    private fun UiDevice.setUp(url: String) {
        executeShellCommand("pm clear $packageName")
        executeShellCommand("am start -W -n $packageName/app.opentv.MainActivity")
        check(wait(Until.hasObject(FIRST_RUN), LOAD_TIMEOUT)) { "the first-run setup screen never opened" }
        addProvider(url)
        reachHome()
        // Movies and Shows load on first opening, both at once.
        openTab("Movies")
        check(wait(Until.hasObject(By.text("Movie 1")), LOAD_TIMEOUT)) { "Movies never loaded" }
        openTab("Shows")
        check(wait(Until.hasObject(By.text("Show 1")), LOAD_TIMEOUT)) { "Shows never loaded" }
        executeShellCommand("am force-stop $packageName")
    }

    // The entry form rather than the phone's setup page, whose link only the QR code carries. Typing
    // a URL with the on-screen keyboard is no journey worth profiling, so the fields are set
    // directly; everything after this is the remote's keys.
    private fun UiDevice.addProvider(url: String) {
        findObject(FIRST_RUN).click()
        check(wait(Until.hasObject(By.clazz(EDIT_TEXT)), TIMEOUT)) { "no provider form" }
        // The fields carry no labels for UiAutomator, so they're known by their order. A form that
        // no longer has these four would have its fields filled wrongly, so stop and say so.
        val fields = findObjects(By.clazz(EDIT_TEXT))
        check(fields.size == XTREAM_FIELDS) {
            "the provider form has ${fields.size} fields, not name, address, username and password"
        }
        fields[1].text = url
        fields[2].text = "u"
        fields[3].text = "p"
        findObject(By.scrollable(true)).scroll(Direction.DOWN, 1f)
        val save = wait(Until.findObject(By.text("Save and load")), TIMEOUT)
        check(save?.isEnabled == true) { "Save and load isn't enabled: the provider form wasn't filled as expected" }
        save.click()
    }

    /** Home, once the channels are in: under What's new on a fresh install. */
    private fun UiDevice.reachHome() {
        val whatsNew = By.text("Got it")
        check(waitForAny(RAIL_LIVE, whatsNew, timeout = LOAD_TIMEOUT)) { "the channels never loaded" }
        // OK, not a tap: a tap leaves the app in touch mode, with nothing highlighted. Its button
        // takes the highlight a moment after it opens, so OK may need another go.
        repeat(OK_ATTEMPTS) {
            if (hasObject(whatsNew)) {
                pressDPadCenter()
                wait(Until.gone(whatsNew), POLL_MILLIS * 2)
            }
        }
        check(!hasObject(whatsNew)) { "What's new didn't close on OK" }
        check(wait(Until.hasObject(RAIL_LIVE), TIMEOUT)) { "Home never opened" }
    }

    /** Along and down Movies' rows into a film. */
    private fun UiDevice.movies() {
        openTab("Movies")
        check(wait(Until.hasObject(By.text("Movie 1")), LOAD_TIMEOUT)) { "Movies never showed its rows" }
        walkRows("Movies", card = "Movie ")
        openDetails(By.text("Watch now"), "movie")
    }

    /** Shows the same way, into a series, whose episodes load as it opens. */
    private fun UiDevice.shows() {
        openTab("Shows")
        check(wait(Until.hasObject(By.text("Show 1")), LOAD_TIMEOUT)) { "Shows never showed its rows" }
        walkRows("Shows", card = "Show ")
        openDetails(By.text("Episode 1"), "series")
    }

    /** OK on the highlighted card, down the details page, and back. */
    private fun UiDevice.openDetails(loaded: BySelector, kind: String) {
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        check(wait(Until.hasObject(loaded), LOAD_TIMEOUT)) { "the $kind details page never opened" }
        press(KeyEvent.KEYCODE_DPAD_DOWN, DETAILS_DOWN)
        press(KeyEvent.KEYCODE_DPAD_UP, DETAILS_DOWN)
        pressBack()
        check(wait(Until.gone(loaded), TIMEOUT)) { "Back didn't leave the $kind details page" }
    }

    /** Home's rows of new titles: along one, then between them. */
    private fun UiDevice.home() {
        openTab("Home")
        check(wait(Until.hasObject(By.text("Movie 1")), LOAD_TIMEOUT)) { "Home has no rows" }
        walkRows("Home", card = "Movie ")
    }

    /**
     * From the rail into a screen of rows: along one and partway back, then down between rows and
     * along, and back up, ending on a card whose title starts with [card]. Never Left after Down: a
     * row it moves into may start on its first card, and Left from there is the rail.
     */
    private fun UiDevice.walkRows(screen: String, card: String) {
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        downToCard(card)
        check(onCard(card)) { "Right from the rail didn't reach $screen's rows" }
        press(KeyEvent.KEYCODE_DPAD_RIGHT, ALONG)
        press(KeyEvent.KEYCODE_DPAD_LEFT, ALONG / 2)
        press(KeyEvent.KEYCODE_DPAD_DOWN, BETWEEN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, BETWEEN)
        press(KeyEvent.KEYCODE_DPAD_UP, BETWEEN)
        downToCard(card)
        check(onCard(card)) { "the highlight left $screen's rows" }
    }

    /**
     * Down until the highlight is on a card: past Movies' and Shows' search box and category chips,
     * which Up can reach because presses drop while a row scrolls in and the counts come out short.
     * The accessibility tree trails the screen, so each look waits for it to catch up.
     */
    private fun UiDevice.downToCard(title: String) {
        repeat(BETWEEN + 1) {
            Thread.sleep(SETTLE_MILLIS)
            if (onCard(title)) return
            press(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        Thread.sleep(SETTLE_MILLIS)
    }

    private fun UiDevice.onCard(title: String): Boolean = onHighlight { node ->
        node.contentDescription?.startsWith(title) == true || node.hasObject(By.descStartsWith(title))
    } == true

    /** Down and back up the guide's channels, and along a channel's programmes. */
    private fun UiDevice.liveGuide() {
        openTab("Live TV")
        check(wait(Until.hasObject(By.textStartsWith("Channel 1")), LOAD_TIMEOUT)) { "the guide never filled" }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_DOWN, GUIDE_DOWN)
        press(KeyEvent.KEYCODE_DPAD_UP, GUIDE_DOWN / 2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, ALONG / 2)
        press(KeyEvent.KEYCODE_DPAD_LEFT, ALONG / 2)
    }

    /** Left onto the rail, then up or down it to [label], the way a remote gets to another tab. */
    private fun UiDevice.openTab(label: String) {
        repeat(RAIL_REACH) {
            if (railIndex() < 0) press(KeyEvent.KEYCODE_DPAD_LEFT)
        }
        val target = RAIL.indexOf(label)
        repeat(RAIL.size * 2) {
            // The highlight reads as nowhere for a moment while the rail opens, so look again.
            var at = railIndex()
            val until = System.currentTimeMillis() + SETTLE_MILLIS
            while (at < 0 && System.currentTimeMillis() < until) {
                Thread.sleep(PRESS_POLL_MILLIS)
                at = railIndex()
            }
            check(at >= 0) { "the highlight never reached the rail on the way to $label" }
            if (at == target) {
                pressDPadCenter()
                waitForIdle()
                return
            }
            press(if (at < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
        error("never reached $label on the rail")
    }

    private fun UiDevice.railIndex(): Int =
        onHighlight { node -> RAIL.indexOfFirst { node.hasObject(By.desc(it)) } } ?: -1

    /**
     * Reads the node with the highlight. Its ancestors, the Compose host view among them, also report
     * focus and contain everything on screen, so it's the innermost one.
     */
    private fun <T> UiDevice.onHighlight(read: (UiObject2) -> T): T? = runCatching {
        findObjects(By.focused(true))
            .minByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            ?.let(read)
        // A node can leave the screen between being found and being read, mid-scroll.
    }.getOrNull()

    /**
     * Presses [keyCode] [times] times, each once the last has settled: the highlight has moved and
     * stopped moving, or [SETTLE_MILLIS] have passed at an edge. A fixed pace drops presses, since
     * a row still scrolling into view has nothing to take the highlight yet, and a card grows as it
     * takes the highlight, so the first change seen isn't the end of the move.
     */
    private fun UiDevice.press(keyCode: Int, times: Int = 1) {
        repeat(times) {
            val before = highlight()
            pressKeyCode(keyCode)
            val until = System.currentTimeMillis() + SETTLE_MILLIS
            var last = before
            var steady = 0
            while (System.currentTimeMillis() < until && (last == before || steady < STEADY_READS)) {
                Thread.sleep(PRESS_POLL_MILLIS)
                val now = highlight()
                steady = if (now == last) steady + 1 else 0
                last = now
            }
        }
        waitForIdle()
        // Where each press left the highlight, for fixing a journey (docs/PERFORMANCE.md).
        val title = onHighlight { node ->
            node.contentDescription ?: node.text ?: node.findObject(By.descStartsWith(""))?.contentDescription
        }
        Log.d(LOG_TAG, "key $keyCode x$times -> $title ${highlight()}")
    }

    private fun UiDevice.highlight(): String? = onHighlight { it.visibleBounds.toShortString() }

    private fun UiDevice.waitForAny(vararg selectors: BySelector, timeout: Long): Boolean {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (selectors.any(::hasObject)) return true
            Thread.sleep(POLL_MILLIS)
        }
        return false
    }

    /** Runs [block], leaving the screen and its window hierarchy behind if it fails. */
    private inline fun UiDevice.evidenceOnFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            val dir = File(OUTPUT_DIR).apply { mkdirs() }
            runCatching { dumpWindowHierarchy(File(dir, "failure.xml")) }
            runCatching { takeScreenshot(File(dir, "failure.png")) }
            throw e
        }
    }

    private companion object {
        const val LOG_TAG = "ProfileGen"

        // Gradle pulls this directory back into baselineprofile/build/outputs.
        const val OUTPUT_DIR = "/sdcard/Android/media/app.opentv.baselineprofile/additional_test_output"
        const val EDIT_TEXT = "android.widget.EditText"
        const val XTREAM_FIELDS = 4
        const val ITERATIONS = 5
        const val STABLE_ITERATIONS = 2
        const val TIMEOUT = 10_000L
        const val LOAD_TIMEOUT = 120_000L
        const val POLL_MILLIS = 500L
        const val SETTLE_MILLIS = 1_000L
        const val PRESS_POLL_MILLIS = 50L
        const val STEADY_READS = 3
        const val OK_ATTEMPTS = 5
        const val RAIL_REACH = 16
        const val ALONG = 8
        const val BETWEEN = 3
        const val DETAILS_DOWN = 6
        const val GUIDE_DOWN = 20
        val RAIL = listOf("Home", "Live TV", "Movies", "Shows", "Recordings")
        val RAIL_LIVE: BySelector = By.desc("Live TV")
        val FIRST_RUN: BySelector = By.text("Type it on the TV instead")
    }
}
