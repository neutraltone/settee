/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

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
