/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ErrorWording
import app.opentv.core.LocaleUtils
import app.opentv.core.ofOpenSubtitles
import app.opentv.data.remote.OpenSubtitlesFailure
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
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
    fun `Settee's key being turned down says the account is fine and Settee needs an update`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.KeyRejected())

        assertThat(error.sentence).isEqualTo(
            "OpenSubtitles isn't accepting Settee right now. Your account is fine: this needs an update to Settee.",
        )
        assertThat(error.detail).isEqualTo("KeyRejected · HTTP 403")
    }

    @Test
    fun `Settee's key turned down with a 401 says so, with that status on the detail line`() {
        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.KeyRejected(status = 401))

        assertThat(error.sentence).isEqualTo(
            "OpenSubtitles isn't accepting Settee right now. Your account is fine: this needs an update to Settee.",
        )
        assertThat(error.detail).isEqualTo("KeyRejected · HTTP 401")
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
    fun `the daily limit when signed in says when the counter resets, in the viewer's zone and the app's language`() {
        val locale = java.util.Locale.getDefault()
        try {
            // The app in British English, as MainActivity's attachBaseContext puts its chosen language on
            // every context. Not through AppSettings.get: that's one per process, and may still hold an
            // earlier test's application, whose settings this test's context never reads.
            val british = LocaleUtils.wrap(ApplicationProvider.getApplicationContext(), "en-GB")
            val midnightUtc = java.time.Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()
            // The graph's zone, not the JVM's: Tokyo is nine hours on from UTC.
            val error = ErrorWording(british, onTelevision = true) { ZoneId.of("Asia/Tokyo") }.ofOpenSubtitles(
                OpenSubtitlesFailure.DownloadLimit(signedIn = true, resetsAt = midnightUtc),
            )

            assertThat(error.sentence).isEqualTo("That's today's OpenSubtitles downloads used. More from 09:00.")
        } finally {
            java.util.Locale.setDefault(locale)
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

    @Test
    fun `a subtitle this TV had no room to keep is about this TV's storage, not reaching OpenSubtitles`() {
        // How Android reports a full disk: the write's errno, ENOSPC, behind the IOException.
        val full = java.io.IOException(android.system.ErrnoException("write", android.system.OsConstants.ENOSPC))

        val error = wording.ofOpenSubtitles(OpenSubtitlesFailure.NotSaved(full))

        assertThat(error.sentence).isEqualTo(
            "There's no room on this TV to keep that subtitle. Free up some space, then try again.",
        )
        assertThat(error.detail).isEqualTo("NotSaved · IO")
    }

    @Test
    fun `a subtitle this TV couldn't keep for any other reason doesn't send the viewer to free up space`() {
        val error = wording.ofOpenSubtitles(
            OpenSubtitlesFailure.NotSaved(java.io.IOException("Subtitle bigger than the cap")),
        )

        assertThat(error.sentence).isEqualTo("This TV couldn't keep that subtitle. Try again, or pick a different one.")
        assertThat(error.detail).isEqualTo("NotSaved · IO")
    }

    @Test
    fun `a sign-in this TV couldn't save says so, not that OpenSubtitles can't be reached`() {
        val error = wording.ofOpenSubtitles(
            OpenSubtitlesFailure.SignInNotSaved(java.security.KeyStoreException("no key")),
        )

        assertThat(error.sentence).isEqualTo(
            "OpenSubtitles accepted the sign-in, but this TV couldn't save it. Restart this TV, then sign in again.",
        )
        assertThat(error.detail).isEqualTo("SignInNotSaved · KeyStore")
    }
}
