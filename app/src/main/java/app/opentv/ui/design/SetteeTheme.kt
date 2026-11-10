/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * Settee's theme. Always dark: there's no light scheme to choose.
 *
 * Screens built on `ui/design` read the tokens directly. The Material 3 scheme and typography here
 * are only a bridge, derived from the tokens, so screens still on Material pick up Settee's colours
 * and type until each moves over. They'll look mismatched until then, which is expected. The last
 * migration ticket removes this bridge along with Material.
 */
@Composable
fun SetteeTheme(content: @Composable () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val typography = remember(locale) { bridgeTypography(locale) }
    MaterialTheme(colorScheme = BridgeColors, typography = typography, content = content)
}

/** LOOK.md has no error colour yet; this coral-red stays apart from the Accent. */
private val BridgeError = Color(0xFFFF6B6B)

private val BridgeColors = darkColorScheme(
    primary = SetteeColors.Accent,
    onPrimary = SetteeColors.OnAccent,
    primaryContainer = SetteeColors.Raised,
    onPrimaryContainer = SetteeColors.Text,
    inversePrimary = SetteeColors.OnAccent,
    secondary = SetteeColors.QuietText,
    onSecondary = SetteeColors.Background,
    secondaryContainer = SetteeColors.Raised,
    onSecondaryContainer = SetteeColors.Text,
    tertiary = SetteeColors.Accent,
    onTertiary = SetteeColors.OnAccent,
    tertiaryContainer = SetteeColors.Raised,
    onTertiaryContainer = SetteeColors.Text,
    background = SetteeColors.Background,
    onBackground = SetteeColors.Text,
    surface = SetteeColors.Surface,
    onSurface = SetteeColors.Text,
    surfaceVariant = SetteeColors.Raised,
    onSurfaceVariant = SetteeColors.QuietText,
    surfaceTint = SetteeColors.Accent,
    inverseSurface = SetteeColors.Text,
    inverseOnSurface = SetteeColors.Background,
    error = BridgeError,
    onError = SetteeColors.Background,
    outline = SetteeColors.Rule,
    outlineVariant = SetteeColors.Rule,
    scrim = Color.Black,
    surfaceBright = SetteeColors.Raised,
    surfaceDim = SetteeColors.Background,
    surfaceContainer = SetteeColors.Surface,
    surfaceContainerHigh = SetteeColors.Raised,
    surfaceContainerHighest = SetteeColors.Raised,
    surfaceContainerLow = SetteeColors.Surface,
    surfaceContainerLowest = SetteeColors.Background,
)

/**
 * Every Material slot mapped onto a Settee role. Material's small slots (bodySmall, labelSmall)
 * land on Body too, since nothing in Settee goes under 16 sp.
 */
private fun bridgeTypography(locale: Locale) = Typography(
    displayLarge = SetteeType.Display.forLocale(locale),
    displayMedium = SetteeType.Display.forLocale(locale),
    displaySmall = SetteeType.Hero.forLocale(locale),
    headlineLarge = SetteeType.Hero.forLocale(locale),
    headlineMedium = SetteeType.ScreenTitle.forLocale(locale),
    headlineSmall = SetteeType.ScreenTitle.forLocale(locale),
    titleLarge = SetteeType.Section.forLocale(locale),
    titleMedium = SetteeType.RowTitle,
    titleSmall = SetteeType.CardTitle,
    // Material's default text style, so anything unstyled gets Body.
    bodyLarge = SetteeType.Body,
    bodyMedium = SetteeType.Body,
    bodySmall = SetteeType.Body,
    labelLarge = SetteeType.Body,
    labelMedium = SetteeType.Body,
    labelSmall = SetteeType.Body,
)
