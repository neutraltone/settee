/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import app.opentv.data.remote.GitHubRelease
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks whether a newer Settee release has been published, so a sideloaded install can
 * update itself.
 *
 * Sideloaded apps get no automatic updates — nothing on the device knows to look. Without
 * this, whoever installs v0.1 stays on v0.1 until they happen to hear there is a newer one
 * and go and fetch it by hand. The app store equivalents (Play, Amazon) push updates for
 * you; the Downloader-app install route we ship through does not, so the app has to do it.
 *
 * ## Design
 * - **Quiet on failure, but honest.** No network, rate-limited, a 404, or a newer release whose
 *   APK hasn't been attached (its release job failed) all come back as [Outcome.CouldNotCheck].
 *   The launch-time gate ignores that, so it never nags; the manual check says it couldn't
 *   check rather than claim the viewer is up to date, which hid a release missing its APK.
 * - **Compares the tag, not a build number.** GitHub gives us a tag like `v0.2.0`. We parse it
 *   as a dotted version and compare it to our own [currentVersionName]. A release whose tag is
 *   not strictly newer is ignored, so re-tagging or a same-version hotfix will not prompt.
 * - **Skips drafts and pre-releases**, which are not meant for the general audience.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionName: String,
    private val repoSlug: String = REPO_SLUG,
) {
    data class Update(
        val versionName: String,
        val title: String,
        /** Null when the release has nothing a viewer would notice. */
        val notes: VersionNotes?,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseUrl: String,
    )

    sealed interface Outcome {
        data object UpToDate : Outcome
        data class Available(val update: Update) : Outcome
        data object CouldNotCheck : Outcome
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenTV")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Outcome.CouldNotCheck
                val body = response.body.string()
                if (body.isBlank()) return@withContext Outcome.CouldNotCheck

                val release = json.decodeFromString<GitHubRelease>(body)
                if (release.draft || release.prerelease) return@withContext Outcome.UpToDate
                if (!isNewer(release.tagName, currentVersionName)) return@withContext Outcome.UpToDate

                val asset = release.apkAsset() ?: return@withContext Outcome.CouldNotCheck
                Outcome.Available(
                    Update(
                        versionName = release.tagName.trimStart('v', 'V'),
                        title = release.name.ifBlank { release.tagName },
                        notes = ReleaseNotes.fromReleaseBody(release.body, release.tagName.trimStart('v', 'V')),
                        apkUrl = asset.browserDownloadUrl,
                        apkSizeBytes = asset.size,
                        releaseUrl = release.htmlUrl,
                    ),
                )
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            Outcome.CouldNotCheck
        }
    }

    companion object {
        /** The single place the project's GitHub location is written down. */
        const val REPO_SLUG = "neutraltone/settee"

        /**
         * True when [tag] names a strictly higher version than [current].
         *
         * Both are reduced to lists of integers (`v0.2.0` → 0,2,0) and compared left to right.
         * A missing or non-numeric component counts as 0, so `0.2` and `0.2.0` are equal and a
         * malformed tag simply never wins.
         */
        fun isNewer(tag: String, current: String): Boolean {
            val a = versionParts(tag)
            val b = versionParts(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai > bi
            }
            return false
        }

        private fun versionParts(v: String): List<Int> = v.trim().trimStart('v', 'V')
            .split('.', '-', '+')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
