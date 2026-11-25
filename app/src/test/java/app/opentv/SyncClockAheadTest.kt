/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.SyncDevice.Companion.MOVIE_MILLIS
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A TV whose clock is set ahead (ADR 0005): what it sends is moved back by how far ahead its clock
 * is, so a change made since on another TV beats it, however often it arrives and even when it
 * arrives passed on by a third TV.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which sync doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncClockAheadTest {

    /** Its clock is far ahead of the other two. */
    private val ahead = SyncDevice(rowIdOffset = 0)
    private val relay = SyncDevice(rowIdOffset = 100)
    private val here = SyncDevice(rowIdOffset = 200)

    @get:Rule(order = 0)
    val graphAhead = ahead.testGraph

    @get:Rule(order = 1)
    val graphRelay = relay.testGraph

    @get:Rule(order = 2)
    val graphHere = here.testGraph

    private fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private suspend fun seedAll() {
        ahead.now = AHEAD
        relay.now = 1_000
        here.now = 1_000
        listOf(ahead, relay, here).forEach { it.seed("Tom") }
    }

    /** The clock-ahead TV's change reaches the relay, then the viewer changes it [here] at 2 000. */
    private suspend fun changedHereAfter(changeAhead: suspend () -> Unit, changeHere: suspend () -> Unit) {
        changeAhead()
        relay.syncFrom(ahead)
        here.now = 2_000
        changeHere()
        here.syncFrom(relay)
    }

    @Test
    fun `a Favourite starred after a clock-ahead TV unstarred it stays starred`() = testing {
        seedAll()
        ahead.favourite("Tom", 1)

        changedHereAfter(
            {
                ahead.now = AHEAD + 1
                ahead.unfavourite("Tom", 1)
            },
            { here.favourite("Tom", 1) },
        )

        assertThat(here.favouriteNames("Tom")).contains("Movie 1")
    }

    @Test
    fun `progress made after a clock-ahead TV's resumes from where it was made`() = testing {
        seedAll()

        changedHereAfter(
            { ahead.play("Tom", ahead.movieKey, 3_000_000, MOVIE_MILLIS) },
            { here.play("Tom", here.movieKey, 1_000_000, MOVIE_MILLIS) },
        )

        assertThat(here.resumeFrom("Tom", here.movieKey)).isEqualTo(1_000_000)
    }

    @Test
    fun `a category unlocked after a clock-ahead TV locked it stays unlocked`() = testing {
        seedAll()

        changedHereAfter(
            { ahead.graph.settings.setCategoryLocked("adult", locked = true, now = AHEAD) },
            { here.graph.settings.setCategoryLocked("adult", locked = false, now = 2_000) },
        )

        assertThat(here.graph.settings.hiddenCategories.value).isEmpty()
    }

    @Test
    fun `a Profile setting changed after a clock-ahead TV changed it keeps the later choice`() = testing {
        seedAll()

        changedHereAfter(
            {
                ahead.asProfile("Tom")
                ahead.graph.profileSettings.setSubtitlesOn(true)
            },
            {
                here.asProfile("Tom")
                here.graph.profileSettings.setSubtitlesOn(false)
            },
        )

        here.asProfile("Tom")
        assertThat(here.graph.profileSettings.subtitlePreference.first().enabled).isFalse()
    }

    @Test
    fun `a Favourite starred after a clock-ahead TV unstarred it stays starred when that TV's bundle arrives again`() =
        testing {
            seedAll()
            ahead.favourite("Tom", 1)
            here.syncFrom(ahead)
            // Unstarred at 1 200 by the real clock.
            ahead.now = AHEAD + 200
            ahead.unfavourite("Tom", 1)
            ahead.now = AHEAD + 500
            here.now = 1_500
            here.syncFrom(ahead)

            here.now = 2_000
            here.favourite("Tom", 1)
            ahead.now = AHEAD + 2_000
            here.now = 3_000
            here.syncFrom(ahead)

            assertThat(here.favouriteNames("Tom")).contains("Movie 1")
        }

    @Test
    fun `a provider named here after a clock-ahead TV renamed the provider of that name keeps its name`() = testing {
        seedAll()
        here.renameProvider("BT")
        // Renamed at 1 000 by the real clock, which that TV reads as AHEAD.
        ahead.renameProvider("Movies")
        here.now = 2_000
        here.renameProvider("Provider")
        ahead.now = AHEAD + 2_000
        here.now = 3_000

        here.syncFrom(ahead)

        assertThat(here.providerName()).isEqualTo("Provider")
    }

    @Test
    fun `a change dated after the receiving TV's now is kept, and passed on, as made now`() = testing {
        here.now = 3_500
        here.seed("Tom")
        here.favourite("Tom", 1)
        here.now = 5_000

        // A bundle without a time of its own, so nothing says how far ahead its clock was.
        here.sync.apply(
            here.sync.decode(
                """{"version": 6, "favourites": [
                    {"profile": "Tom", "kind": "MOVIE", "source": "Provider", "id": "m1",
                     "changedAtMillis": 9000, "removed": true}
                ]}""",
            ),
        )

        assertThat(here.favouriteNames("Tom")).doesNotContain("Movie 1")
        val passedOn = here.graph.viewerLibrary.favourites.export().single { it.kind == "MOVIE" }
        assertThat(passedOn.changedAtMillis).isEqualTo(5_000)
    }

    @Test
    fun `a change passed on by a TV whose clock is behind keeps its time`() = testing {
        here.now = 3_500
        here.seed("Tom")
        here.favourite("Tom", 1)
        here.now = 5_000

        // Its bundle says 3 000, but the change it passes on was made at 4 000 on a TV with the right time.
        here.sync.apply(
            here.sync.decode(
                """{"version": 6, "generatedAtMillis": 3000, "favourites": [
                        {"profile": "Tom", "kind": "MOVIE", "source": "Provider", "id": "m1",
                         "changedAtMillis": 4000, "removed": true}
                    ]}""",
            ),
        )

        assertThat(here.favouriteNames("Tom")).doesNotContain("Movie 1")
    }

    private companion object {
        const val AHEAD = 10_000_000L
    }
}
