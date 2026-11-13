/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.remote.OpenSubtitlesClient
import app.opentv.data.remote.OpenSubtitlesFailure
import app.opentv.data.repo.OpenSubtitlesAccount
import app.opentv.data.repo.Sealer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reversible, and visibly not the plain text, so a test can see the password isn't kept as typed. */
class FakeSealer : Sealer {
    override fun seal(plain: String) = "sealed:" + plain.reversed()

    override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed().takeIf {
        sealed.startsWith("sealed:")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OpenSubtitlesAccountTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = AppSettings.separate(context, "opensubtitles_account_test")
    private val site = FakeOpenSubtitles().apply {
        replies["POST /login"] = """{"user": {"allowed_downloads": 20, "remaining_downloads": 20}, "token": "t1"}"""
        replies["POST /download"] = """{"link": "https://dl.opensubtitles.example/f.srt", "remaining": 19}"""
        replies["GET /f.srt"] = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
    }
    private var now = DAY_START
    private val account = OpenSubtitlesAccount(
        settings,
        OpenSubtitlesClient(site.http, apiKey = "k", appVersion = "0.5.0"),
        FakeSealer(),
        clock = { now },
    )

    private fun logins() = site.requests.count { it.url.encodedPath.endsWith("/login") }

    private fun lastDownloadAuth() =
        site.requests.last { it.url.encodedPath.endsWith("/download") }.header("Authorization")

    @Test
    fun `signing in keeps the username and token, and the password only sealed`() = runTest {
        assertThat(account.signIn("viewer", "secret").isSuccess).isTrue()

        assertThat(account.signedInAs.value).isEqualTo("viewer")
        val stored = context.getSharedPreferences("opensubtitles_account_test", Context.MODE_PRIVATE).all.values
        assertThat(stored.map { it.toString() }).doesNotContain("secret")
        account.download(fileId = 1)
        assertThat(lastDownloadAuth()).isEqualTo("Bearer t1")
    }

    @Test
    fun `a Keystore that won't seal the password is a failed sign-in that keeps nothing, not a crash`() = runTest {
        val broken = object : Sealer {
            override fun seal(plain: String): String = throw java.security.KeyStoreException("no key")

            override fun open(sealed: String): String? = null
        }
        val unsealed = OpenSubtitlesAccount(settings, OpenSubtitlesClient(site.http, "k", "0.5.0"), broken, { now })

        assertThat(unsealed.signIn("viewer", "secret").isFailure).isTrue()
        assertThat(unsealed.signedInAs.value).isNull()
    }

    @Test
    fun `a VIP account's own address is kept with its token, and used again after a restart`() = runTest {
        site.replies["POST /login"] = """{"user": {}, "token": "t1", "base_url": "vip-api.opensubtitles.example"}"""
        account.signIn("viewer", "secret")

        // A new account and client over the same settings, as after Settee restarts.
        val restarted =
            OpenSubtitlesAccount(settings, OpenSubtitlesClient(site.http, "k", "0.5.0"), FakeSealer(), { now })
        restarted.download(fileId = 1)

        val asked = site.requests.last { it.url.encodedPath.endsWith("/download") }
        assertThat(asked.url.host).isEqualTo("vip-api.opensubtitles.example")
        assertThat(asked.header("Authorization")).isEqualTo("Bearer t1")
    }

    @Test
    fun `a wrong username or password keeps nothing`() = runTest {
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""

        assertThat(account.signIn("viewer", "wrong").exceptionOrNull())
            .isInstanceOf(OpenSubtitlesFailure.SignInRejected::class.java)
        assertThat(account.signedInAs.value).isNull()
    }

    @Test
    fun `a day later the token renews with the saved password without asking`() = runTest {
        account.signIn("viewer", "secret")
        site.replies["POST /login"] = """{"user": {}, "token": "t2"}"""
        now += DAY + 1

        account.download(fileId = 1)

        assertThat(logins()).isEqualTo(2)
        assertThat(site.bodies.last { it.contains("username") }).contains("\"password\":\"secret\"")
        assertThat(lastDownloadAuth()).isEqualTo("Bearer t2")
    }

    @Test
    fun `a token OpenSubtitles turns down early is renewed and the download tried again`() = runTest {
        account.signIn("viewer", "secret")
        site.replies["POST /download"] = """!401 {"message": "invalid token"}"""
        site.replies["POST /login"] = """{"user": {}, "token": "t2"}"""

        account.download(fileId = 1)

        assertThat(logins()).isEqualTo(2)
        assertThat(site.requests.filter { it.url.encodedPath.endsWith("/download") }.map { it.header("Authorization") })
            .containsExactly("Bearer t1", "Bearer t2").inOrder()
    }

    @Test
    fun `a token that can't be renewed is reported, not retried for ever`() = runTest {
        account.signIn("viewer", "secret")
        site.replies["POST /login"] = """!401 {"message": "Invalid username/password"}"""
        now += DAY + 1

        assertThat(account.download(fileId = 1).exceptionOrNull())
            .isInstanceOf(OpenSubtitlesFailure.TokenRejected::class.java)
        assertThat(logins()).isEqualTo(2)
    }

    @Test
    fun `signing out forgets the account, and downloads go back to the anonymous allowance`() = runTest {
        account.signIn("viewer", "secret")

        account.signOut()

        assertThat(account.signedInAs.value).isNull()
        assertThat(context.getSharedPreferences("opensubtitles_account_test", Context.MODE_PRIVATE).all).isEmpty()
        account.download(fileId = 1)
        assertThat(lastDownloadAuth()).isNull()
    }

    @Test
    fun `automatic download is on until the viewer turns it off`() {
        assertThat(account.autoDownload.value).isTrue()

        account.setAutoDownload(false)

        assertThat(account.autoDownload.value).isFalse()
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val DAY_START = 1_800_000_000_000L
    }
}
