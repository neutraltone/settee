/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.model.SubtitleKind
import app.opentv.data.remote.SubtitleResult
import app.opentv.data.repo.SubtitlePreference
import app.opentv.data.repo.baseLanguage

/**
 * When a film or episode fetches its own subtitle from OpenSubtitles, on a box whose language is
 * [device]. Only a signed-in box does it, because OpenSubtitles allows automatic fetching only on the
 * viewer's own allowance, and only when nothing on hand would do: the stream has no track in the
 * language and the box has no Downloaded Subtitle for the title.
 */
class AutoSubtitles(private val device: String) {
    /** The language to fetch, or null when nothing should be fetched. */
    fun languageToFetch(
        signedIn: Boolean,
        autoOn: Boolean,
        preference: SubtitlePreference?,
        streamLanguages: List<String?>,
        hasDownloaded: Boolean,
    ): String? {
        val allowed = signedIn && autoOn
        val wanted = baseLanguage(preference?.language ?: device)
        val missing = streamLanguages.none { baseLanguage(it) == wanted }
        val fetch = allowed && preference?.enabled == true && !hasDownloaded && missing
        return wanted.takeIf { fetch }
    }

    companion object {
        /** The most downloaded of [results] in [language] and of [kind], if there is one. */
        fun pick(results: List<SubtitleResult>, language: String, kind: SubtitleKind): SubtitleResult? {
            val wanted = baseLanguage(language)
            return results.filter {
                baseLanguage(it.language) == wanted && it.kind == kind
            }.maxByOrNull { it.downloads }
        }
    }
}
