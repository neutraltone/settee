/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import app.opentv.data.remote.SubtitleResult
import app.opentv.data.repo.SubtitlePreference
import app.opentv.data.repo.baseLanguage

/** One subtitle track a stream offers, as far as choosing between them goes. */
data class SubtitleTrack(val language: String?, val supported: Boolean)

/**
 * Which of [tracks] to show when subtitles are on: the Profile's [preferred] language, then the
 * [device] language, then the first track that can be shown. A missing language never means no
 * subtitles: the viewer asked for them. Returns the index into [tracks], or null when none can be shown.
 */
fun chooseSubtitleTrack(tracks: List<SubtitleTrack>, preferred: String?, device: String): Int? =
    listOfNotNull(preferred, device).firstNotNullOfOrNull { language ->
        val wanted = baseLanguage(language)
        tracks.indexOfFirst { it.supported && baseLanguage(it.language) == wanted }.takeIf { it >= 0 }
    } ?: tracks.indexOfFirst { it.supported }.takeIf { it >= 0 }

/** Which subtitle to show: one of the stream's own tracks, or one of the title's Downloaded Subtitles. */
sealed interface SubtitlePick {
    data class Stream(val index: Int) : SubtitlePick

    data class Downloaded(val index: Int) : SubtitlePick
}

/**
 * As [chooseSubtitleTrack], with the title's [downloaded] subtitles (their languages) among the
 * choices: in each language the stream's own track comes first, so a Downloaded Subtitle is shown
 * when the stream has nothing in the language wanted. Failing both languages, the stream's first
 * track, then the first Downloaded Subtitle.
 */
fun chooseSubtitle(
    stream: List<SubtitleTrack>,
    downloaded: List<String>,
    preferred: String?,
    device: String,
): SubtitlePick? {
    val all = stream + downloaded.map { SubtitleTrack(it, supported = true) }
    val index = chooseSubtitleTrack(all, preferred, device) ?: return null
    return if (index < stream.size) SubtitlePick.Stream(index) else SubtitlePick.Downloaded(index - stream.size)
}

/**
 * OpenSubtitles' [results] in the order the results list shows them: the Profile's [preferred]
 * language, then the [device] language, then every other, most downloaded first within each.
 * Languages compare by base code, as [chooseSubtitleTrack] does, since a preference saved from a
 * stream's track may say `eng` where OpenSubtitles says `en`.
 */
fun orderSubtitleResults(results: List<SubtitleResult>, preferred: String?, device: String): List<SubtitleResult> {
    val leading = listOfNotNull(baseLanguage(preferred), baseLanguage(device)).distinct()
    return results.sortedWith(
        compareBy<SubtitleResult> { result ->
            leading.indexOf(baseLanguage(result.language)).takeIf { it >= 0 } ?: leading.size
        }.thenByDescending { it.downloads },
    )
}

/**
 * When a Profile's Subtitle Preference turns subtitles on for one stream: once, as soon as both the
 * preference and the stream's text tracks are known. Deciding only once is what keeps a viewer's
 * Off in the picker from being undone before the saved preference catches up, which "turn on
 * whenever nothing is selected" would do. [newStream] starts the next channel or title afresh.
 */
class StreamSubtitles {
    private var decided = false

    fun newStream() {
        decided = false
    }

    /** Whether to turn subtitles on now; true at most once per stream. */
    fun shouldTurnOn(preference: SubtitlePreference?, hasText: Boolean): Boolean {
        if (decided || preference == null || !hasText) return false
        decided = true
        return preference.enabled
    }
}
