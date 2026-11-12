/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.runtime.Composable

/**
 * Settee's theme. Always dark: there's no light scheme to choose.
 *
 * It has nothing to provide today. Colours, type and shapes are plain tokens ([SetteeColors],
 * [SetteeType], [SetteeShapes]) that screens read directly, and Settee's [Text] picks the face its
 * locale needs by itself. It stays as the one place to add something every screen must share.
 */
@Composable
fun SetteeTheme(content: @Composable () -> Unit) {
    content()
}
