/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.SyncDevice.Companion.MOVIE_MILLIS
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A provider is known on every device by its name (ADR 0004): a stray space or capital, or a rename,
 * mustn't cut its Favourites and progress off from the other devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncProviderNamesTest {

    private val deviceA = SyncDevice(rowIdOffset = 0)
    private val deviceB = SyncDevice(rowIdOffset = 100)
    private val deviceC = SyncDevice(rowIdOffset = 200)

    @get:Rule(order = 0)
    val graphA = deviceA.testGraph

    @get:Rule(order = 1)
    val graphB = deviceB.testGraph

    @get:Rule(order = 2)
    val graphC = deviceC.testGraph

    private fun testing(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    @Test
    fun `a provider called Sky with a trailing space on one device is sky on another`() = testing {
        deviceA.sourceName = "Sky "
        deviceB.sourceName = "sky"
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.favourite("Alex", 1)
        deviceA.play("Alex", deviceA.movieKey, 1_200_000, MOVIE_MILLIS)
        deviceA.hide(2)

        deviceB.syncFrom(deviceA)

        assertThat(deviceB.favouriteNames("Alex")).containsExactly("Channel 1", "Movie 1", "Series 1")
        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(1_200_000)
        assertThat(deviceB.isHidden(2)).isTrue()
    }

    @Test
    fun `renaming a provider keeps its Favourites here and carries the new name to the other device`() = testing {
        deviceA.now = 1_000
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.favourite("Alex", 1)
        deviceA.play("Alex", deviceA.movieKey, 1_200_000, MOVIE_MILLIS)
        deviceB.syncFrom(deviceA)

        deviceA.now = 2_000
        deviceA.renameProvider("Sky")
        deviceA.now = 3_000
        deviceA.favourite("Alex", 2)
        deviceA.play("Alex", deviceA.movieKey, 2_400_000, MOVIE_MILLIS)
        deviceB.syncFrom(deviceA)

        assertThat(deviceA.favouriteNames("Alex"))
            .containsExactly("Channel 1", "Channel 2", "Movie 1", "Movie 2", "Series 1", "Series 2")
        assertThat(deviceB.providerName()).isEqualTo("Sky")
        assertThat(deviceB.favouriteNames("Alex"))
            .containsExactly("Channel 1", "Channel 2", "Movie 1", "Movie 2", "Series 1", "Series 2")
        assertThat(deviceB.resumeFrom("Alex", deviceB.movieKey)).isEqualTo(2_400_000)
    }

    @Test
    fun `a device that hasn't heard of a rename still reaches the renamed provider by its old name`() = testing {
        deviceA.seed("Alex")
        deviceC.seed("Alex")
        deviceA.now = 2_000
        deviceA.renameProvider("Sky")
        deviceC.now = 3_000
        deviceC.favourite("Alex", 1)
        deviceC.play("Alex", deviceC.movieKey, 1_200_000, MOVIE_MILLIS)

        deviceA.syncFrom(deviceC)

        assertThat(deviceA.favouriteNames("Alex")).containsExactly("Channel 1", "Movie 1", "Series 1")
        assertThat(deviceA.resumeFrom("Alex", deviceA.movieKey)).isEqualTo(1_200_000)
    }

    @Test
    fun `a rename passes on through a device in between`() = testing {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceC.seed("Alex")
        deviceA.renameProvider("Sky")

        deviceB.syncFrom(deviceA)
        deviceC.syncFrom(deviceB)

        assertThat(deviceC.providerName()).isEqualTo("Sky")
    }

    @Test
    fun `a name this device gave its provider after another device's rename stays`() = testing {
        deviceA.seed("Alex")
        deviceB.seed("Alex")
        deviceA.now = 2_000
        deviceA.renameProvider("Sky")
        deviceA.now = 3_000
        deviceA.renameProvider("Sky UK")
        deviceB.now = 4_000
        deviceB.renameProvider("Sky")

        deviceB.syncFrom(deviceA)

        assertThat(deviceB.providerName()).isEqualTo("Sky")
    }
}
