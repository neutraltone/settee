/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.opentv.R
import app.opentv.data.repo.Playable
import app.opentv.player.PlaybackErrors
import app.opentv.ui.design.ActionButton
import app.opentv.ui.design.Message
import app.opentv.ui.design.ScreenFrame

/**
 * A details page whose title isn't stored any more, as a refresh removed it (#495): worded as playing a
 * removed title is (`Playable.Failure.GONE`), with Back highlighted as the one way on.
 */
@Composable
internal fun GoneDetail(onBack: () -> Unit) {
    val context = LocalContext.current
    val gone =
        remember(context) { PlaybackErrors.describe(context, Playable.Failure.GONE) }
    val back = remember { FocusRequester() }
    ScreenFrame {
        Message(
            body = gone.sentence,
            detail = gone.detail,
            below = { ActionButton(stringResource(R.string.common_back), onBack, Modifier.focusRequester(back)) },
        )
    }
    LaunchedEffect(Unit) { back.requestFocus() }
}
