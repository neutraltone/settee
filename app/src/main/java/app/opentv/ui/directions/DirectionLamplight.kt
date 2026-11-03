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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * A: Lamplight. The quietest: barely warm near-black, a legible plain sans, and a white highlight
 * that inverts whatever it lands on. Warmth is a hint, so it's the safest on a poor panel.
 */
private val Bg = Color(0xFF12100E)
private val Panel = Color(0xFF1E1B18)
private val Line = Color(0xFF38332E)
private val Ink = Color(0xFFF3EFE8)
private val Soft = Color(0xFFB4ACA0)
private val Lamp = Color(0xFFE9C89A)
private val FocusFill = Color(0xFFFFFFFF)
private val OnFocus = Color(0xFF12100E)
private val Face = Atkinson
private val Corner = RoundedCornerShape(6.dp)

@Composable
private fun Rail(current: Int) = PlainRail(current, Panel, Soft, Ink, Lamp, FocusFill, OnFocus)

@Composable
fun LamplightScreen(screen: Screen) {
    when (screen) {
        Screen.HOME_PREVIEW -> Home(preview = true)
        Screen.HOME -> Home(preview = false)
        Screen.GUIDE -> Guide()
        Screen.SHOWS -> Shows()
        Screen.DETAILS -> Details()
        else -> Unit
    }
}

@Composable
private fun SectionLabel(text: String) {
    OneLine(text, Soft, 15, Face, Modifier.padding(bottom = 6.dp), FontWeight.Bold)
}

@Composable
private fun Home(preview: Boolean) = WithRail(Bg, { Rail(0) }) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (preview) PreviewStrip()
        CardRow("Continue Watching", ProtoData.continueWatching, !preview)
        CardRow("Favourites", ProtoData.favourites, false)
        CardRow("New", ProtoData.newTitles, false)
    }
}

@Composable
private fun PreviewStrip() {
    val channel = ProtoData.channels.first()
    Tile(Modifier.padding(end = 48.dp).fillMaxWidth(), initial = true) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Corner)
                .background(if (focused) FocusFill else Panel)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(ProtoData.LIVE_SEED, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            LogoTile(channel, 36.dp, fontFamily = Face)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                OneLine(
                    "${channel.name}  ·  ${ProtoData.liveProgramme}",
                    if (focused) OnFocus else Ink,
                    17,
                    Face,
                    weight = FontWeight.Bold,
                )
                OneLine(ProtoData.liveTimes, if (focused) OnFocus else Soft, 14, Face)
                Spacer(Modifier.height(6.dp))
                ProgressLine(
                    ProtoData.LIVE_PROGRESS,
                    if (focused) Color(0xFFCFCBC4) else Line,
                    if (focused) OnFocus else Lamp,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun CardRow(title: String, items: List<Item>, initial: Boolean) {
    Column {
        SectionLabel(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEachIndexed { index, item ->
                Tile(Modifier.width(176.dp), initial = initial && index == 0) { focused -> Card(item, focused) }
            }
        }
    }
}

@Composable
private fun Card(item: Item, focused: Boolean) {
    Column(
        Modifier
            .clip(Corner)
            .background(if (focused) FocusFill else Bg),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(if (focused) 3.dp else 0.dp)) {
            if (item.seed != null) {
                Art(item.seed, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
            } else {
                NoArt(item.title)
            }
            val watch = item.watch
            if (watch is Watch.InProgress) {
                ProgressLine(watch.fraction, Color(0xFF3A3530), Lamp, Modifier.align(Alignment.BottomCenter))
            }
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            OneLine(item.title, if (focused) OnFocus else Ink, 15, Face, weight = FontWeight.Bold)
            OneLine(statusText(item), if (focused) OnFocus else Soft, 14, Face)
        }
    }
}

private fun statusText(item: Item): String = when (val watch = item.watch) {
    Watch.Unwatched -> item.sub
    Watch.Watched -> "✓ Watched"
    is Watch.EpisodesLeft -> watch.label()
    else -> "${item.sub}  ·  ${watch.label()}"
}

@Composable
private fun NoArt(title: String) {
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(4.dp))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        OneLine(title, Soft, 15, Face)
    }
}

