/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * D: Picture House. Artwork first: a dusky plum-charcoal, a round modern sans, and a coral ring that
 * also lifts the focused card, drawn through graphicsLayer so it never re-measures. Cards carry their
 * text on the art. Shows has the highlighted title's backdrop filling the top of the screen, which
 * is the costliest thing here and stays provisional until #84 reports.
 */
private val Bg = Color(0xFF1A1416)
private val Panel = Color(0xFF281F22)
private val Raised = Color(0xFF362A2E)
private val Ink = Color(0xFFF8EFEA)
private val Soft = Color(0xFFBDABA8)
private val Coral = Color(0xFFFF8F6B)
private val OnCoral = Color(0xFF2A130B)
private val Face = Poppins
private val Corner = RoundedCornerShape(10.dp)

@Composable
private fun Rail(current: Int) = PlainRail(current, Panel, Soft, Coral, Coral, Coral, OnCoral)

@Composable
fun PictureHouseScreen(screen: Screen) {
    when (screen) {
        Screen.HOME_PREVIEW -> Home(preview = true)
        Screen.HOME -> Home(preview = false)
        Screen.GUIDE -> Guide()
        Screen.SHOWS -> Shows()
        Screen.DETAILS -> Details()
        else -> Unit
    }
}

private fun Modifier.lifted(focused: Boolean, scale: Float = 1.06f): Modifier =
    if (focused) {
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .shadow(14.dp, Corner)
            .border(3.dp, Coral, Corner)
    } else {
        this
    }

@Composable
private fun Label(text: String) {
    OneLine(text, Ink, 16, Face, Modifier.padding(start = 5.dp, bottom = 2.dp), FontWeight.Medium)
}

@Composable
private fun Home(preview: Boolean) = WithRail(Bg, { Rail(0) }) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (preview) LiveBand()
        OverlayRow("Continue Watching", ProtoData.continueWatching, 228.dp, !preview)
        OverlayRow("Favourites", ProtoData.favourites, 180.dp, false)
        OverlayRow("New", ProtoData.newTitles, 180.dp, false)
    }
}

@Composable
private fun LiveBand() {
    val channel = ProtoData.channels.first()
    Tile(Modifier.padding(start = 5.dp, end = 48.dp).fillMaxWidth().height(96.dp), initial = true) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .lifted(focused, 1.02f)
                .clip(Corner),
        ) {
            Box(Modifier.fillMaxSize().background(Panel))
            Art(ProtoData.LIVE_SEED, Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f))
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().aspectRatio(16f / 9f).background(
                    Brush.horizontalGradient(0f to Panel, 0.4f to Panel.copy(alpha = 0f)),
                ),
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoTile(channel, 52.dp, fontFamily = Face)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.width(380.dp)) {
                    OneLine(ProtoData.liveProgramme, Ink, 20, Face, weight = FontWeight.SemiBold)
                    OneLine("${channel.name}  ·  ${ProtoData.liveTimes}", Soft, 14, Face)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(ProtoData.LIVE_PROGRESS, Raised, Coral)
                }
            }
            if (focused) Box(Modifier.fillMaxSize().border(3.dp, Coral, Corner))
        }
    }
}

@Composable
private fun OverlayRow(title: String, items: List<Item>, width: androidx.compose.ui.unit.Dp, initial: Boolean) {
    Column {
        Label(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp, top = 5.dp, bottom = 5.dp, start = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { index, item ->
                Tile(Modifier.width(width), initial = initial && index == 0) { focused ->
                    OverlayCard(item, focused, 16f / 9f)
                }
            }
        }
    }
}

/** The text sits on the art behind a solid-to-clear gradient, painted on the card, not a dimmed layer. */
@Composable
private fun OverlayCard(item: Item, focused: Boolean, ratio: Float, showTitle: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .lifted(focused)
            .clip(Corner)
            .background(Raised),
    ) {
        if (item.seed != null) Art(item.seed, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Bg.copy(alpha = 0f), 0.95f to Bg),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 9.dp, vertical = 8.dp)) {
            if (showTitle) OneLine(item.title, Ink, 15, Face, weight = FontWeight.SemiBold)
            OneLine(statusText(item), if (focused) Coral else Soft, 14, Face, weight = FontWeight.Medium)
        }
        val watch = item.watch
        if (watch is Watch.InProgress) {
            ProgressLine(watch.fraction, Raised, Coral, Modifier.align(Alignment.BottomCenter), 4.dp)
        }
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, Coral, Corner))
    }
}

private fun statusText(item: Item): String = when (val watch = item.watch) {
    Watch.Unwatched -> item.sub
    Watch.Watched -> "✓ Watched"
    is Watch.EpisodesLeft -> watch.label()
    else -> "${item.sub}  ·  ${watch.label()}"
}

