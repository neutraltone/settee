/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.opentv.data.repo.ProfileSettings
import app.opentv.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a player's Subtitles picker saves to the Profile's Subtitle Preference. */
class SubtitlePicks(val onOff: () -> Unit, val onPicked: (language: String?) -> Unit)

/**
 * Plays every stream from the active Profile's Subtitle Preference, and returns what the
 * Subtitles picker calls so the viewer's choices are saved. Shared by the live and VOD players so
 * the two can't drift. Only the picker saves: automatic choices never change the preference.
 */
@Composable
fun rememberProfileSubtitles(
    controller: PlayerController,
    profileSettings: ProfileSettings,
    scope: CoroutineScope,
): SubtitlePicks {
    val preference by profileSettings.subtitlePreference.collectAsState(initial = null)
    val tracks by controller.tracks.collectAsState()
    LaunchedEffect(tracks, preference) { controller.applySubtitlePreference(preference) }
    return remember(profileSettings, scope) {
        SubtitlePicks(
            onOff = { scope.launch { profileSettings.setSubtitlesOn(false) } },
            onPicked = { language -> scope.launch { profileSettings.pickSubtitleTrack(language) } },
        )
    }
}
