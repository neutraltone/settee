/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * PROTOTYPE for #152: the screens Direction E hasn't covered, and its loose ends. Throwaway, never
 * merged. Static states with the settled look from #151, to choose a structure from.
 */
private val Bg = Color(0xFF1A1416)
private val Panel = Color(0xFF281F22)
private val Raised = Color(0xFF362A2E)
private val Rule = Color(0xFF45373B)
private val Ink = Color(0xFFF8EFEA)
private val Soft = Color(0xFFBDABA8)
private val Coral = Color(0xFFFF8F6B)
private val OnCoral = Color(0xFF2A130B)
private val Title = YoungSerif
private val Body = Alegreya
private val Square = RoundedCornerShape(2.dp)
private val Round = RoundedCornerShape(10.dp)
private val Pill = RoundedCornerShape(50)

enum class Remaining {
    VOD_BAR, VOD_STEP_SEEK, VOD_STEP_BUTTONS, TRACKS, NEXT_EPISODE, STILL_WATCHING,
    BANNER_CARD, BANNER_STRIP, LIVE_LIST, LIVE_BAR, PLAY_ERROR,
    SETTINGS_PANES, SETTINGS_PANES_DISPLAY, SETTINGS_TILES, RAIL_FOOT,
    ONE_PAGE, WELCOME, CHOOSE, FORM, CONNECT_ERROR, HOME_EMPTY,
    REC_LIST, REC_SIDEBAR, REC_EMPTY,
    SEASON_HEADERS, SEASON_PILLS, SEASON_SIDEBAR,
    FAV_MIXED, FAV_OWN_ROW,
    FIND_SEARCH, FIND_LETTERS, FIND_NONE,
    PLAYER2_SEEK, PLAYER2_DOWN, PLAYER2_UP, LIVE2_SEEK, LIVE2_DOWN,
    ONE_PAGE2, QR_SETUP,
    RAIL_TODAY, RAIL_NO_PANEL, RAIL_TIGHT, RAIL_LABELS,
}

@Composable
fun RemainingScreen(screen: Remaining) {
    when (screen) {
        Remaining.VOD_BAR -> VodPlayer(step = 0)
        Remaining.VOD_STEP_SEEK -> VodPlayer(step = 1)
        Remaining.VOD_STEP_BUTTONS -> VodPlayer(step = 2)
        Remaining.TRACKS -> Tracks()
        Remaining.NEXT_EPISODE -> NextEpisode()
        Remaining.STILL_WATCHING -> StillWatching()
        Remaining.BANNER_CARD -> BannerCard()
        Remaining.BANNER_STRIP -> BannerStrip()
        Remaining.LIVE_LIST -> LiveList()
        Remaining.LIVE_BAR -> LiveBar()
        Remaining.PLAY_ERROR -> PlayError()
        Remaining.SETTINGS_PANES -> SettingsPanes(onDisplay = false)
        Remaining.SETTINGS_PANES_DISPLAY -> SettingsPanes(onDisplay = true)
        Remaining.SETTINGS_TILES -> SettingsTiles()
        Remaining.RAIL_FOOT -> RailFoot()
        Remaining.ONE_PAGE -> OnePage()
        Remaining.WELCOME -> Welcome()
        Remaining.CHOOSE -> Choose()
        Remaining.FORM -> Form()
        Remaining.CONNECT_ERROR -> ConnectError()
        Remaining.HOME_EMPTY -> HomeEmpty()
        Remaining.REC_LIST -> RecList()
        Remaining.REC_SIDEBAR -> RecSidebar()
        Remaining.REC_EMPTY -> RecEmpty()
        Remaining.SEASON_HEADERS -> SeasonHeaders()
        Remaining.SEASON_PILLS -> SeasonPills()
        Remaining.SEASON_SIDEBAR -> SeasonSidebar()
        Remaining.FAV_MIXED -> FavMixed()
        Remaining.FAV_OWN_ROW -> FavOwnRow()
        Remaining.FIND_SEARCH -> FindSearch()
        Remaining.FIND_LETTERS -> FindLetters()
        Remaining.FIND_NONE -> FindNone()
        Remaining.PLAYER2_SEEK -> Player2(live = false, focus = Focus2.SEEK)
        Remaining.PLAYER2_DOWN -> Player2(live = false, focus = Focus2.ICONS)
        Remaining.PLAYER2_UP -> Player2(live = false, focus = Focus2.CLOSE)
        Remaining.LIVE2_SEEK -> Player2(live = true, focus = Focus2.SEEK)
        Remaining.LIVE2_DOWN -> Player2(live = true, focus = Focus2.ICONS)
        Remaining.ONE_PAGE2 -> OnePage2()
        Remaining.QR_SETUP -> QrSetup()
        Remaining.RAIL_TODAY -> RailOption(start = 48, end = 8, panel = true)
        Remaining.RAIL_NO_PANEL -> RailOption(start = 48, end = 8, panel = false)
        Remaining.RAIL_TIGHT -> RailOption(start = 16, end = 16, panel = true, showMargin = true)
        Remaining.RAIL_LABELS -> RailOption(start = 48, end = 14, panel = true, labels = true)
    }
}

// ---------- Shared pieces ----------

@Composable
private fun Screen(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize().background(Bg)) { content() }

@Composable
private fun Rail(current: Int, content: @Composable () -> Unit) =
    WithRail(Bg, { PlainRail(current, Panel, Soft, Coral, Coral, Coral, OnCoral) }, content)