@Composable
private fun Guide() = WithRail(Bg, { Rail(1) }) {
    // Not a grid: channels lead with their logo, now-playing text and a progress bar, as #88 asks of
    // live cards, and the next programme is one line. The timeline grid would be a press away.
    Row(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
        Column(
            Modifier
                .width(410.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProtoData.channels.forEachIndexed { row, channel ->
                Tile(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    initial = row == 0,
                ) { focused ->
                    ChannelCard(channel, focused)
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Art(ProtoData.LIVE_SEED, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Corner))
            Spacer(Modifier.height(12.dp))
            OneLine(ProtoData.liveProgramme, Ink, 22, Face, weight = FontWeight.SemiBold)
            OneLine("${ProtoData.liveChannel}  ·  ${ProtoData.liveTimes}", Coral, 14, Face, weight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Text(
                ProtoData.liveBlurb,
                color = Soft,
                fontFamily = Face,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            OneLine("Next: Fireside Tales, 21:00", Ink, 14, Face, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, focused: Boolean) {
    val now = channel.programmes.first()
    val next = channel.programmes.getOrNull(1)?.title ?: "Until 21:30"
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .lifted(focused, 1.02f)
            .clip(Corner)
            .background(if (focused) Raised else Panel)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoTile(channel, 36.dp, fontFamily = Face)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            OneLine(now.title, Ink, 15, Face, weight = FontWeight.SemiBold)
            OneLine("${channel.name}  ·  $next", Soft, 14, Face)
            Spacer(Modifier.height(2.dp))
            ProgressLine(ProtoData.NOW_MINUTES.toFloat() / now.minutes, Bg, if (focused) Coral else Soft, height = 2.dp)
        }
    }
}

@Composable
private fun Shows() {
    val nav = LocalProtoNav.current
    var lead by remember { mutableStateOf(ProtoData.drama.first()) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Full-bleed, behind the rail too. Swapping this on each press is why it's provisional.
        val seed = lead.seed
        if (seed != null) Art(seed, Modifier.fillMaxWidth().height(350.dp))
        Box(
            Modifier.fillMaxWidth().height(352.dp).background(
                Brush.verticalGradient(0.35f to Bg.copy(alpha = 0f), 1f to Bg),
            ),
        )
        Box(
            Modifier.fillMaxWidth().height(352.dp).background(
                Brush.horizontalGradient(0f to Bg, 0.55f to Bg.copy(alpha = 0f)),
            ),
        )
        WithRail(Color.Transparent, { Rail(3) }) {
            Column(Modifier.fillMaxSize().padding(top = 44.dp, bottom = 27.dp)) {
                OneLine(lead.title, Ink, 38, Face, weight = FontWeight.SemiBold)
                OneLine("${lead.sub}  ·  ${statusText(lead)}", Coral, 15, Face, weight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    ProtoData.detailBlurb,
                    color = Ink,
                    fontFamily = Face,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    modifier = Modifier.width(440.dp),
                )
                Spacer(Modifier.weight(1f))
                Label("Drama")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 48.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ProtoData.drama.forEachIndexed { index, item ->
                        Tile(
                            Modifier.width(138.dp),
                            initial = index == 0,
                            onFocused = { lead = item },
                            onClick = { nav(Screen.DETAILS) },
                        ) { focused -> OverlayCard(item, focused, 2f / 3f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Details() {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Art(11, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.25f to Bg.copy(alpha = 0f), 0.72f to Bg),
            ),
        )
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 110.dp, bottom = 27.dp)) {
            OneLine(ProtoData.detailTitle, Ink, 54, Face, weight = FontWeight.SemiBold)
            OneLine(ProtoData.detailMeta, Coral, 15, Face, weight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                ProtoData.detailBlurb,
                color = Ink,
                fontFamily = Face,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = 3,
                modifier = Modifier.width(620.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("▶  Resume S2 E5", true)
                Action("Episodes", false)
                Action("☆", false)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.padding(horizontal = 5.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProtoData.episodes.forEach { episode ->
                    Tile(Modifier.width(158.dp)) { focused -> OverlayCard(episode, focused, 16f / 9f) }
                }
            }
        }
    }
}

@Composable
private fun Action(text: String, primary: Boolean) {
    Tile(initial = primary) { focused ->
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (focused) Coral else Raised)
                .padding(horizontal = 20.dp, vertical = 9.dp),
        ) {
            OneLine(text, if (focused) OnCoral else Ink, 15, Face, weight = FontWeight.SemiBold)
        }
    }
}
