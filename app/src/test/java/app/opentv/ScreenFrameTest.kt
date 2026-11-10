/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.opentv.ui.design.ScreenFrame
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeShapes
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeTheme
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Stick's screen is 960 x 540 dp (TV_1080P). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenFrameTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `content sits inside the screen margin`() {
        compose.setTvContent {
            ScreenFrame {
                Box(Modifier.fillMaxSize().testTag("content"))
            }
        }

        compose.onNodeWithTag("content")
            .assertLeftPositionInRootIsEqualTo(48.dp)
            .assertTopPositionInRootIsEqualTo(27.dp)
            .assertWidthIsEqualTo(864.dp)
            .assertHeightIsEqualTo(486.dp)
    }

    @Test
    fun `a row runs to the screen's edges while its first item starts at the margin`() {
        compose.setTvContent {
            ScreenFrame {
                Column(verticalArrangement = Arrangement.spacedBy(SetteeSpace.RowGap)) {
                    Text("Continue watching", style = SetteeType.Section)
                    LazyRow(
                        Modifier.bleed().testTag("row"),
                        contentPadding = bleedPadding,
                        horizontalArrangement = Arrangement.spacedBy(SetteeSpace.CardGap),
                    ) {
                        items(8) { index ->
                            Box(
                                Modifier
                                    .size(width = 186.dp, height = 105.dp)
                                    .background(SetteeColors.Raised, SetteeShapes.Card)
                                    .testTag("card $index"),
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("row").assertLeftPositionInRootIsEqualTo(0.dp).assertWidthIsEqualTo(960.dp)
        compose.onNodeWithTag("card 0").assertLeftPositionInRootIsEqualTo(48.dp)
        captureScreen("ds_screen_frame_row")
    }

    @Test
    fun `the frame paints its background everywhere but where video plays`() {
        val behind = Color.Red
        compose.setContent {
            SetteeTheme {
                // Stands in for the video surface, which sits behind the app's window.
                Box(Modifier.fillMaxSize().background(behind)) {
                    ScreenFrame {
                        VideoArea(Modifier.size(width = 320.dp, height = 180.dp)) {}
                    }
                }
            }
        }

        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val dp = pixels.width / 960f
        // Inside the video area: what's behind shows through.
        assertThat(pixels[(100 * dp).toInt(), (100 * dp).toInt()]).isEqualTo(behind)
        // In the margin and beside the video: Settee's background.
        assertThat(pixels[(10 * dp).toInt(), (10 * dp).toInt()]).isEqualTo(SetteeColors.Background)
        assertThat(pixels[(600 * dp).toInt(), (100 * dp).toInt()]).isEqualTo(SetteeColors.Background)
        assertThat(pixels[(100 * dp).toInt(), (400 * dp).toInt()]).isEqualTo(SetteeColors.Background)
    }

    @Test
    fun `a video area outside the frame paints nothing beyond the frame`() {
        compose.setContent {
            SetteeTheme {
                Box(Modifier.fillMaxSize().background(Color.Red)) {
                    ScreenFrame(Modifier.size(width = 400.dp, height = 300.dp)) {
                        VideoArea(Modifier.offset(x = 450.dp, y = 300.dp).size(width = 320.dp, height = 180.dp)) {}
                    }
                }
            }
        }

        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val dp = pixels.width / 960f
        fun at(x: Int, y: Int) = pixels[(x * dp).toInt(), (y * dp).toInt()]
        // The frame itself is all background...
        assertThat(at(10, 10)).isEqualTo(SetteeColors.Background)
        assertThat(at(390, 290)).isEqualTo(SetteeColors.Background)
        // ...and nothing spills past its edges.
        assertThat(at(200, 315)).isEqualTo(Color.Red)
        assertThat(at(450, 312)).isEqualTo(Color.Red)
    }
}
