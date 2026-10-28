/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of GitHub's `releases/latest` response we care about.
 *
 * Every field defaults, so a shape change on GitHub's side (a renamed or dropped key) leaves
 * us with an empty value rather than a parse exception that would break launch. `ignoreUnknownKeys`
 * on the decoder handles the ninety other fields we do not read.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAsset> = emptyList(),
) {
    /**
     * The attached `.apk` to install. Releases carry the same APK twice, `settee-<tag>.apk` and a
     * fixed-name `settee.apk` for Downloader short codes; prefer the versioned one so the file the
     * updater downloads says which version it is. Falls back to any `.apk` for older releases.
     */
    fun apkAsset(): GitHubAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { tagName.isNotEmpty() && it.name.contains(tagName) } ?: apks.firstOrNull()
    }
}

@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)
