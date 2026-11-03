/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * C: Listings. Type-led, like a printed TV listings page: oxblood-black, a warm slab serif, ruled
 * lines, and a cream highlight that reads like a marker across a whole line. Home and Shows are
 * lists beside one large image of whatever is highlighted, so rows go down rather than along. The
 * cheapest on artwork count, but the leading image changes on each press, so its cost waits on #84.
 */
private val Bg = Color(0xFF1B100F)
private val Panel = Color(0xFF281917)
private val Rule = Color(0xFF45302C)
private val Ink = Color(0xFFF3E7D3)
private val Soft = Color(0xFFBFA997)
private val Terracotta = Color(0xFFE5866A)
private val Paper = Color(0xFFF3E7D3)
private val OnPaper = Color(0xFF1B100F)
private val Slab = Zilla
private val Body = Alegreya

@Composable
private fun Rail(current: Int) = PlainRail(current, Panel, Soft, Ink, Terracotta, Paper, OnPaper)

@Composable
fun ListingsScreen(screen: Screen) {
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
private fun Heading(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)) {
        OneLine(text.uppercase(), Terracotta, 14, Body, weight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
    }
}

private fun statusText(item: Item): String = when (val watch = item.watch) {
    Watch.Unwatched -> "New"
    Watch.Watched -> "✓ Watched"
    else -> watch.label()
}

