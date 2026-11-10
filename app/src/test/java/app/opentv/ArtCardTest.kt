/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import app.opentv.ui.design.ArtCard
import app.opentv.ui.design.ArtCardSize
import app.opentv.ui.design.ProgressBar
import app.opentv.ui.design.QuietLabel
import app.opentv.ui.design.SetteeSpace
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The art cards, quiet labels and progress bars Home's rows are built from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtCardTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val artwork = PlaceholderArtwork()

    private fun setCards() = compose.setTvContent {
        Row(
            Modifier.padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            ArtCard("Collateral", "https://example.com/1.jpg", {}, ArtCardSize.Large, progress = 0.4f)
            ArtCard("Severance", "https://example.com/2.jpg", {
            }, ArtCardSize.Landscape, status = listOf("S2 E4"), badge = "FHD")
            ArtCard(
                "The Lord of the Rings: The Return of the King",
                "https://example.com/3.jpg",
                {},
                ArtCardSize.Portrait,
                status = listOf("S4 E3", "22 min left"),
                progress = 0.7f,
            )
            ArtCard("Thief", null, {}, ArtCardSize.Portrait)
        }
    }

    @Test
    fun `art cards come in their three sizes, with only an unstarted title's name on it`() {
        setCards()

        assertThat(compose.onNode(hasText("Collateral") and hasClickAction()).getUnclippedBoundsInRoot().width)
            .isEqualTo(300.dp)
        assertThat(compose.onNode(hasText("Severance") and hasClickAction()).getUnclippedBoundsInRoot().width)
            .isEqualTo(186.dp)
        assertThat(compose.onNode(hasText("Thief") and hasClickAction()).getUnclippedBoundsInRoot().width)
            .isEqualTo(118.dp)
        compose.onNodeWithText("22 min left", useUnmergedTree = true).assertExists()
        captureScreen("ds_art_card_resting")
    }

    @Test
    fun `a highlighted cover moves its progress bar and text in clear of the ring`() {
        setCards()

        compose.onNode(hasText("22 min left") and hasClickAction()).requestFocus().assertIsFocused()
        captureScreen("ds_art_card_highlighted")
        compose.onNode(hasText("Collateral") and hasClickAction()).requestFocus().assertIsFocused()
        captureScreen("ds_art_card_large_highlighted")
    }

    @Test
    fun `a quiet label and a progress bar`() {
        compose.setTvContent {
            Column(Modifier.padding(48.dp).width(240.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                QuietLabel("Now: Pointless")
                QuietLabel("A line far too long for the space it has, cut off at its end")
                ProgressBar(0f)
                ProgressBar(0.35f)
                ProgressBar(1f)
            }
        }

        compose.onNodeWithText("Now: Pointless").assertExists()
        captureScreen("ds_quiet_label_and_progress_bar")
    }
}
