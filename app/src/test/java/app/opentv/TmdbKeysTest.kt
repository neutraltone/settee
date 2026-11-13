/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.remote.TmdbCheck
import app.opentv.data.remote.TmdbClient
import app.opentv.data.repo.TmdbKeys
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saving and removing the TMDB Key, which Settings › TMDB, Onboarding and the phone page all go
 * through: only a key TMDB has accepted is ever saved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TmdbKeysTest {

    private val settings = AppSettings.get(ApplicationProvider.getApplicationContext())
    private val panel = FakePanel()
    private val tmdb = TmdbClient(panel.http, settings)
    private val keys = TmdbKeys(settings) { tmdb.check(it) }

    @After
    fun forgetKey() = settings.setTmdbApiKey("")

    @Test
    fun `a key TMDB accepts is saved`() = runTest {
        panel.replies["/3/authentication"] = """{"success": true}"""

        assertThat(keys.save(KEY)).isEqualTo(TmdbCheck.Accepted)

        assertThat(settings.tmdbApiKey.value).isEqualTo(KEY)
        assertThat(tmdb.isConfigured()).isTrue()
    }

    @Test
    fun `a key is saved without the spaces a paste brings along`() = runTest {
        panel.replies["/3/authentication"] = """{"success": true}"""

        keys.save("  $KEY\n")

        assertThat(settings.tmdbApiKey.value).isEqualTo(KEY)
    }

    @Test
    fun `a key TMDB turns down is not saved, and the one before stays`() = runTest {
        panel.replies["/3/authentication"] = """{"success": true}"""
        keys.save(KEY)
        panel.replies["/3/authentication"] = "!401"

        assertThat(keys.save(OTHER_KEY)).isEqualTo(TmdbCheck.Rejected)

        assertThat(settings.tmdbApiKey.value).isEqualTo(KEY)
    }

    @Test
    fun `a key is not saved when TMDB can't be reached`() = runTest {
        panel.replies["/3/authentication"] = "!"

        assertThat(keys.save(KEY)).isInstanceOf(TmdbCheck.Unreachable::class.java)

        assertThat(settings.tmdbApiKey.value).isEmpty()
    }

    @Test
    fun `a blank key is turned down without asking TMDB`() = runTest {
        assertThat(keys.save("   ")).isEqualTo(TmdbCheck.Rejected)

        assertThat(panel.requests).isEmpty()
    }

    @Test
    fun `the saved key shows only its last four characters`() = runTest {
        assertThat(keys.savedEnding.first()).isNull()
        panel.replies["/3/authentication"] = """{"success": true}"""

        keys.save(KEY)

        assertThat(keys.savedEnding.first()).isEqualTo("wxyz")
    }

    @Test
    fun `removing the key stops every call to TMDB`() = runTest {
        panel.replies["/3/authentication"] = """{"success": true}"""
        keys.save(KEY)
        panel.requests.clear()

        keys.remove()
        tmdb.movieMetaOrFailure("A Film", year = null, tmdbId = null)
        tmdb.seriesMetaOrFailure("A Show", year = null, tmdbId = null)
        tmdb.imdbId("A Film", year = null, isMovie = true, tmdbId = null)

        assertThat(tmdb.isConfigured()).isFalse()
        assertThat(keys.savedEnding.first()).isNull()
        assertThat(panel.requests).isEmpty()
    }

    private companion object {
        /** Placeholders, low-entropy so gitleaks sees no secret. */
        val KEY = "a".repeat(28) + "wxyz"
        val OTHER_KEY = "b".repeat(32)
    }
}
