/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.remote.OpenSubtitlesFailure
import com.google.common.truth.Truth.assertThat
import java.util.TimeZone
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each OpenSubtitles failure says what happened and what to do, as docs/ERRORS.md lists. */
@RunWith(RobolectricTestRunner::class)
// The device in American English; the app set to British English below, where the time is said.
@Config(sdk = [34], qualifiers = "en-rUS", application = android.app.Application::class)
class OpenSubtitlesWordingTest {

    private val wording = ErrorWording(ApplicationProvider.getApplicationContext(), onTelevision = true)

    @Test
    fun `no results says so`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.NoResults())

        assertThat(error.sentence).isEqualTo("OpenSubtitles has no subtitles for this yet.")
        assertThat(error.detail).isEqualTo("NoResults")
    }

    @Test
    fun `not reaching OpenSubtitles asks the viewer to check the TV is online`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.Unreachable(java.net.UnknownHostException()))

        assertThat(error.sentence).isEqualTo("Can't reach OpenSubtitles. Check this TV is online, then try again.")
    }

    @Test
    fun `trouble at OpenSubtitles' end says it's theirs, with the status`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.Unreachable(null, status = 503))

        assertThat(
            error.sentence,
        ).isEqualTo("OpenSubtitles is having problems. That's at their end, so try again later.")
        assertThat(error.detail).isEqualTo("HTTP 503")
    }

    @Test
    fun `the daily limit when not signed in suggests signing in`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.DownloadLimit(signedIn = false, resetsAt = null))

        assertThat(error.sentence).isEqualTo(
            "That's today's 5 free subtitles used. Sign in to OpenSubtitles in Settings for more.",
        )
        assertThat(error.detail).isEqualTo("DownloadLimit · HTTP 406")
    }

    @Test
    fun `the daily limit when signed in says when the counter resets, in local time and the app's language`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        val locale = java.util.Locale.getDefault()
        AppSettings.get(ApplicationProvider.getApplicationContext()).setLanguageTag("en-GB")
        try {
            val midnightUtc = java.time.Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
            val error = wording.ofOpenSubtitles(
                OpenSubtitlesFailure.DownloadLimit(signedIn = true, resetsAt = midnightUtc),
            )

            assertThat(error.sentence).isEqualTo("That's today's OpenSubtitles downloads used. More from 00:00.")
        } finally {
            TimeZone.setDefault(zone)
            java.util.Locale.setDefault(locale)
            AppSettings.get(ApplicationProvider.getApplicationContext()).setLanguageTag("")
        }
    }

    @Test
    fun `a wrong username or password says so`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.SignInRejected())

        assertThat(
            error.sentence,
        ).isEqualTo("OpenSubtitles didn't accept that username or password. Check them and try again.")
    }

    @Test
    fun `a sign-in that can't be renewed asks the viewer to sign in again`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.TokenRejected())

        assertThat(error.sentence).isEqualTo("OpenSubtitles signed this TV out. Sign in again in Settings.")
    }
}
