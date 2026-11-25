/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.player.SkipCue
import app.opentv.ui.design.arriving
import app.opentv.ui.design.rememberArrival

/**
 * Skip intro, Skip recap, or one button for both (#518). Like the Next Episode card it turns up without
 * a press, so it rises into the picture rather than cutting in.
 */
@Composable
internal fun SkipStretchButton(stretch: SkipCue.Stretch, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (stretch.kind) {
        SkipCue.Kind.INTRO -> R.string.player_skip_intro
        SkipCue.Kind.RECAP -> R.string.player_skip_recap
        SkipCue.Kind.RECAP_AND_INTRO -> R.string.player_skip_recap_and_intro
        SkipCue.Kind.INTRO_AND_RECAP -> R.string.player_skip_intro_and_recap
    }
    OverlayButton(stringResource(label), onSkip, modifier.arriving(rememberArrival(), rise = 16.dp))
}
