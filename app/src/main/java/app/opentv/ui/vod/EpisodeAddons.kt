/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.opentv.data.model.Episode
import app.opentv.data.model.StremioStream
import kotlinx.coroutines.flow.Flow

/**
 * The show page's picker of an episode's add-on streams: [ask] opens it on an episode, and is null when
 * there are no add-ons to [find] streams with. The episode stays known for Try again and the stream picked.
 */
@Stable
internal class EpisodeAddons(val picker: AddonPickerState) {
    var find: ((Episode) -> Flow<AddonSearch>)? = null

    var episode by mutableStateOf<Episode?>(null)
        private set

    val ask: ((Episode) -> Unit)?
        get() = find?.let { find ->
            { episode ->
                this.episode = episode
                picker.ask { find(episode) }
            }
        }
}

@Composable
internal fun rememberEpisodeAddons(seriesId: Long, find: ((Episode) -> Flow<AddonSearch>)?): EpisodeAddons {
    val picker = rememberAddonPickerState(seriesId)
    return remember(picker) { EpisodeAddons(picker) }.also { it.find = find }
}

/** [addons]' picker while it's open on an episode; the stream picked calls [onPlay] with that episode. */
@Composable
internal fun EpisodeAddonPicker(addons: EpisodeAddons, onPlay: (Episode, StremioStream) -> Unit) {
    val find = addons.find ?: return
    val episode = addons.episode ?: return
    AddonStreamPicker(addons.picker, { find(episode) }) { onPlay(episode, it.stream) }
}
