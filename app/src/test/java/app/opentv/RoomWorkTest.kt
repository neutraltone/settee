/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Canaries for #615: a UI test's waits cover Room's background work. Each read of providers is made
 * slow on purpose, so a wait that only watches the main looper and Compose returns before the read
 * is back, every run rather than now and then on a busy CI runner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
class RoomWorkTest {

    @get:Rule
    val compose = createComposeRule()

    // Never counted down: awaiting it is a read that takes SLOW_READ_MILLIS on Room's own thread,
    // standing in for a slow disk. The default executor is kept, since that's what's under test.
    private val slowDisk = CountDownLatch(1)

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        OpenTvDatabase::class.java,
    )
        .setQueryCallback({ sql, _ ->
            if (sql.startsWith("SELECT") &&
                sql.contains("sources")
            ) {
                slowDisk.await(SLOW_READ_MILLIS, TimeUnit.MILLISECONDS)
            }
        }, Runnable::run)
        .build()

    private val id =
        runBlocking {
            db.sources().insert(Source(name = "Old films", kind = SourceKind.M3U, url = "http://example.com"))
        }

    @After
    fun close() = db.close()

    @Test
    fun `an assertion straight after a Room-backed screen appears sees the rows`() {
        // Built outside composition, as a view model would, so recomposing doesn't make a new flow.
        val namesFlow = db.sources().observeAll().map { all -> all.joinToString { it.name } }
        compose.setTvContent {
            val names by namesFlow.collectAsState("")
            BasicText(names)
        }

        compose.onNodeWithText("Old films").assertExists()
    }

    @Test
    fun `waitForIdle does not return while a Room read is in flight`() {
        var read: Source? = null
        compose.setTvContent {
            LaunchedEffect(Unit) { read = db.sources().byId(id) }
        }

        compose.waitForIdle()

        assertThat(read?.name).isEqualTo("Old films")
    }

    private companion object {
        const val SLOW_READ_MILLIS = 500L
    }
}
