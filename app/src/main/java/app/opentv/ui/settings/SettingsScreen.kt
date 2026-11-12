/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.SetteeColors
import app.opentv.ui.design.SetteeSpace
import app.opentv.ui.design.SetteeType
import app.opentv.ui.design.Text

/** Settings' sections, in the order the left pane lists them. */
enum class SettingsSection(@StringRes val title: Int, @StringRes val hint: Int, val icon: ImageVector) {
    Providers(R.string.settings_pane_providers, R.string.settings_pane_providers_hint, Icons.Filled.Dns),
    Guide(R.string.settings_pane_guide, R.string.settings_pane_guide_hint, Icons.Filled.LiveTv),
    Channels(R.string.settings_pane_channels, R.string.settings_pane_channels_hint, Icons.Filled.GridView),
    Display(R.string.settings_pane_display, R.string.settings_pane_display_hint, Icons.Filled.Tune),
    Recording(R.string.settings_pane_recording, R.string.settings_pane_recording_hint, Icons.Filled.Storage),
    Parental(R.string.settings_pane_parental, R.string.settings_pane_parental_hint, Icons.Filled.Lock),
    Devices(R.string.settings_pane_devices, R.string.settings_pane_devices_hint, Icons.Filled.Sync),
    Addons(R.string.settings_pane_addons, R.string.settings_pane_addons_hint, Icons.Filled.Extension),
    Manager(R.string.settings_pane_manager, R.string.settings_pane_manager_hint, Icons.Filled.PhoneAndroid),
    About(R.string.settings_pane_about, R.string.settings_pane_about_hint, Icons.Filled.Info),
}

/**
 * Which section Settings was on, kept outside the screen so leaving Settings and coming back
 * returns to it, as the tabs remember theirs.
 */
@Stable
class SettingsMemory(section: SettingsSection = SettingsSection.Providers) {
    var section by mutableStateOf(section)
}

/** Test tags for what a test can't find by its text alone. */
object SettingsTags {
    const val SECTION = "settings-section"
    const val PANE_TITLE = "settings-pane-title"
}

/**
 * Settings (docs/LOOK.md, Settings): the sections on the left, and the highlighted section's
 * settings beside them, with no OK needed to see what's inside.
 */
@Composable
fun SettingsScreen(memory: SettingsMemory, onBack: () -> Unit) {
    val current = remember { FocusRequester() }
    val pane = remember { FocusRequester() }
    var inPane by remember { mutableStateOf(false) }
    BackHandler { if (inPane) runCatching { current.requestFocus() } else onBack() }
    LaunchedEffect(Unit) {
        // A frame first: until the input mode has gone to keyboard, nothing can take focus.
        withFrameNanos {}
        runCatching { current.requestFocus() }
    }
    Row(Modifier.fillMaxSize().background(SetteeColors.Background)) {
        Column(
            Modifier
                .width(SECTIONS_WIDTH)
                .fillMaxHeight()
                .background(SetteeColors.Surface)
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
                .focusGroup(),
        ) {
            Text(stringResource(R.string.nav_settings), SetteeType.ScreenTitle)
            Spacer(Modifier.height(SetteeSpace.RowGap))
            SettingsSection.entries.forEach { section ->
                SectionRow(
                    section = section,
                    isCurrent = section == memory.section,
                    onHighlighted = { memory.section = section },
                    onClick = { runCatching { pane.requestFocus() } },
                    modifier = (if (section == memory.section) Modifier.focusRequester(current) else Modifier)
                        .focusProperties { right = pane },
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
                .onFocusChanged { inPane = it.hasFocus }
                // Left always goes back to the sections: nothing in a pane sits beside anything
                // else, and the nearest section by position is rarely the one being shown.
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) runCatching { current.requestFocus() }
                    true
                },
        ) {
            val section = memory.section
            Text(stringResource(section.title), SetteeType.ScreenTitle, Modifier.testTag(SettingsTags.PANE_TITLE))
            Text(stringResource(section.hint), SetteeType.Body, color = SetteeColors.QuietText)
            Spacer(Modifier.height(SetteeSpace.RowGap))
            Column(
                Modifier.focusRequester(pane).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            ) {
                when (section) {
                    SettingsSection.Display -> DisplayPane()
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    section: SettingsSection,
    isCurrent: Boolean,
    onHighlighted: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HighlightSurface(
        onClick = onClick,
        style = HighlightStyle.Row,
        modifier = modifier
            .testTag(SettingsTags.SECTION)
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onHighlighted() },
    ) { highlighted ->
        val colour = when {
            highlighted -> SetteeColors.OnAccent
            isCurrent -> SetteeColors.Accent
            else -> SetteeColors.Text
        }
        Row(
            Modifier.padding(horizontal = SECTION_PADDING, vertical = SECTION_PADDING_VERTICAL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(section.icon, contentDescription = null, tint = colour, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(SECTION_PADDING))
            Text(
                stringResource(section.title),
                SetteeType.Synopsis,
                color = colour,
                maxLines = 1,
            )
        }
    }
}

private val SECTIONS_WIDTH = 360.dp
private val SECTION_PADDING = 12.dp
private val SECTION_PADDING_VERTICAL = 9.dp
private val ICON_SIZE = 22.dp
private val ROW_GAP = 8.dp
