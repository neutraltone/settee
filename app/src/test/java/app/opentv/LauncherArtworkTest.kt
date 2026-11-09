/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import javax.imageio.ImageIO
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Plain Application: OpenTvApp schedules WorkManager on start, which the artwork doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherArtworkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manifestIcon() = ContextCompat.getDrawable(context, context.applicationInfo.icon)

    @Test
    @Config(sdk = [25])
    fun `a launcher below API 26 gets a plain image icon`() {
        assertThat(manifestIcon()).isInstanceOf(BitmapDrawable::class.java)
    }

    @Test
    @Config(sdk = [25])
    fun `a launcher below API 26 shows the mark on its coral tile`() {
        val icon = (manifestIcon() as BitmapDrawable).bitmap

        // Inside the tile's rounded corner, left of the armrest.
        assertThat(icon.getPixel(icon.width / 20, icon.height / 2) and 0xFFFFFF).isEqualTo(CORAL)
    }

    @Test
    fun `a launcher from API 26 on keeps the adaptive icon`() {
        assertThat(manifestIcon()).isInstanceOf(AdaptiveIconDrawable::class.java)
    }

    @Test
    fun `a launcher with themed icons gets a one-colour version of the mark`() {
        val icon = manifestIcon() as AdaptiveIconDrawable

        assertThat(icon.monochrome).isNotNull()
    }

    @Test
    fun `the notification icon is the mark in one colour, with the screen cut out`() {
        // Android draws a small icon from its alpha alone: a coloured tile would be a blank square.
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.drawable.ic_notification)!!.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(bitmap))
        }
        val pixels = IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
        val solid = pixels.filter { Color.alpha(it) == 255 }

        assertThat(solid.map { it and 0xFFFFFF }.toSet()).hasSize(1)
        // The screen's middle, between the eyes and above the smile, is see-through.
        assertThat(Color.alpha(bitmap.getPixel(size / 2, size * 45 / 100))).isEqualTo(0)
    }

    @Test
    fun `the TV banner is an opaque 320 by 180 image`() {
        val banner = context.resources.openRawResource(context.applicationInfo.banner).use(ImageIO::read)

        assertThat(banner).isNotNull()
        assertThat(banner.width).isEqualTo(320)
        assertThat(banner.height).isEqualTo(180)
        assertThat(banner.colorModel.hasAlpha()).isFalse()
    }

    @Test
    fun `the TV banner is Settee's coral`() {
        val banner = context.resources.openRawResource(context.applicationInfo.banner).use(ImageIO::read)

        assertThat(banner.getRGB(4, 4) and 0xFFFFFF).isEqualTo(CORAL)
    }

    private companion object {
        const val CORAL = 0xFF8F6B
    }
}
