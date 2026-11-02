/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.content.Context
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

// Plain Application: OpenTvApp schedules WorkManager on start, which the artwork doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LauncherArtworkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun manifestIcon() = ContextCompat.getDrawable(context, context.applicationInfo.icon)

    @Test
    @Config(sdk = [25])
    fun `a launcher below API 26 gets a plain image icon`() {
        assertThat(manifestIcon()).isInstanceOf(BitmapDrawable::class.java)
    }

    @Test
    fun `a launcher from API 26 on keeps the adaptive icon`() {
        assertThat(manifestIcon()).isInstanceOf(AdaptiveIconDrawable::class.java)
    }

    @Test
    fun `the TV banner is an opaque 320 by 180 image`() {
        val banner = context.resources.openRawResource(context.applicationInfo.banner).use(ImageIO::read)

        assertThat(banner).isNotNull()
        assertThat(banner.width).isEqualTo(320)
        assertThat(banner.height).isEqualTo(180)
        assertThat(banner.colorModel.hasAlpha()).isFalse()
    }
}
