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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.TmdbKeys
import app.opentv.ui.design.HighlightStyle
import app.opentv.ui.design.HighlightSurface
import app.opentv.ui.design.Icon
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
    Tmdb(R.string.settings_pane_tmdb, R.string.settings_pane_tmdb_hint, Icons.Filled.Movie),
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

    /** The row that opened a screen of its own, which takes the Highlight back on return. */
    internal var openedRow: String? = null
}

/**
 * Where the Highlight goes when Compose starts again from the top of Settings: into the pane if it
 * was there, else to the open section. A row taking the Highlight back from its own screen
 * (screenRow) is let through. Returns true when the pane couldn't take it yet and is worth trying
 * again a frame later: it can be empty for that moment, as when Enter your PIN goes just as the
 * settings arrive.
 */
private fun reenter(
    memory: SettingsMemory,
    lastIn: LastHighlight,
    pane: FocusRequester,
    current: FocusRequester,
): Boolean {
    if (memory.openedRow != null) return false
    val wasInPane = lastIn.pane
    val intoPane = wasInPane && runCatching { pane.requestFocus() }.getOrNull() == true
    if (!intoPane) runCatching { current.requestFocus() }
    return wasInPane && !intoPane
}

/** Whether the Highlight was last in the pane or the sections. Not state: nothing draws from it. */
private class LastHighlight {
    var pane = false
}

/** Test tags for what a test can't find by its text alone. */
object SettingsTags {
    const val SECTION = "settings-section"
    const val PANE_TITLE = "settings-pane-title"
    const val CODE_BLOCK = "settings-code-block"
}

/**
 * Settings (docs/LOOK.md, Settings): the sections on the left, and the highlighted section's
 * settings beside them, with no OK needed to see what's inside.
 */
@Composable
fun SettingsScreen(
    memory: SettingsMemory,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddProvider: () -> Unit = {},
    onEditProvider: (sourceId: Long) -> Unit = {},
    onOpenChannels: () -> Unit = {},
    onOpenFontLicences: () -> Unit = {},
    // A seam so tests can show each answer without reaching GitHub.
    checkForUpdate: UpdateCheck = CheckGitHub,
    // Likewise, so tests can give TMDB's answer to a key without reaching TMDB; null for the app's own.
    tmdbKeys: TmdbKeys? = null,
) {
    val current = remember { FocusRequester() }
    val pane = remember { FocusRequester() }
    var inPane by remember { mutableStateOf(false) }
    // Where the Highlight last was, which outlives the row it was on (inPane goes false with it).
    val lastIn = remember { LastHighlight() }
    var paneRetries by remember { mutableIntStateOf(0) }
    LaunchedEffect(paneRetries) {
        if (paneRetries == 0) return@LaunchedEffect
        withFrameNanos {}
        runCatching { pane.requestFocus() }
    }
    BackHandler { if (inPane) runCatching { current.requestFocus() } else onBack() }
    LaunchedEffect(Unit) {
        // A frame first: until the input mode has gone to keyboard, nothing can take focus.
        withFrameNanos {}
        if (memory.openedRow != null) {
            // Coming back from a row's own screen: the row takes the Highlight itself (screenRow).
            // If it's gone, such as a provider removed meanwhile, start on the section instead.
            withFrameNanos {}
            withFrameNanos {}
            if (memory.openedRow == null) return@LaunchedEffect
            memory.openedRow = null
        }
        runCatching { current.requestFocus() }
    }
    Row(
        modifier
            .fillMaxSize()
            .background(SetteeColors.Background)
            // When the row with the Highlight goes (a provider, guide or add-on removed, Enter your
            // PIN once the PIN is in), Compose starts again from the first thing on screen, and
            // highlighting a section opens it: so Settings jumped to Providers. Going back into the
            // pane, or to the open section, keeps the viewer where they were. One rule for every
            // pane, rather than each pane moving the Highlight after its own removals. A row taking
            // the Highlight back from its own screen (screenRow) is let through.
            .focusProperties { onEnter = { if (reenter(memory, lastIn, pane, current)) paneRetries++ } }
            .focusGroup(),
    ) {
        Column(
            Modifier
                .width(SECTIONS_WIDTH)
                .fillMaxHeight()
                .background(SetteeColors.Surface)
                .padding(horizontal = SetteeSpace.ScreenSide, vertical = SetteeSpace.ScreenTopBottom)
                .onFocusChanged { if (it.hasFocus) lastIn.pane = false }
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
                .onFocusChanged {
                    inPane = it.hasFocus
                    if (it.hasFocus) lastIn.pane = true
                }
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
                CompositionLocalProvider(LocalSettingsMemory provides memory) {
                    Pane(
                        section,
                        onAddProvider,
                        onEditProvider,
                        onOpenChannels,
                        onOpenFontLicences,
                        checkForUpdate,
                        tmdbKeys ?: ServiceLocator.get(LocalContext.current).tmdbKeys,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pane(
    section: SettingsSection,
    onAddProvider: () -> Unit,
    onEditProvider: (sourceId: Long) -> Unit,
    onOpenChannels: () -> Unit,
    onOpenFontLicences: () -> Unit,
    checkForUpdate: UpdateCheck,
    tmdbKeys: TmdbKeys,
) {
    when (section) {
        SettingsSection.Providers -> ProvidersPane(onAddProvider, onEditProvider)
        SettingsSection.Guide -> GuidePane()
        SettingsSection.Channels -> ChannelsPane(onOpenChannels)
        SettingsSection.Display -> DisplayPane()
        SettingsSection.Recording -> RecordingPane()
        SettingsSection.Parental -> ParentalPane()
        SettingsSection.Devices -> DevicesPane()
        SettingsSection.Addons -> AddonsPane()
        SettingsSection.Tmdb -> TmdbPane(tmdbKeys)
        SettingsSection.Manager -> ManagerPane()
        SettingsSection.About -> AboutPane(onOpenFontLicences, checkForUpdate)
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