// Columns rather than one long list: Favourites stays one press from Continue Watching (Right, where
// rows would use Down), so this adds nothing to #87's Daily routes. A single list would not pass.
@Composable
private fun Home(preview: Boolean) = WithRail(Bg, { Rail(0) }) {
    var lead by remember { mutableStateOf(if (preview) null else ProtoData.continueWatching.first()) }
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp)) {
        LeadBand(lead)
        Spacer(Modifier.height(6.dp))
        if (preview) {
            LiveLine(onFocused = { lead = null })
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(
                "Continue Watching" to ProtoData.continueWatching,
                "Favourites" to ProtoData.favourites,
                "New" to ProtoData.newTitles,
            ).forEachIndexed { column, (title, items) ->
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    Heading(title)
                    items.forEachIndexed { index, item ->
                        Line(item, initial = !preview && column == 0 && index == 0, onFocused = { lead = item })
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveLine(onFocused: () -> Unit) {
    val channel = ProtoData.channels.first()
    Tile(Modifier.fillMaxWidth(), initial = true, onFocused = onFocused) { focused ->
        LineBody(focused) {
            LogoTile(channel, 34.dp, fontFamily = Slab)
            Spacer(Modifier.width(10.dp))
            OneLine("Back to ${channel.name}", if (focused) OnPaper else Ink, 18, Slab, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(14.dp))
            OneLine(ProtoData.liveProgramme, if (focused) OnPaper else Soft, 15, Body, Modifier.weight(1f))
            OneLine(ProtoData.liveTimes, if (focused) OnPaper else Soft, 14, Body)
        }
    }
}

@Composable
private fun LineBody(focused: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (focused) Paper else Bg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Line(item: Item, initial: Boolean = false, onFocused: () -> Unit) {
    Tile(Modifier.fillMaxWidth(), initial = initial, onFocused = onFocused) { focused ->
        LineBody(focused) {
            Column(Modifier.weight(1f)) {
                OneLine(item.title, if (focused) OnPaper else Ink, 18, Slab, weight = FontWeight.SemiBold)
                OneLine(item.sub, if (focused) OnPaper else Soft, 14, Body)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
                OneLine(
                    statusText(item),
                    when {
                        focused -> OnPaper
                        item.watch == Watch.Watched -> Soft
                        else -> Terracotta
                    },
                    14,
                    Body,
                    weight = FontWeight.Medium,
                )
                val watch = item.watch
                if (watch is Watch.InProgress) {
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(watch.fraction, if (focused) Color(0xFFCDBFA9) else Rule, if (focused) OnPaper else Terracotta)
                }
            }
        }
    }
}

/** One image of whatever is highlighted. Null means the Live Preview, which would be playing here. */
@Composable
private fun LeadBand(item: Item?) {
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        val seed = if (item == null) ProtoData.LIVE_SEED else item.seed
        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(2.dp)).background(Panel)) {
            if (seed != null) {
                Art(seed, Modifier.fillMaxSize())
            } else {
                OneLine(item?.title.orEmpty(), Soft, 18, Slab, Modifier.align(Alignment.Center))
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            OneLine(item?.title ?: ProtoData.liveProgramme, Ink, 30, Slab, weight = FontWeight.SemiBold)
            OneLine(
                if (item == null) "${ProtoData.liveChannel}  ·  ${ProtoData.liveTimes}" else "${item.sub}  ·  ${statusText(item)}",
                Terracotta,
                15,
                Body,
                weight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            if (item == null) {
                ProgressLine(ProtoData.LIVE_PROGRESS, Rule, Terracotta)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                if (item == null) ProtoData.liveBlurb else ProtoData.detailBlurb,
                color = Soft,
                fontFamily = Body,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                maxLines = 3,
            )
        }
    }
}

private val ChannelColumn = 190.dp
private const val GUIDE_MINUTES = 90f

@Composable
private fun Guide() = WithRail(Bg, { Rail(1) }) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            OneLine("Tonight", Ink, 30, Slab, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(18.dp))
            ProtoData.categories.forEach { category ->
                val current = category == "All channels"
                OneLine(
                    category,
                    if (current) Terracotta else Soft,
                    15,
                    Body,
                    Modifier.padding(end = 14.dp, bottom = 4.dp),
                    if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            OneLine("Now ${ProtoData.nowLabel}", Terracotta, 14, Body, Modifier.width(ChannelColumn), FontWeight.Bold)
            Row(Modifier.weight(1f)) {
                ProtoData.times.forEach { OneLine(it, Soft, 14, Body, Modifier.weight(1f), FontWeight.Medium) }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink))
        ProtoData.channels.forEachIndexed { row, channel ->
            Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(ChannelColumn), verticalAlignment = Alignment.CenterVertically) {
                    OneLine(channel.number.toString(), Soft, 14, Body, Modifier.width(20.dp))
                    LogoTile(channel, 28.dp, fontFamily = Slab)
                    Spacer(Modifier.width(8.dp))
                    OneLine(channel.name, Ink, 16, Slab, Modifier.weight(1f), FontWeight.SemiBold)
                }
                Row(Modifier.weight(1f).fillMaxHeight()) {
                    channel.programmes.forEachIndexed { index, programme ->
                        Tile(
                            Modifier.weight(programme.minutes / GUIDE_MINUTES).fillMaxHeight(),
                            initial = row == 0 && index == 0,
                        ) { focused ->
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (focused) Paper else Bg)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                OneLine(
                                    programme.title,
                                    when {
                                        focused -> OnPaper
                                        index == 0 -> Ink
                                        else -> Soft
                                    },
                                    16,
                                    Body,
                                    weight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                                )
                            }
                        }
                        if (index != channel.programmes.lastIndex) {
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 10.dp).background(Rule))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
        }
    }
}

@Composable
private fun Shows() = WithRail(Bg, { Rail(3) }) {
    val nav = LocalProtoNav.current
    val all = remember { ProtoData.drama + ProtoData.comedy }
    var lead by remember { mutableStateOf(all.first()) }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(360.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 15.dp, bottom = 27.dp),
        ) {
            Heading("Drama")
            ProtoData.drama.forEachIndexed { index, item ->
                ShowLine(item, index == 0, { lead = item }, { nav(Screen.DETAILS) })
            }
            Heading("Comedy")
            ProtoData.comedy.forEach { item -> ShowLine(item, false, { lead = item }, { nav(Screen.DETAILS) }) }
        }
        Spacer(Modifier.width(24.dp))
        // The louder half of the personality: one large poster and the blurb, rather than a wall of them.
        Row(Modifier.weight(1f).padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
            Box(Modifier.width(200.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(2.dp)).background(Panel)) {
                val seed = lead.seed
                if (seed != null) {
                    Art(seed, Modifier.fillMaxSize(), lead.title)
                } else {
                    OneLine(lead.title, Soft, 18, Slab, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    lead.title,
                    color = Ink,
                    fontFamily = Slab,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                OneLine(lead.sub, Terracotta, 15, Body, weight = FontWeight.Medium)
                OneLine(statusText(lead), Soft, 15, Body)
            }
        }
    }
}

@Composable
private fun ShowLine(item: Item, initial: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    Tile(Modifier.fillMaxWidth(), initial = initial, onFocused = onFocused, onClick = onClick) { focused ->
        LineBody(focused) {
            OneLine(item.title, if (focused) OnPaper else Ink, 18, Slab, Modifier.weight(1f), FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            OneLine(
                statusText(item),
                when {
                    focused -> OnPaper
                    item.watch == Watch.Watched -> Soft
                    else -> Terracotta
                },
                14,
                Body,
                weight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Details() {
    Box(Modifier.fillMaxSize().background(Bg)) {
        // A band of backdrop fading down into the page, so the text below sits on solid colour.
        Art(11, Modifier.fillMaxWidth().height(270.dp))
        Box(
            Modifier.fillMaxWidth().height(272.dp).background(
                Brush.verticalGradient(0.2f to Bg.copy(alpha = 0f), 1f to Bg),
            ),
        )
        Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 130.dp, end = 48.dp, bottom = 27.dp)) {
            OneLine(ProtoData.detailTitle, Ink, 56, Slab, weight = FontWeight.SemiBold)
            OneLine(ProtoData.detailMeta, Terracotta, 16, Body, weight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Ink))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(390.dp)) {
                    Text(
                        ProtoData.detailBlurb,
                        color = Ink,
                        fontFamily = Body,
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Action("▶ Resume S2 E5", true)
                        Action("☆ Favourite", false)
                    }
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    OneLine("SERIES 2", Terracotta, 14, Body, weight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    ProtoData.episodes.forEach { episode ->
                        Tile(Modifier.fillMaxWidth()) { focused ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (focused) Paper else Bg)
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OneLine(episode.sub, if (focused) OnPaper else Soft, 14, Body, Modifier.width(46.dp))
                                OneLine(
                                    episode.title,
                                    if (focused) OnPaper else Ink,
                                    16,
                                    Slab,
                                    Modifier.weight(1f),
                                    FontWeight.Medium,
                                )
                                OneLine(
                                    if (episode.watch == Watch.Unwatched) "" else statusText(episode),
                                    when {
                                        focused -> OnPaper
                                        episode.watch == Watch.Watched -> Soft
                                        else -> Terracotta
                                    },
                                    14,
                                    Body,
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
                    }
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
                .clip(RoundedCornerShape(2.dp))
                .background(if (focused) Paper else Panel)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            OneLine(text, if (focused) OnPaper else Ink, 16, Slab, weight = FontWeight.SemiBold)
        }
    }
}