@Composable
private fun Guide() = WithRail(Bg, { Rail(1) }) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(ProtoData.LIVE_SEED, Modifier.width(180.dp).aspectRatio(16f / 9f).clip(Corner))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                OneLine(ProtoData.liveProgramme, Ink, 22, Face, weight = FontWeight.Bold)
                OneLine("${ProtoData.liveChannel}  ·  ${ProtoData.liveTimes}", Soft, 15, Face)
                Spacer(Modifier.height(8.dp))
                Text(
                    ProtoData.liveBlurb,
                    color = Soft,
                    fontFamily = Face,
                    fontSize = 15.sp,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            OneLine("All channels", Lamp, 14, Face, Modifier.width(ChannelColumn), FontWeight.Bold)
            Row(Modifier.weight(1f)) {
                ProtoData.times.forEach { OneLine(it, Soft, 14, Face, Modifier.weight(1f)) }
            }
        }
        ProtoData.channels.forEachIndexed { row, channel ->
            GuideRow(channel, row == 0)
            Spacer(Modifier.height(2.dp))
        }
    }
}

private val ChannelColumn = 172.dp
private const val GUIDE_MINUTES = 90f

@Composable
private fun GuideRow(channel: Channel, initial: Boolean) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn), verticalAlignment = Alignment.CenterVertically) {
            OneLine(channel.number.toString(), Soft, 14, Face, Modifier.width(22.dp))
            LogoTile(channel, 26.dp, fontFamily = Face)
            Spacer(Modifier.width(8.dp))
            OneLine(channel.name, Ink, 14, Face, Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxHeight()) {
            channel.programmes.forEachIndexed { index, programme ->
                Tile(
                    Modifier.weight(programme.minutes / GUIDE_MINUTES).fillMaxHeight().padding(end = 2.dp),
                    initial = initial && index == 0,
                ) { focused ->
                    val isNow = index == 0
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    focused -> FocusFill
                                    isNow -> Color(0xFF2A2622)
                                    else -> Panel
                                },
                            )
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        OneLine(
                            programme.title,
                            if (focused) OnFocus else if (isNow) Ink else Soft,
                            14,
                            Face,
                            weight = if (focused) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shows() = WithRail(Bg, { Rail(3) }) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PosterRow("Drama", ProtoData.drama, true)
        PosterRow("Comedy", ProtoData.comedy, false)
    }
}

@Composable
private fun PosterRow(title: String, items: List<Item>, initial: Boolean) {
    val nav = LocalProtoNav.current
    Column {
        SectionLabel(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { index, item ->
                Tile(
                    Modifier.width(138.dp),
                    initial = initial && index == 0,
                    onClick = { nav(Screen.DETAILS) },
                ) { focused ->
                    Column(
                        Modifier
                            .clip(Corner)
                            .background(if (focused) FocusFill else Bg),
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).padding(if (focused) 3.dp else 0.dp)) {
                            if (item.seed != null) {
                                Art(item.seed, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)), item.title)
                            } else {
                                NoArt(item.title)
                            }
                        }
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                            OneLine(item.title, if (focused) OnFocus else Ink, 15, Face, weight = FontWeight.Bold)
                            OneLine(statusText(item), if (focused) OnFocus else Soft, 14, Face)
                        }
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
        // One scrim, painted as solid-to-clear gradients rather than a blurred or alpha-dimmed layer.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg, 0.45f to Bg, 0.85f to Bg.copy(alpha = 0f)),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Bg.copy(alpha = 0f), 0.85f to Bg),
            ),
        )
        Column(Modifier.fillMaxHeight().padding(start = 48.dp, top = 48.dp, end = 48.dp, bottom = 27.dp)) {
            OneLine(ProtoData.detailTitle, Ink, 42, Face, weight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OneLine(ProtoData.detailMeta, Soft, 15, Face)
            Spacer(Modifier.height(10.dp))
            Text(
                ProtoData.detailBlurb,
                color = Ink,
                fontFamily = Face,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = 3,
                modifier = Modifier.width(440.dp),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Action("▶  Resume S2 E5", primary = true)
                Action("Episodes", primary = false)
                Action("☆  Favourite", primary = false)
            }
            Spacer(Modifier.weight(1f))
            SectionLabel("Series 2")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoData.episodes.forEach { episode ->
                    Tile(Modifier.width(160.dp)) { focused -> Card(episode, focused) }
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
                .background(if (focused) FocusFill else Panel)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            OneLine(text, if (focused) OnFocus else Ink, 16, Face, weight = FontWeight.Bold)
        }
    }
}
