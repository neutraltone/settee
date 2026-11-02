/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import app.opentv.data.repo.SourceRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sync matches a title by its source's name, so no two sources on a device may share one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SourceNamesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext<Application>())
    private val sources = SourceRepository(test.db.sources(), XtreamApi(test.panel.http), StalkerApi(test.panel.http))

    private fun source(name: String, id: Long = 0) =
        Source(id = id, name = name, kind = SourceKind.M3U, url = "http://example.com/list.m3u")

    private suspend fun names() = sources.observeAll().first().map { it.name }

    @After
    fun close() = test.close()

    @Test
    fun `a second provider with a name already taken gets its own`() = runTest {
        sources.save(source("Provider"))
        sources.save(source("Provider"))
        sources.save(source("Provider"))

        assertThat(names()).containsExactly("Provider", "Provider 2", "Provider 3")
    }

    @Test
    fun `saving a provider again keeps its name`() = runTest {
        val id = sources.save(source("Provider"))

        sources.save(source("Provider", id))

        assertThat(names()).containsExactly("Provider")
    }

    @Test
    fun `renaming a provider to a name already taken gets its own`() = runTest {
        sources.save(source("Provider"))
        val id = sources.save(source("Other"))

        sources.save(source("Provider", id))

        assertThat(names()).containsExactly("Provider", "Provider 2")
    }
}
