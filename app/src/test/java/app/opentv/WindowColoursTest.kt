/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.opentv.ui.design.SetteeColors
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The window shows its XML colours until Compose draws the first frame. If they drift from the
 * tokens, every launch flashes the old colour first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WindowColoursTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the window behind Compose uses Settee's colours`() {
        assertThat(context.getColor(R.color.window_background)).isEqualTo(SetteeColors.Background.toArgb())
        assertThat(context.getColor(R.color.accent)).isEqualTo(SetteeColors.Accent.toArgb())
    }
}
