/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.NotesHeading
import app.opentv.update.NotesSection
import app.opentv.update.UpdateChecker
import app.opentv.update.VersionNotes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * The updater as the app constructs it, with GitHub replaced by a canned reply at the HTTP
 * boundary, so the test sees which releases it asks for and which file it would install.
 */
class UpdateCheckerTest {

    private val requestedUrls = mutableListOf<String>()

    private fun githubReplying(json: String, code: Int = 200) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val releaseWithBothApks = """
        {
          "tag_name": "v0.2.0",
          "name": "Settee 0.2.0",
          "html_url": "https://github.com/neutraltone/settee/releases/tag/v0.2.0",
          "body": "### Features\n\n* **#9:** episodes show how far through them you are ([516bf6a](https://github.com/neutraltone/settee/commit/516bf6a))\n\n---\nsigned: yes\n\nBuilt by GitHub Actions from the tagged commit.\n\n---",
          "assets": [
            {
              "name": "settee.apk",
              "browser_download_url": "https://github.com/neutraltone/settee/releases/download/v0.2.0/settee.apk",
              "size": 1000
            },
            {
              "name": "settee-v0.2.0.apk",
              "browser_download_url": "https://github.com/neutraltone/settee/releases/download/v0.2.0/settee-v0.2.0.apk",
              "size": 1000
            }
          ]
        }
    """.trimIndent()

    /** What the app concludes when GitHub replies [json] (with [code]) to a build of [installed]. */
    private fun check(json: String, installed: String = "0.1.0", code: Int = 200) =
        runBlocking { UpdateChecker(githubReplying(json, code), installed).check() }

    private fun availableFrom(json: String) = (check(json) as? UpdateChecker.Outcome.Available)?.update

    private val releaseWithoutApks = """
        {
          "tag_name": "v0.2.0",
          "name": "Settee 0.2.0",
          "html_url": "https://github.com/neutraltone/settee/releases/tag/v0.2.0",
          "body": "",
          "assets": []
        }
    """.trimIndent()

    @Test
    fun `a newer release without its apk can't be checked, rather than looking up to date`() {
        assertThat(check(releaseWithoutApks)).isEqualTo(UpdateChecker.Outcome.CouldNotCheck)
    }

    @Test
    fun `the installed version being the latest release is up to date`() {
        assertThat(check(releaseWithBothApks, installed = "0.2.0")).isEqualTo(UpdateChecker.Outcome.UpToDate)
    }

    @Test
    fun `an error from GitHub, such as a rate limit, can't be checked`() {
        assertThat(check(releaseWithBothApks, code = 403)).isEqualTo(UpdateChecker.Outcome.CouldNotCheck)
    }

    @Test
    fun `an empty reply can't be checked`() {
        assertThat(check("")).isEqualTo(UpdateChecker.Outcome.CouldNotCheck)
    }

    @Test
    fun `a reply that isn't a release can't be checked`() {
        assertThat(check("<html>Service unavailable</html>")).isEqualTo(UpdateChecker.Outcome.CouldNotCheck)
    }

    @Test
    fun `checks Settee's own releases`() {
        check(releaseWithBothApks)

        assertThat(requestedUrls)
            .containsExactly("https://api.github.com/repos/neutraltone/settee/releases/latest")
    }

    @Test
    fun `offers the versioned apk when the release also carries the fixed-name one`() {
        val update = availableFrom(releaseWithBothApks)

        assertThat(update?.apkUrl)
            .isEqualTo("https://github.com/neutraltone/settee/releases/download/v0.2.0/settee-v0.2.0.apk")
    }

    @Test
    fun `offers the release's viewer-facing notes`() {
        val update = availableFrom(releaseWithBothApks)

        assertThat(update?.notes).isEqualTo(
            VersionNotes(
                "0.2.0",
                listOf(NotesSection(NotesHeading.NewFeatures, listOf("episodes show how far through them you are"))),
            ),
        )
    }
}
