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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * B: Hearth. Openly amber: brown-black surfaces, a characterful serif for titles, and an amber
 * highlight with the one permitted glow. Home leads with one larger card, for the single press to
 * pick up. Shows gives the artwork the most room. The details tint is provisional until #84 reports.
 */
private val Bg = Color(0xFF1C120A)
private val Panel = Color(0xFF2C1D10)
private val Raised = Color(0xFF3A2716)
private val Ink = Color(0xFFFBEFD9)
private val Soft = Color(0xFFCDB592)
private val Amber = Color(0xFFF4A83D)
private val OnAmber = Color(0xFF241505)
private val Title = YoungSerif
private val Body = Alegreya
private val Corner = RoundedCornerShape(14.dp)

@Composable
private fun Rail(current: Int) = PlainRail(current, Panel, Soft, Amber, Amber, Amber, OnAmber)

@Composable
fun HearthScreen(screen: Screen) {
    when (screen) {
        Screen.HOME_PREVIEW -> Home(preview = true)
        Screen.HOME -> Home(preview = false)
        Screen.GUIDE -> Guide()
        Screen.SHOWS -> Shows()
        Screen.DETAILS -> Details()
        else -> Unit
    }
}

/** The highlight: amber border and the one glow on the screen, on the focused item alone. */
private fun Modifier.hearthFocus(focused: Boolean, shape: RoundedCornerShape = Corner): Modifier =
    if (focused) {
        shadow(16.dp, shape, ambientColor = Amber, spotColor = Amber).border(4.dp, Amber, shape)
    } else {
        this
    }

@Composable
private fun SectionTitle(text: String) {
    OneLine(text, Ink, 20, Title, Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Status(item: Item, focused: Boolean) {
    val colour = if (focused) Amber else Soft
    when (val watch = item.watch) {
        Watch.Unwatched -> OneLine(item.sub, colour, 15, Body)
        Watch.Watched -> OneLine("✓ Watched", colour, 15, Body, weight = FontWeight.Medium)
        is Watch.EpisodesLeft -> OneLine(watch.label(), colour, 15, Body, weight = FontWeight.Medium)
        else -> OneLine("${item.sub}  ·  ${watch.label()}", colour, 15, Body, weight = FontWeight.Medium)
    }
}

@Composable
private fun Home(preview: Boolean) = WithRail(Bg, { Rail(0) }) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (preview) LivePanel()
        Column {
            SectionTitle("Continue Watching")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ProtoData.continueWatching.forEachIndexed { index, item ->
                    Tile(
                        Modifier.width(if (index == 0 && !preview) 300.dp else 186.dp),
                        initial = index == 0 && !preview,
                    ) { focused -> LandscapeCard(item, focused) }
                }
            }
        }
        LandscapeRow("Favourites", ProtoData.favourites)
        LandscapeRow("New", ProtoData.newTitles)
    }
}

@Composable
private fun LivePanel() {
    val channel = ProtoData.channels.first()
    Tile(Modifier.padding(end = 48.dp, top = 4.dp).fillMaxWidth(), initial = true) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .hearthFocus(focused)
                .clip(Corner)
                .background(if (focused) Raised else Panel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(ProtoData.LIVE_SEED, Modifier.width(200.dp).aspectRatio(16f / 9f))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).padding(end = 18.dp)) {
                OneLine("Back to ${channel.name}", Amber, 15, Body, weight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                OneLine(ProtoData.liveProgramme, Ink, 24, Title)
                Spacer(Modifier.height(4.dp))
                OneLine(ProtoData.liveTimes, Soft, 15, Body)
                Spacer(Modifier.height(10.dp))
                ProgressLine(ProtoData.LIVE_PROGRESS, Color(0xFF4A3421), Amber, height = 4.dp)
            }
        }
    }
}

@Composable
private fun LandscapeRow(title: String, items: List<Item>) {
    Column {
        SectionTitle(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item ->
                Tile(Modifier.width(186.dp)) { focused -> LandscapeCard(item, focused) }
            }
        }
    }
}

@Composable
private fun LandscapeCard(item: Item, focused: Boolean) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .hearthFocus(focused)
                .clip(Corner),
        ) {
            if (item.seed != null) Art(item.seed, Modifier.fillMaxSize()) else NoArt(item.title)
            val watch = item.watch
            if (watch is Watch.InProgress) {
                ProgressLine(watch.fraction, Color(0xFF4A3421), Amber, Modifier.align(Alignment.BottomCenter), 5.dp)
            }
            if (watch == Watch.Watched) TickBadge(Modifier.align(Alignment.TopEnd))
            if (focused) Box(Modifier.fillMaxSize().border(4.dp, Amber, Corner))
        }
        Spacer(Modifier.height(5.dp))
        OneLine(item.title, if (focused) Amber else Ink, 16, Body, Modifier.padding(horizontal = 4.dp), FontWeight.Bold)
        Box(Modifier.padding(horizontal = 4.dp)) { Status(item, focused) }
    }
}

@Composable
private fun TickBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        OneLine("✓", Amber, 14, Body, weight = FontWeight.Bold)
    }
}

