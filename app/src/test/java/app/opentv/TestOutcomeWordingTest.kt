/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.LocaleUtils
import app.opentv.data.repo.TestOutcome
import app.opentv.data.repo.TestOutcomeWording
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Test connection says when the details work, in the app's language rather than in code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rGB", application = android.app.Application::class)
class TestOutcomeWordingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val locale = Locale.getDefault()

    // Midday UTC, so it's the 31st in every time zone a test machine might be in.
    private val newYearsEve2026 = 1_798_718_400_000L

    @After
    fun restore() = Locale.setDefault(locale)

    /** Wording in [language], as MainActivity's attachBaseContext puts the app's chosen language on. */
    private fun wording(language: String) = TestOutcomeWording(LocaleUtils.wrap(context, language))

    @Test
    fun `an Xtream login's account comes from the app's strings, with its expiry in the app's language`() {
        val said = wording("de").of(TestOutcome.Xtream(username = "u", maxConnections = 2, expiresAtMillis = newYearsEve2026))

        assertThat(said).isEqualTo(
            "Your login works. Signed in as u. Watch on 2 screens at a time. Your subscription runs until 31.12.2026.",
        )
    }

    @Test
    fun `an account that says less leaves out what it didn't say, and one screen is singular`() {
        val said = wording("en-GB").of(TestOutcome.Xtream(username = null, maxConnections = 1, expiresAtMillis = null))

        assertThat(said).isEqualTo("Your login works. Watch on 1 screen at a time.")
    }

    @Test
    fun `a portal or playlist that passes never says its channels are loading, since Test loads none`() {
        val said = listOf(TestOutcome.StalkerAccepted, TestOutcome.PlaylistFound).map(wording("en-GB")::of)

        assertThat(said).containsExactly(
            "Your portal accepted the MAC address.",
            "Found your playlist. Save to load its channels.",
        ).inOrder()
    }
}
