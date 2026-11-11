/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** The players' seek bar. The buffering spinner is a progress node too, but one with no range. */
val seekBar = SemanticsMatcher("is the seek bar") { node ->
    node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.range?.endInclusive?.let { it > 0 } == true
}

/** Whether the seek bar has the Highlight. */
fun ComposeContentTestRule.seekBarIsFocused() = onAllNodes(isFocused() and seekBar).fetchSemanticsNodes().isNotEmpty()
