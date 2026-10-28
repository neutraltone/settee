/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.UpdateChecker
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

    private fun githubReplying(json: String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val releaseWithBothApks = """
        {
          "tag_name": "v0.2.0",
          "name": "Settee 0.2.0",
          "html_url": "https://github.com/neutraltone/settee/releases/tag/v0.2.0",
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

    @Test
    fun `checks Settee's own releases`() {
        runBlocking { UpdateChecker(githubReplying(releaseWithBothApks), "0.1.0").check() }

        assertThat(requestedUrls)
            .containsExactly("https://api.github.com/repos/neutraltone/settee/releases/latest")
    }

    @Test
    fun `offers the versioned apk when the release also carries the fixed-name one`() {
        val update = runBlocking { UpdateChecker(githubReplying(releaseWithBothApks), "0.1.0").check() }

        assertThat(update?.apkUrl)
            .isEqualTo("https://github.com/neutraltone/settee/releases/download/v0.2.0/settee-v0.2.0.apk")
    }
}
