/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SkeletonCards
import app.opentv.ui.design.SkeletonShelf
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settee's skeleton, the waiting pattern for row and grid screens (#581): a row's real title over
 * blank cards, pulsing slowly as one, from the first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkeletonTest {

    @get:Rule
    val compose = createComposeRule(effectContext = AnimationsOff)

    @Test
    fun `it keeps pulsing with the system's animations turned off`() {
        compose.mainClock.autoAdvance = false
        compose.setTvContent {
            ScreenFrame {
                Box(Modifier.fillMaxWidth().testTag("skeleton")) { SkeletonShelf("Recently Added") }
            }
        }
        val frames = (1..12).map {
            compose.mainClock.advanceTimeBy(250)
            val image = compose.onNodeWithTag("skeleton").captureToImage()
            IntArray(image.width * image.height).also { image.readPixels(it) }.contentHashCode()
        }

        assertThat(frames.distinct().size).isGreaterThan(1)
    }

    @Test
    fun `covers fill the screen's width, and no more`() {
        assertFillsTheWidth(SkeletonCards.Covers)
    }

    @Test
    fun `Continue Watching's cards fill the screen's width, and no more`() {
        assertFillsTheWidth(SkeletonCards.ContinueWatching(largeFirst = true))
    }

    private fun assertFillsTheWidth(cards: SkeletonCards) {
        compose.mainClock.autoAdvance = false
        compose.setTvContent { ScreenFrame { SkeletonShelf("Row", cards = cards) } }
        compose.mainClock.advanceTimeBy(100)
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.right
        val rights = compose.onAllNodesWithTag(SKELETON_CARD).fetchSemanticsNodes().map { it.positionInRoot.x + it.size.width }

        // The last card reaches the screen's edge; the one before it doesn't, or the last is wasted.
        assertThat(rights.last()).isAtLeast(width)
        assertThat(rights[rights.size - 2]).isLessThan(width)
    }

    private companion object {
        const val SKELETON_CARD = "skeleton-card"
    }
}
