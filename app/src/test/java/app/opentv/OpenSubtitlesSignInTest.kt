/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.core.ErrorWording
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.ui.settings.OpenSubtitlesSignIn
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Settings › OpenSubtitles knows about a signed-in account, against [FakeOpenSubtitles]. Placeholder only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenSubtitlesSignInTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val site = FakeOpenSubtitles().apply {
        replies["POST /login"] = """{"user": {"allowed_downloads": 20, "remaining_downloads": 18}, "token": "t"}"""
        replies["GET /infos/user"] = """{"data": {"allowed_downloads": 20, "remaining_downloads": 18}}"""
    }
    private val account = OpenSubtitlesAccount(
        AppSettings.separate(context, "opensubtitles_sign_in_test"),
        OpenSubtitlesClient(site.http, "k", "0.5.0"),
        FakeSealer(),
    )
    private val signIn = OpenSubtitlesSignIn(account, ErrorWording(context, onTelevision = true))

    @Before
    fun signedIn() = runTest {
        assertThat(account.signIn("u", "p").isSuccess).isTrue()
    }

    private fun signedOutByOpenSubtitles() {
        site.replies["GET /infos/user"] = """!401 {"message": "invalid token"}"""
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""
    }

    @Test
    fun `when OpenSubtitles turns down the saved password, it says the TV was signed out and offers Sign in again`() =
        runTest {
            signedOutByOpenSubtitles()

            signIn.checkAllowance()

            assertThat(signIn.accountProblem?.sentence)
                .isEqualTo("OpenSubtitles signed this TV out. Sign in again in Settings.")
            assertThat(signIn.offersSignInAgain).isTrue()
        }

    @Test
    fun `when OpenSubtitles can't be reached, it says so and doesn't offer to sign in again`() = runTest {
        site.replies["GET /infos/user"] = "!"

        signIn.checkAllowance()

        assertThat(signIn.accountProblem?.sentence)
            .isEqualTo("Can't reach OpenSubtitles. Check this TV is online, then try again.")
        assertThat(signIn.accountProblem?.detail).isNotEmpty()
        assertThat(signIn.offersSignInAgain).isFalse()
    }

    @Test
    fun `signing in again with the same username shows the allowance it came back with`() = runTest {
        signedOutByOpenSubtitles()
        signIn.checkAllowance()
        site.replies["POST /login"] =
            """{"user": {"allowed_downloads": 20, "remaining_downloads": 7}, "token": "t2"}"""

        assertThat(signIn.signIn("u", "new password")).isNull()

        assertThat(signIn.allowance?.getOrNull()?.remaining).isEqualTo(7)
        assertThat(signIn.accountProblem).isNull()
        assertThat(signIn.offersSignInAgain).isFalse()
    }
}