@Composable
private fun NoArt(title: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Raised, Panel)))
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Ink, fontFamily = Title, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 3)
    }
}

private const val GUIDE_MINUTES = 90f

@Composable
private fun Guide() = WithRail(Bg, { Rail(1) }) {
    Column(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OneLine("Live TV", Ink, 26, Title)
            Spacer(Modifier.width(18.dp))
            ProtoData.categories.take(5).forEach { category ->
                val current = category == "All channels"
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (current) Raised else Bg)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    OneLine(category, if (current) Amber else Soft, 15, Body, weight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            OneLine("Now ${ProtoData.nowLabel}", Amber, 15, Body, Modifier.width(ChannelColumn), FontWeight.Bold)
            Row(Modifier.weight(1f)) {
                ProtoData.times.forEach { OneLine(it, Soft, 15, Body, Modifier.weight(1f)) }
            }
        }
        ProtoData.channels.forEachIndexed { row, channel ->
            HearthGuideRow(channel, row == 0)
            Spacer(Modifier.height(5.dp))
        }
    }
}

private val ChannelColumn: Dp = 176.dp

@Composable
private fun HearthGuideRow(channel: Channel, initial: Boolean) {
    Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(ChannelColumn), verticalAlignment = Alignment.CenterVertically) {
            LogoTile(channel, 34.dp, fontFamily = Title)
            Spacer(Modifier.width(8.dp))
            OneLine(channel.name, Ink, 16, Body, Modifier.weight(1f), FontWeight.Medium)
        }
        Row(Modifier.weight(1f).fillMaxHeight()) {
            channel.programmes.forEachIndexed { index, programme ->
                Tile(
                    Modifier.weight(programme.minutes / GUIDE_MINUTES).fillMaxHeight().padding(end = 4.dp),
                    initial = initial && index == 0,
                ) { focused ->
                    val isNow = index == 0
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = Amber, spotColor = Amber) else Modifier)
                            .clip(shape)
                            .background(
                                when {
                                    focused -> Amber
                                    isNow -> Raised
                                    else -> Panel
                                },
                            )
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        OneLine(
                            programme.title,
                            when {
                                focused -> OnAmber
                                isNow -> Ink
                                else -> Soft
                            },
                            16,
                            Body,
                            weight = if (focused || isNow) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shows() = WithRail(Bg, { Rail(3) }) {
    val nav = LocalProtoNav.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 27.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        listOf("Drama" to ProtoData.drama, "Comedy" to ProtoData.comedy).forEachIndexed { rowIndex, (title, items) ->
            Column {
                SectionTitle(title)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(end = 48.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        Tile(
                            Modifier.width(190.dp),
                            initial = rowIndex == 0 && index == 0,
                            onClick = { nav(Screen.DETAILS) },
                        ) { focused -> BigPoster(item, focused) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigPoster(item: Item, focused: Boolean) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .hearthFocus(focused)
                .clip(Corner),
        ) {
            if (item.seed != null) Art(item.seed, Modifier.fillMaxSize(), item.title) else NoArt(item.title)
            if (item.watch == Watch.Watched) TickBadge(Modifier.align(Alignment.TopEnd))
            if (focused) Box(Modifier.fillMaxSize().border(4.dp, Amber, Corner))
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.padding(horizontal = 4.dp)) { Status(item, focused) }
    }
}

@Composable
private fun Details() {
    Box(Modifier.fillMaxSize().background(Bg)) {
        Art(11, Modifier.fillMaxSize())
        // The static tint #88 allows: painted once when the page opens, never re-tinted on focus.
        Box(Modifier.fillMaxSize().background(Color(0xFF6B3A10).copy(alpha = 0.45f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg, 0.5f to Bg.copy(alpha = 0.92f), 0.9f to Bg.copy(alpha = 0f)),
            ),
        )
        Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp, bottom = 27.dp)) {
            Art(11, Modifier.width(200.dp).aspectRatio(2f / 3f).clip(Corner), ProtoData.detailTitle)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                OneLine(ProtoData.detailTitle, Ink, 44, Title)
                Spacer(Modifier.height(4.dp))
                OneLine(ProtoData.detailMeta, Amber, 16, Body, weight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(
                    ProtoData.detailBlurb,
                    color = Ink,
                    fontFamily = Body,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    maxLines = 3,
                    modifier = Modifier.width(470.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Action("▶  Resume S2 E5", primary = true)
                    Action("Episodes", primary = false)
                    Action("☆  Favourite", primary = false)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 27.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProtoData.episodes.forEach { episode ->
                    Tile(Modifier.width(158.dp)) { focused -> LandscapeCard(episode, focused) }
                }
            }
        }
    }
}

@Composable
private fun Action(text: String, primary: Boolean) {
    Tile(initial = primary) { focused ->
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = Amber, spotColor = Amber) else Modifier)
                .clip(shape)
                .background(
                    when {
                        focused -> Amber
                        else -> Raised
                    },
                )
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            OneLine(text, if (focused) OnAmber else Ink, 17, Body, weight = FontWeight.Bold)
        }
    }
}