@Composable
private fun H1(text: String, modifier: Modifier = Modifier) = OneLine(text, Ink, 28, Title, modifier)

@Composable
private fun H2(text: String) {
    Column(Modifier.fillMaxWidth()) {
        OneLine(text, Ink, 18, Title)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Para(text: String, color: Color = Soft, size: Int = 18, modifier: Modifier = Modifier) {
    Text(text, color = color, fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = size.sp, lineHeight = (size * 1.3f).sp, modifier = modifier)
}

@Composable
private fun Button(text: String, icon: ImageVector? = null, initial: Boolean = false) {
    Tile(initial = initial) { focused ->
        Row(
            Modifier.height(40.dp).clip(Pill).background(if (focused) Coral else Raised).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (focused) OnCoral else Ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            OneLine(text, if (focused) OnCoral else Ink, 17, Body, weight = FontWeight.Bold)
        }
    }
}

/** A row that fills with coral, as every row settled in #151 does. */
@Composable
private fun Item(
    modifier: Modifier = Modifier,
    initial: Boolean = false,
    height: Int = 38,
    shape: Shape = Square,
    rest: Color = Color.Transparent,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    Tile(modifier.fillMaxWidth(), initial = initial) { focused ->
        Row(
            Modifier.fillMaxWidth().height(height.dp).clip(shape).background(if (focused) Coral else rest).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) { content(focused) }
    }
}

@Composable
private fun TextRow(label: String, trailing: String = "", initial: Boolean = false, current: Boolean = false, height: Int = 38) {
    Item(initial = initial, height = height) { focused ->
        OneLine(
            label,
            when {
                focused -> OnCoral
                current -> Coral
                else -> Ink
            },
            16,
            Body,
            Modifier.weight(1f),
            if (current || focused) FontWeight.Bold else FontWeight.Medium,
        )
        OneLine(trailing, if (focused) OnCoral else Soft, 16, Body)
    }
}

@Composable
private fun Video(seed: Int = 13) = Art(seed, Modifier.fillMaxSize())

@Composable
private fun FootScrim(modifier: Modifier = Modifier, height: Int = 230) {
    Box(modifier.fillMaxWidth().height(height.dp).background(Brush.verticalGradient(0f to Bg.copy(alpha = 0f), 0.45f to Bg.copy(alpha = 0.9f), 1f to Bg)))
}

@Composable
private fun QuietLine(text: String, modifier: Modifier = Modifier) = OneLine(text, Soft, 16, Body, modifier)

private val channels get() = ProtoData.channels

// ---------- Player: movies and episodes ----------

@Composable
private fun SeekBar(modifier: Modifier = Modifier, highlighted: Boolean) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OneLine("31:42", Ink, 18, Body, weight = FontWeight.Medium)
        Box(Modifier.weight(1f).height(16.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(if (highlighted) 8.dp else 4.dp).clip(Pill).background(Rule)) {
                Box(Modifier.fillMaxWidth(0.62f).fillMaxHeight().background(Coral))
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.fillMaxWidth(0.62f))
                if (highlighted) Box(Modifier.size(16.dp).clip(Pill).background(Ink))
            }
        }
        OneLine("18 min left", Soft, 18, Body, weight = FontWeight.Medium)
    }
}

@Composable
private fun VodButtons(initialFirst: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button("Pause", Icons.Filled.Pause, initial = initialFirst)
        Button("Back 10s", Icons.Filled.FastRewind)
        Button("Forward 30s", Icons.Filled.FastForward)
        Button("Subtitles", Icons.Filled.ClosedCaption)
        Button("Audio", Icons.Filled.Audiotrack)
        Button("Options", Icons.Filled.MoreVert)
    }
}

/** 0: everything together. 1: first press shows the seek bar only. 2: Down from there brings the buttons. */
@Composable
private fun VodPlayer(step: Int) = Screen {
    Video()
    FootScrim(Modifier.align(Alignment.BottomCenter), height = if (step == 1) 150 else 230)
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OneLine("Harbour Lights", Ink, 28, Title)
        OneLine("Season 2, episode 5  ·  Slack Water", Soft, 18, Body, weight = FontWeight.Medium)
        if (step == 1) {
            Tile(initial = true) { SeekBar(highlighted = it) }
        } else {
            SeekBar(highlighted = false)
            VodButtons(initialFirst = true)
        }
    }
}

@Composable
private fun Tracks() = Screen {
    Video()
    Row(Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.width(340.dp).fillMaxHeight().background(Panel).padding(start = 18.dp, end = 48.dp, top = 27.dp, bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OneLine("Subtitles", Ink, 28, Title)
            Spacer(Modifier.height(10.dp))
            listOf("Off", "English", "English (SDH)", "French", "Spanish").forEachIndexed { index, label ->
                Item(initial = index == 1) { focused ->
                    OneLine(label, if (focused) OnCoral else Ink, 16, Body, Modifier.weight(1f), FontWeight.Medium)
                    if (index == 1) Icon(Icons.Filled.Check, null, tint = if (focused) OnCoral else Coral, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Para("Your choice follows you to the next title.", size = 16)
        }
    }
}

@Composable
private fun NextEpisode() = Screen {
    Video()
    Column(
        Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 27.dp).width(330.dp).clip(Round).background(Panel),
    ) {
        Art(14, Modifier.fillMaxWidth().aspectRatio(2.4f))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OneLine("Up Next  ·  S2 E6", Coral, 16, Body, weight = FontWeight.Bold)
            OneLine("Ebb", Ink, 18, Title)
            OneLine("Plays in 12 seconds", Soft, 16, Body, weight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Play now", Icons.Filled.PlayArrow, initial = true)
                Button("Watch credits")
            }
        }
    }
}

@Composable
private fun StillWatching() = Screen {
    Video()
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.85f)))
    Column(Modifier.align(Alignment.Center).width(460.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OneLine("Still watching?", Ink, 32, Title)
        Para("Settee has played three episodes in a row. Keep going?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Keep watching", Icons.Filled.PlayArrow, initial = true)
            Button("Stop")
        }
    }
}

