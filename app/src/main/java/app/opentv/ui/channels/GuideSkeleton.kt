/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.opentv.ui.design.SkeletonGuide

/**
 * The guide's shape while its rows are first built from channels on the device, in the guide's own
 * measures ([SkeletonGuide]). Its first row takes the Highlight when the guide is to ([takeFocus]), as
 * the real guide's first row would; when the rows come, the guide takes it over as it opens.
 */
@Composable
internal fun OpeningGuideSkeleton(takeFocus: Boolean) {
    val opening = remember { FocusRequester() }
    LaunchedEffect(takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        // Once laid out, which is when it can take the Highlight.
        withFrameNanos {}
        runCatching { opening.requestFocus() }
    }
    SkeletonGuide(
        header = HEADER_HEIGHT,
        rowHeight = ROW_HEIGHT,
        channelColumn = CHANNEL_COLUMN,
        halfHour = HALF_HOUR_WIDTH,
        opening = Modifier.focusRequester(opening),
    )
}