// ---------- Player: live TV ----------

@Composable
private fun BannerCard() = Screen {
    Video(23)
    Column(
        Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 27.dp).width(460.dp).clip(Round).background(Bg.copy(alpha = 0.92f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(channels[1], 32.dp, fontFamily = Title)
            Spacer(Modifier.width(10.dp))
            OneLine(channels[1].name, Ink, 18, Title)
        }
        OneLine("News at Eight", Ink, 18, Body, weight = FontWeight.Bold)
        ProgressLine(0.3f, Rule, Coral)
        OneLine("Next at 20:30  ·  Gardeners' Hour", Soft, 16, Body, weight = FontWeight.Medium)
    }
}

@Composable
private fun BannerStrip() = Screen {
    Video(23)
    FootScrim(Modifier.align(Alignment.BottomCenter), height = 170)
    Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LogoTile(channels[1], 56.dp, fontFamily = Title)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OneLine(channels[1].name, Ink, 28, Title)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OneLine("Now  News at Eight", Ink, 18, Body, Modifier.weight(1f), FontWeight.Bold)
                OneLine("Next 20:30  Gardeners' Hour", Soft, 18, Body, weight = FontWeight.Medium)
            }
            ProgressLine(0.3f, Rule, Coral)
        }
    }
}

@Composable
private fun LiveList() = Screen {
    Video(23)
    Column(
        Modifier.width(400.dp).fillMaxHeight().background(Panel).padding(start = 48.dp, end = 10.dp, top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OneLine("All channels", Ink, 28, Title)
        Spacer(Modifier.height(8.dp))
        Item(height = 46) { focused ->
            OneLine("Last", if (focused) OnCoral else Coral, 16, Body, weight = FontWeight.Bold)
            LogoTile(channels[4], 30.dp, fontFamily = Title)
            OneLine(channels[4].name, if (focused) OnCoral else Ink, 16, Body, Modifier.weight(1f), FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
        channels.take(7).forEachIndexed { index, channel ->
            Item(initial = index == 1, height = 56) { focused ->
                LogoTile(channel, 34.dp, fontFamily = Title)
                Column(Modifier.weight(1f)) {
                    OneLine(channel.name, if (focused) OnCoral else Ink, 16, Body, weight = FontWeight.Bold)
                    OneLine(channel.programmes.first().title, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
                }
                if (index == 1) Icon(Icons.Filled.PlayArrow, null, tint = if (focused) OnCoral else Coral, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LiveBar() = Screen {
    Video(23)
    FootScrim(Modifier.align(Alignment.BottomCenter))
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(channels[1], 40.dp, fontFamily = Title)
            Spacer(Modifier.width(12.dp))
            OneLine("News at Eight", Ink, 28, Title, Modifier.weight(1f))
            OneLine("20:00 to 20:30", Soft, 18, Body, weight = FontWeight.Medium)
        }
        ProgressLine(0.3f, Rule, Coral)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Pause", Icons.Filled.Pause, initial = true)
            Button("Record", Icons.Filled.FiberManualRecord)
            Button("Subtitles", Icons.Filled.ClosedCaption)
            Button("Audio", Icons.Filled.Audiotrack)
            Button("Quality", Icons.Filled.Tune)
            Button("Options", Icons.Filled.MoreVert)
        }
    }
}

@Composable
private fun PlayError() = Screen {
    Column(Modifier.align(Alignment.Center).width(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OneLine("This channel won't play", Ink, 32, Title)
        Para("Its video is damaged, and that's at your provider's end. Try another channel.", Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Try again", Icons.Filled.Refresh, initial = true)
            Button("Channel list", Icons.Filled.LiveTv)
        }
        QuietLine("Code 3003  ·  HTTP 200  ·  MPEG-TS")
    }
}

// ---------- Settings ----------

private data class Section(val icon: ImageVector, val name: String, val sub: String)

private val sections = listOf(
    Section(Icons.Filled.Dns, "Providers", "Add, change or refresh the service you pay for"),
    Section(Icons.Filled.LiveTv, "TV guide", "Where your TV guide comes from"),
    Section(Icons.Filled.GridView, "Channels", "Hide or favourite channels"),
    Section(Icons.Filled.Tune, "Display and playback", "Subtitles, the live preview and more"),
    Section(Icons.Filled.Storage, "Recording", "Where recordings are saved"),
    Section(Icons.Filled.Lock, "Parental lock", "Hide categories behind a PIN"),
    Section(Icons.Filled.Sync, "Your devices", "Keep progress in step between devices"),
    Section(Icons.Filled.Extension, "Add-ons", "Stremio add-ons you've set up"),
    Section(Icons.Filled.PhoneAndroid, "Manage on phone or laptop", "Organise channels with a keyboard"),
    Section(Icons.Filled.Info, "About", "Version, updates and licences"),
)

@Composable
private fun SettingRow(label: String, value: String, initial: Boolean = false, toggle: Boolean? = null) {
    Item(initial = initial, height = 52, shape = Round, rest = Panel) { focused ->
        OneLine(label, if (focused) OnCoral else Ink, 16, Body, Modifier.weight(1f), FontWeight.Bold)
        if (toggle != null) {
            Box(Modifier.size(width = 44.dp, height = 24.dp).clip(Pill).background(if (toggle) (if (focused) OnCoral else Coral) else Rule)) {
                Box(Modifier.align(if (toggle) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(18.dp).clip(Pill).background(if (focused && toggle) Coral else Ink))
            }
        } else {
            OneLine(value, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
            Icon(Icons.Filled.ChevronRight, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsPanes(onDisplay: Boolean) = Screen {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(360.dp).fillMaxHeight().background(Panel).padding(start = 48.dp, end = 10.dp, top = 27.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            H1("Settings")
            Spacer(Modifier.height(8.dp))
            sections.forEachIndexed { index, section ->
                val here = index == 3
                Item(initial = here && !onDisplay, height = 41) { focused ->
                    Icon(section.icon, null, tint = if (focused) OnCoral else if (here) Coral else Soft, modifier = Modifier.size(20.dp))
                    OneLine(section.name, if (focused) OnCoral else if (here) Coral else Ink, 16, Body, weight = if (here || focused) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 48.dp, top = 27.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            H1("Display and playback")
            Spacer(Modifier.height(4.dp))
            SettingRow("Subtitles", "English", initial = onDisplay)
            SettingRow("Sleep timer", "Off")
            SettingRow("Live preview on Home", "", toggle = true)
            SettingRow("Play the next episode by itself", "", toggle = true)
            SettingRow("Language", "System default")
            SettingRow("Live TV", "", toggle = true)
            SettingRow("Movies", "", toggle = true)
        }
    }
}

@Composable
private fun SettingsTiles() = Screen {
    Column(
        Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        H1("Settings")
        sections.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { column, section ->
                    Item(Modifier.weight(1f), initial = row == 0 && column == 0, height = 76, shape = Round, rest = Panel) { focused ->
                        Icon(section.icon, null, tint = if (focused) OnCoral else Coral, modifier = Modifier.size(26.dp))
                        Column(Modifier.weight(1f)) {
                            OneLine(section.name, if (focused) OnCoral else Ink, 18, Body, weight = FontWeight.Bold)
                            OneLine(section.sub, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** Today Search and Settings sit in a top bar. A left rail has no top bar, so they have to go somewhere. */
@Composable
private fun RailFoot() = Screen {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(104.dp).fillMaxHeight().background(Panel).padding(start = 48.dp, end = 8.dp, top = 27.dp, bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                Icons.Filled.Search,
                Icons.Filled.Home,
                Icons.Filled.LiveTv,
                Icons.Filled.Movie,
                Icons.Filled.Tv,
                Icons.Filled.FiberManualRecord,
            ).forEachIndexed { index, icon ->
                Tile(initial = index == 0) { focused ->
                    Box(Modifier.size(48.dp).clip(Round).background(if (focused) Coral else Panel), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (focused) OnCoral else if (index == 1) Coral else Soft, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            listOf(Icons.Filled.Person, Icons.Filled.Settings).forEach { icon ->
                Tile { focused ->
                    Box(Modifier.size(48.dp).clip(Round).background(if (focused) Coral else Panel), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 32.dp, end = 48.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            H2("Continue Watching")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProtoData.continueWatching.take(3).forEach { item ->
                    Art(item.seed ?: 11, Modifier.width(186.dp).aspectRatio(16f / 9f).clip(Round))
                }
            }
        }
    }
}

// ---------- Onboarding ----------

@Composable
private fun ProviderKinds() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Xtream Codes login" to "A server address, username and password",
            "M3U playlist" to "A single link from your provider",
            "Stalker portal" to "A portal address and a MAC address",
        ).forEachIndexed { index, (name, hint) ->
            Item(initial = index == 0, height = 66, shape = Round, rest = Panel) { focused ->
                Column(Modifier.weight(1f)) {
                    OneLine(name, if (focused) OnCoral else Ink, 18, Body, weight = FontWeight.Bold)
                    OneLine(hint, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(22.dp))
            }
        }
        Item(height = 52, shape = Round) { focused ->
            Icon(Icons.Filled.PhoneAndroid, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(22.dp))
            OneLine("Type it on your phone instead", if (focused) OnCoral else Ink, 16, Body, weight = FontWeight.Medium)
        }
    }
}

private const val WELCOME_LINE = "Welcome to Settee."
private const val SETTLE_LINE = "Let's get you settled in."
private const val PLAYS_LINE = "Settee plays the TV you already pay for. It doesn't supply channels, so start by adding your provider."
private const val PRIVACY_LINE = "Your provider details stay on this device. Settee has no accounts and no server of its own."

@Composable
private fun OnePage() = Screen {
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            OneLine(WELCOME_LINE, Ink, 32, Title)
            OneLine(SETTLE_LINE, Coral, 28, Title)
            Spacer(Modifier.height(4.dp))
            Para(PLAYS_LINE, Ink)
            Para(PRIVACY_LINE)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            OneLine("How does your provider connect?", Ink, 18, Title)
            Spacer(Modifier.height(10.dp))
            ProviderKinds()
        }
    }
}

@Composable
private fun Welcome() = Screen {
    Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OneLine(WELCOME_LINE, Ink, 54, Title)
        OneLine(SETTLE_LINE, Coral, 28, Title)
        Spacer(Modifier.height(6.dp))
        Button("Get started", initial = true)
    }
}

@Composable
private fun Choose() = Screen {
    Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OneLine("Add your provider", Ink, 32, Title)
        Para(PLAYS_LINE, Ink)
        ProviderKinds()
        Para(PRIVACY_LINE, size = 16)
    }
}

@Composable
private fun Field(label: String, value: String, initial: Boolean = false) {
    Tile(Modifier.fillMaxWidth(), initial = initial) { focused ->
        Column(
            Modifier.fillMaxWidth().clip(Round).background(Panel)
                .then(if (focused) Modifier.border(3.dp, Coral, Round) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OneLine(label, if (focused) Coral else Soft, 16, Body, weight = FontWeight.Medium)
            OneLine(value, Ink, 18, Body, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun Form() = Screen {
    Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OneLine("Xtream Codes login", Ink, 32, Title)
        Field("Server address", "http://example.com:8080", initial = true)
        Field("Username", "u")
        Field("Password", "••••••••")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Add provider")
            Button("Type it on your phone instead", Icons.Filled.PhoneAndroid)
        }
    }
}

@Composable
private fun ConnectError() = Screen {
    Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OneLine("Can't reach your provider", Ink, 32, Title)
        Para("Check this TV is online and the address is right.", Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button("Try again", Icons.Filled.Refresh, initial = true)
            Button("Change details")
        }
        QuietLine("Timed out after 30 s  ·  Xtream  ·  no response")
    }
}

@Composable
private fun HomeEmpty() = Rail(0) {
    Column(Modifier.fillMaxSize().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        OneLine("Nothing here yet", Ink, 28, Title)
        Para("What you watch and favourite will show up here.", Ink)
    }
}

// ---------- Recordings ----------

private data class Rec(val title: String, val channel: Int, val line: String, val status: String, val failed: Boolean = false)

private val recorded = listOf(
    Rec("News at Eight", 1, "Northgate One  ·  Mon 20:00  ·  28 min", "Watched"),
    Rec("Cup Replay", 3, "Tideline Sport  ·  Sun 15:00  ·  1 h 52 min", "40 min left"),
    Rec("Winter Orchard", 4, "Kino  ·  Sat 21:00", "Didn't finish recording", failed = true),
)
private val scheduled = listOf(
    Rec("The Evening Programme", 0, "Hearth TV  ·  Today 20:00", "Recording now"),
    Rec("Meridian Tonight", 2, "Meridian News  ·  Tomorrow 18:30", "Reminder"),
)

@Composable
private fun RecRow(rec: Rec, initial: Boolean = false) {
    Item(initial = initial, height = 60, shape = Round, rest = Panel) { focused ->
        LogoTile(channels[rec.channel], 36.dp, fontFamily = Title)
        Column(Modifier.weight(1f)) {
            OneLine(rec.title, if (focused) OnCoral else Ink, 17, Body, weight = FontWeight.Bold)
            OneLine(rec.line, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
        }
        OneLine(rec.status, if (focused) OnCoral else if (rec.status == "Watched") Soft else Coral, 16, Body, weight = FontWeight.Medium)
    }
}

@Composable
private fun RecList() = Rail(4) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        H1("Recordings")
        H2("Coming up")
        scheduled.forEach { RecRow(it) }
        Spacer(Modifier.height(4.dp))
        H2("Recorded")
        recorded.forEachIndexed { index, rec -> RecRow(rec, initial = index == 0) }
        Spacer(Modifier.height(4.dp))
        H2("Series links")
    }
}

@Composable
private fun RecSidebar() = Rail(4) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(214.dp).fillMaxHeight().background(Panel).padding(start = 8.dp, end = 8.dp, top = 27.dp)) {
            TextRow("Recorded", "3", current = true)
            TextRow("Coming up", "2")
            TextRow("Series links", "1")
            TextRow("Reminders", "1")
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            H1("Recorded")
            Spacer(Modifier.height(4.dp))
            recorded.forEachIndexed { index, rec -> RecRow(rec, initial = index == 0) }
        }
    }
}

@Composable
private fun RecEmpty() = Rail(4) {
    Column(Modifier.fillMaxSize().padding(end = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        OneLine("No recordings yet", Ink, 28, Title)
        Para("Press Record while you're watching, or pick a programme in the guide.", Ink)
    }
}

// ---------- Loose end: seasons ----------

@Composable
private fun Hero(compact: Boolean = true) {
    Row(verticalAlignment = Alignment.Bottom) {
        OneLine("Harbour Lights", Ink, if (compact) 32 else 54, Title, Modifier.weight(1f))
    }
}

private val episodes = listOf("Home Water" to "✓ Watched", "The Keeper's Log" to "✓ Watched", "The Pilot Boat" to "✓ Watched", "Fog Signal" to "18 min left", "Slack Water" to "", "Ebb" to "")

@Composable
private fun EpisodeRow(name: String, status: String, index: Int, season: Int = 2, initial: Boolean = false) {
    Item(initial = initial, height = 62, shape = Round, rest = Panel) { focused ->
        Art(30 + index + season * 3, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f)) {
            OneLine(name, if (focused) OnCoral else Ink, 17, Body, weight = FontWeight.Bold)
            OneLine("S$season E${index + 1}", if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
        }
        OneLine(status, if (focused) OnCoral else if (status.startsWith("✓")) Soft else Coral, 16, Body, weight = FontWeight.Medium)
    }
}

@Composable
private fun SeasonHeaders() = Screen {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        H2("Season 1")
        EpisodeRow("Departures", "✓ Watched", 7, season = 1)
        Spacer(Modifier.height(6.dp))
        H2("Season 2")
        episodes.take(5).forEachIndexed { index, (name, status) -> EpisodeRow(name, status, index, initial = index == 3) }
        Spacer(Modifier.height(6.dp))
        H2("Season 3")
    }
}

@Composable
private fun SeasonPills() = Screen {
    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hero()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { season ->
                Tile(initial = false) { focused ->
                    Box(
                        Modifier.height(36.dp).clip(Pill).background(if (focused) Coral else if (season == 2) Ink else Raised).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { OneLine("Season $season", if (focused || season == 2) OnCoral else Ink, 16, Body, weight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        episodes.take(6).forEachIndexed { index, (name, status) -> EpisodeRow(name, status, index, initial = index == 3) }
    }
}

@Composable
private fun SeasonSidebar() = Screen {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(240.dp).fillMaxHeight().background(Panel).padding(start = 48.dp, end = 8.dp, top = 27.dp)) {
            OneLine("Harbour Lights", Ink, 18, Title)
            Spacer(Modifier.height(10.dp))
            (1..5).forEach { season -> TextRow("Season $season", if (season == 1) "✓" else "${season + 5}", initial = season == 2, current = season == 2) }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            H1("Season 2")
            Spacer(Modifier.height(6.dp))
            episodes.take(6).forEachIndexed { index, (name, status) -> EpisodeRow(name, status, index) }
        }
    }
}

// ---------- Loose end: favourite channels on Home ----------

@Composable
private fun CoverCard(item: Item) {
    Box(Modifier.width(118.dp).aspectRatio(2f / 3f).clip(Round).background(Raised)) {
        item.seed?.let { Art(it, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Bg.copy(alpha = 0f), 0.95f to Bg)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)) {
            OneLine(item.title, Ink, 16, Body, weight = FontWeight.Bold)
            OneLine(item.watch.label().ifEmpty { item.sub }, Soft, 16, Body, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ChannelCover(channel: Channel, initial: Boolean) {
    Tile(Modifier.width(118.dp), initial = initial) { focused ->
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(Round).background(Raised)
                .then(if (focused) Modifier.border(4.dp, Coral, Round).padding(4.dp).border(2.dp, Bg, RoundedCornerShape(6.dp)) else Modifier),
        ) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) { LogoTile(channel, 58.dp, fontFamily = Title) }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)) {
                OneLine(channel.name, Ink, 16, Body, weight = FontWeight.Bold)
                OneLine(channel.programmes.first().title, if (focused) Coral else Soft, 16, Body, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChannelWide(channel: Channel, initial: Boolean) {
    Tile(Modifier.width(186.dp), initial = initial) { focused ->
        Row(
            Modifier.fillMaxWidth().height(72.dp).clip(Round).background(Raised)
                .then(if (focused) Modifier.border(4.dp, Coral, Round) else Modifier)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoTile(channel, 44.dp, fontFamily = Title)
            Spacer(Modifier.width(10.dp))
            Column {
                OneLine(channel.name, Ink, 16, Body, weight = FontWeight.Bold)
                OneLine(channel.programmes.first().title, if (focused) Coral else Soft, 16, Body, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StillRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProtoData.continueWatching.take(4).forEach { item ->
            Box(Modifier.width(186.dp).aspectRatio(16f / 9f).clip(Round)) {
                Art(item.seed ?: 11, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Bg.copy(alpha = 0f), 0.95f to Bg)))
                OneLine(item.title, Ink, 16, Body, Modifier.align(Alignment.BottomStart).padding(8.dp), FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FavMixed() = Rail(0) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H2("Continue Watching")
        StillRow()
        H2("Favourites")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelCover(channels[0], initial = true)
            CoverCard(ProtoData.favourites[2])
            ChannelCover(channels[3], initial = false)
            CoverCard(ProtoData.favourites[4])
            CoverCard(ProtoData.favourites[5])
        }
    }
}

@Composable
private fun FavOwnRow() = Rail(0) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        H2("Continue Watching")
        StillRow()
        H2("Favourites")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(2, 4, 5, 1, 0).forEach { CoverCard(ProtoData.favourites[it]) }
        }
        H2("Favourite channels")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0, 3, 4).forEachIndexed { index, channel -> ChannelWide(channels[channel], initial = index == 0) }
        }
    }
}

// ---------- Loose end: Find a category ----------

@Composable
private fun CategoryContent() {
    Column(Modifier.padding(start = 14.dp, top = 27.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        H1("UK Entertainment")
        Spacer(Modifier.height(4.dp))
        channels.take(5).forEach { channel ->
            Row(Modifier.height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoTile(channel, 30.dp, fontFamily = Title)
                Spacer(Modifier.width(10.dp))
                OneLine(channel.name, Ink, 16, Body, weight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                OneLine(channel.programmes.first().title, Soft, 16, Body, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FindSearch() = Rail(1) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(250.dp).fillMaxHeight().background(Panel).padding(start = 8.dp, end = 8.dp, top = 27.dp)) {
            Field("Find a category", "uk e", initial = true)
            Spacer(Modifier.height(8.dp))
            TextRow("UK Entertainment", "1,204")
            TextRow("UK Entertainment HD", "312")
            TextRow("UK Events", "48")
            Spacer(Modifier.height(8.dp))
            OneLine("3 of 214 categories", Soft, 16, Body, Modifier.padding(horizontal = 10.dp))
        }
        CategoryContent()
    }
}

@Composable
private fun FindLetters() = Rail(1) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(44.dp).fillMaxHeight().background(Raised).padding(top = 27.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf("A", "C", "D", "E", "F", "K", "M", "N", "P", "S", "T", "U", "V").forEach { letter ->
                Tile(initial = letter == "U") { focused ->
                    Box(Modifier.size(34.dp).clip(Square).background(if (focused) Coral else Raised), contentAlignment = Alignment.Center) {
                        OneLine(letter, if (focused) OnCoral else Ink, 16, Body, weight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(Modifier.width(214.dp).fillMaxHeight().background(Panel).padding(start = 8.dp, end = 8.dp, top = 27.dp)) {
            listOf("UK Comedy" to "312", "UK Documentary" to "96", "UK Entertainment" to "1,204", "UK Kids" to "96", "UK Movies" to "410", "UK News" to "86", "UK Sport" to "412", "US Comedy" to "118").forEach { (name, count) ->
                TextRow(name, count, current = name == "UK Entertainment")
            }
        }
        CategoryContent()
    }
}

@Composable
private fun FindNone() = Rail(1) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(250.dp).fillMaxHeight().background(Panel).padding(start = 8.dp, end = 8.dp, top = 27.dp)) {
            listOf("Favourites" to "24", "All channels" to "48,212", "Albania" to "86", "Arabic" to "1,102", "Argentina" to "94", "Armenia" to "22", "Australia" to "118", "Austria" to "64", "Azerbaijan" to "18", "Bangladesh" to "40", "Belgium" to "72").forEachIndexed { index, (name, count) ->
                TextRow(name, count, initial = index == 1, current = index == 1)
            }
            Spacer(Modifier.height(6.dp))
            OneLine("11 of 214 categories", Soft, 16, Body, Modifier.padding(horizontal = 10.dp))
        }
        CategoryContent()
    }
}

// ---------- Round 2: the owner's changes ----------

private enum class Focus2 { SEEK, ICONS, CLOSE }

@Composable
private fun IconButton(icon: ImageVector, initial: Boolean = false, size: Int = 40) {
    Tile(initial = initial) { focused ->
        Box(
            Modifier.size(size.dp).clip(Pill).background(if (focused) Coral else Raised),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (focused) OnCoral else Ink, modifier = Modifier.size((size / 2).dp)) }
    }
}

/**
 * Left and Right can't both move through the video and move between buttons, so the seek bar holds
 * them: OK there plays and pauses, Down reaches the rest and Up the close button.
 */
@Composable
private fun Player2(live: Boolean, focus: Focus2) = Screen {
    Video(if (live) 23 else 13)
    FootScrim(Modifier.align(Alignment.BottomCenter), height = if (focus == Focus2.ICONS) 240 else 170)
    Box(Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp)) {
        IconButton(Icons.Filled.Close, initial = focus == Focus2.CLOSE)
    }
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (live) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoTile(channels[1], 36.dp, fontFamily = Title)
                Spacer(Modifier.width(10.dp))
                OneLine("News at Eight", Ink, 28, Title)
            }
        } else {
            OneLine("Harbour Lights", Ink, 28, Title)
            OneLine("Season 2, episode 5  ·  Slack Water", Soft, 18, Body, weight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Pause, null, tint = Ink, modifier = Modifier.size(26.dp))
            }
            OneLine(if (live) "20:00" else "31:42", Ink, 18, Body, weight = FontWeight.Medium)
            Tile(Modifier.weight(1f), initial = focus == Focus2.SEEK) { focused ->
                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(if (focused) 8.dp else 4.dp).clip(Pill).background(Rule)) {
                        Box(Modifier.fillMaxWidth(if (live) 0.3f else 0.62f).fillMaxHeight().background(Coral))
                    }
                    if (focused) {
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.fillMaxWidth(if (live) 0.3f else 0.62f))
                            Box(Modifier.size(20.dp).clip(Pill).background(Ink))
                        }
                    }
                }
            }
            OneLine(if (live) "20:30" else "18 min left", Soft, 18, Body, weight = FontWeight.Medium)
            IconButton(Icons.Filled.ClosedCaption, initial = focus == Focus2.ICONS)
            IconButton(Icons.Filled.Audiotrack)
        }
        if (focus == Focus2.ICONS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live) {
                    Button("Record", Icons.Filled.FiberManualRecord)
                    Button("Quality", Icons.Filled.Tune)
                    Button("Aspect ratio", Icons.Filled.AspectRatio)
                    Button("Sleep timer", Icons.Filled.Bedtime)
                } else {
                    Button("Mark as watched", Icons.Filled.Check)
                    Button("Sleep timer", Icons.Filled.Bedtime)
                    Button("Options", Icons.Filled.MoreVert)
                }
            }
        }
    }
}

@Composable
private fun OnePage2() = Screen {
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            OneLine(WELCOME_LINE, Ink, 32, Title)
            OneLine(SETTLE_LINE, Coral, 28, Title)
            Spacer(Modifier.height(4.dp))
            Para(PLAYS_LINE, Ink)
            Para(PRIVACY_LINE)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Item(initial = true, height = 84, shape = Round, rest = Panel) { focused ->
                Icon(Icons.Filled.PhoneAndroid, null, tint = if (focused) OnCoral else Coral, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    OneLine("Set up from your phone", if (focused) OnCoral else Ink, 18, Body, weight = FontWeight.Bold)
                    OneLine("Scan a code and type your details there", if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
                }
                Icon(Icons.Filled.ChevronRight, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            OneLine("Or type it on the TV", Ink, 18, Title)
            listOf(
                "Xtream Codes login" to "A server address, username and password",
                "M3U playlist" to "A single link from your provider",
                "Stalker portal" to "A portal address and a MAC address",
            ).forEach { (name, hint) ->
                Item(height = 60, shape = Round, rest = Panel) { focused ->
                    Column(Modifier.weight(1f)) {
                        OneLine(name, if (focused) OnCoral else Ink, 16, Body, weight = FontWeight.Bold)
                        OneLine(hint, if (focused) OnCoral else Soft, 16, Body, weight = FontWeight.Medium)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = if (focused) OnCoral else Soft, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/** A stand-in pattern, drawn so the layout can be judged. It doesn't scan. */
@Composable
private fun FakeQr(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color.White).padding(14.dp)) {
        val cells = 29
        val cell = size.width / cells
        val random = kotlin.random.Random(151)
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                val corner = (x < 8 && y < 8) || (x > cells - 9 && y < 8) || (x < 8 && y > cells - 9)
                if (!corner && random.nextBoolean()) {
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
        }
        listOf(0 to 0, cells - 7 to 0, 0 to cells - 7).forEach { (x, y) ->
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(7 * cell, 7 * cell))
            drawRect(Color.White, Offset((x + 1) * cell, (y + 1) * cell), Size(5 * cell, 5 * cell))
            drawRect(Color.Black, Offset((x + 2) * cell, (y + 2) * cell), Size(3 * cell, 3 * cell))
        }
    }
}

@Composable
private fun QrSetup() = Screen {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        FakeQr(Modifier.size(300.dp).clip(Round))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneLine("Set up from your phone", Ink, 32, Title)
            Para("Point your phone's camera at the code. It opens a page on this TV, and what you type stays on your home network.", Ink)
            Spacer(Modifier.height(4.dp))
            OneLine("Then enter this code on your phone", Soft, 16, Body, weight = FontWeight.Medium)
            OneLine("4  8  2  9  1  6", Coral, 54, Title)
            Spacer(Modifier.height(2.dp))
            OneLine("Camera won't scan it? Open this in your phone's browser:", Soft, 16, Body, weight = FontWeight.Medium)
            OneLine("192.168.1.20:8423", Ink, 18, Body, weight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Button("Type it on the TV instead", initial = true)
        }
    }
}

@Composable
private fun RailOption(start: Int, end: Int, panel: Boolean, labels: Boolean = false, showMargin: Boolean = false) = Screen {
    val entries = listOf(
        Icons.Filled.Search to "Search",
        Icons.Filled.Home to "Home",
        Icons.Filled.LiveTv to "Live TV",
        Icons.Filled.Movie to "Movies",
        Icons.Filled.Tv to "Shows",
        Icons.Filled.FiberManualRecord to "Recordings",
    )
    val foot = listOf(Icons.Filled.Person to "Sam", Icons.Filled.Settings to "Settings")
    val background = if (panel) Panel else Bg
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.then(if (labels) Modifier.width(250.dp) else Modifier).fillMaxHeight().background(background)
                .padding(start = start.dp, end = end.dp, top = 27.dp, bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            @Composable
            fun Entry(index: Int, icon: ImageVector, label: String) {
                val here = index == 1
                Tile(if (labels) Modifier.fillMaxWidth() else Modifier, initial = labels && here) { focused ->
                    Row(
                        Modifier.then(if (labels) Modifier.fillMaxWidth() else Modifier).height(48.dp).clip(Round).background(if (focused) Coral else background).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (focused) OnCoral else if (here) Coral else Soft, modifier = Modifier.size(24.dp))
                        if (labels) {
                            Spacer(Modifier.width(12.dp))
                            OneLine(label, if (focused) OnCoral else if (here) Coral else Ink, 18, Body, weight = if (focused || here) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
            }
            entries.forEachIndexed { index, (icon, label) -> Entry(index, icon, label) }
            Spacer(Modifier.weight(1f))
            foot.forEachIndexed { index, (icon, label) -> Entry(index + 10, icon, label) }
        }
        Column(Modifier.weight(1f).padding(start = 20.dp, end = 48.dp, top = 27.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            H2("Continue Watching")
            StillRow()
            H2("Favourites")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(2, 4, 5, 1, 0, 3).take(if (labels) 5 else 6).forEach { CoverCard(ProtoData.favourites[it]) }
            }
        }
    }
    if (showMargin) {
        Box(Modifier.fillMaxHeight().padding(start = 48.dp).width(1.dp).background(Coral))
    }
}
