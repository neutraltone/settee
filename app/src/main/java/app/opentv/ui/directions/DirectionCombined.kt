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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/*
 * E: the owner's combination from the first round. Picture House's colours, rounded cards with text
 * on the art, pill buttons, and Shows and details layouts; Hearth's type and larger first card; and
 * Listings' ruled, square guide and its layout for the Live Preview. Cover art is portrait, and
 * anything showing progress is landscape.
 *
 * Providers carry around 100k titles, so Live TV, Movies and Shows get a categories sidebar. It
 * stays tucked away until Left from the leftmost item opens it, so no press is added on the way to a
 * card, and it pushes the content in one step with no animated layout. Back to the first item is one
 * Right. Reaching the rail from content costs one extra Left.
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
private val Corner = RoundedCornerShape(2.dp)
private val Card = RoundedCornerShape(10.dp)
private const val COVER = 2f / 3f
private const val STILL = 16f / 9f
private val CoverWidth = 118.dp

private data class Category(val name: String, val count: String)

private val liveCategories = listOf(
    Category("Favourites", "24"),
    Category("All channels", "48,212"),
    Category("UK Entertainment", "1,204"),
    Category("UK News", "86"),
    Category("UK Sport", "412"),
    Category("UK Kids", "96"),
    Category("US Entertainment", "3,118"),
    Category("US News", "240"),
    Category("US Sport", "1,905"),
    Category("Films 24/7", "2,310"),
    Category("Music", "188"),
)
private val showCategories = listOf(
    Category("For you", ""),
    Category("All shows", "38,940"),
    Category("Drama", "8,431"),
    Category("Comedy", "6,120"),
    Category("Crime", "3,904"),
    Category("Documentary", "5,212"),
    Category("Kids", "4,377"),
    Category("Reality", "2,908"),
    Category("Sci-Fi & Fantasy", "1,766"),
    Category("UK", "7,045"),
    Category("US", "12,880"),
)
private val movieCategories = listOf(
    Category("For you", ""),
    Category("All films", "61,302"),
    Category("Action", "9,118"),
    Category("Comedy", "8,206"),
    Category("Drama", "12,744"),
    Category("Family", "3,590"),
    Category("Horror", "4,871"),
    Category("Romance", "3,322"),
    Category("Thriller", "6,015"),
    Category("4K", "2,467"),
    Category("Classics", "1,903"),
)

@Composable
private fun Rail(current: Int) = PlainRail(current, Panel, Soft, Coral, Coral, Coral, OnCoral)

@Composable
fun CombinedScreen(screen: Screen) {
    when (screen) {
        Screen.HOME_PREVIEW -> Home(preview = true)
        Screen.HOME -> Home(preview = false)
        Screen.GUIDE -> Guide(open = false)
        Screen.GUIDE_CATEGORIES -> Guide(open = true)
        Screen.SHOWS -> Browse(movies = false, open = false)
        Screen.SHOWS_CATEGORIES -> Browse(movies = false, open = true)
        Screen.MOVIES -> Browse(movies = true, open = false)
        Screen.MOVIES_CATEGORIES -> Browse(movies = true, open = true)
        Screen.DETAILS -> Details()
    }
}

/** Left from the leftmost item opens the sidebar, as the guide's channel column already does on main. */
private fun Modifier.leftOpens(open: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val left = event.key == Key.DirectionLeft
    if (left && event.type == KeyEventType.KeyDown) open()
    left
}

@Composable
private fun Sidebar(
    categories: List<Category>,
    selected: Int,
    unit: String,
    onSelect: (Int) -> Unit,
    onLeave: () -> Unit,
) {
    var entered by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(214.dp)
            .fillMaxHeight()
            .background(Panel)
            .onFocusChanged {
                if (it.hasFocus) entered = true
                if (entered && !it.hasFocus) onLeave()
            }
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 27.dp, bottom = 27.dp),
    ) {
        Tile(Modifier.fillMaxWidth()) { focused ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(Corner)
                    .background(if (focused) Coral else Panel)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = if (focused) OnCoral else Soft,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                OneLine("Find a category", if (focused) OnCoral else Soft, 15, Body)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
        categories.forEachIndexed { index, category ->
            Tile(
                Modifier.fillMaxWidth(),
                initial = index == selected,
                onClick = { onSelect(index) },
            ) { focused ->
                val current = index == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(Corner)
                        .background(if (focused) Coral else Panel)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OneLine(
                        category.name,
                        when {
                            focused -> OnCoral
                            current -> Coral
                            else -> Ink
                        },
                        16,
                        Body,
                        Modifier.weight(1f),
                        if (current || focused) FontWeight.Bold else FontWeight.Normal,
                    )
                    OneLine(category.count, if (focused) OnCoral else Soft, 14, Body)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        OneLine("11 of 214 $unit", Soft, 14, Body, Modifier.padding(horizontal = 8.dp))
    }
}

/** Where you are, and the only sign of the sidebar while it's tucked away. */
@Composable
private fun CategoryHeader(name: String, count: String, unit: String, open: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        OneLine(name, Ink, 28, Title)
        Spacer(Modifier.width(12.dp))
        OneLine(
            if (count.isEmpty()) "" else "$count $unit",
            Soft,
            15,
            Body,
            Modifier.padding(bottom = 3.dp).weight(1f),
        )
        if (!open) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = Coral,
                modifier = Modifier.padding(bottom = 3.dp).size(18.dp),
            )
            OneLine("Categories", Coral, 15, Body, Modifier.padding(bottom = 3.dp), FontWeight.Medium)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Column(Modifier.fillMaxWidth().padding(start = 5.dp, end = 48.dp)) {
        OneLine(text, Ink, 18, Title)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
        Spacer(Modifier.height(6.dp))
    }
}

private fun statusText(item: Item): String = when (val watch = item.watch) {
    Watch.Unwatched -> item.sub
    Watch.Watched -> "✓ Watched"
    is Watch.EpisodesLeft -> watch.label()
    else -> "${item.sub}  ·  ${watch.label()}"
}

private fun Modifier.lifted(focused: Boolean, scale: Float = 1.06f): Modifier =
    if (focused) {
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
            .shadow(14.dp, Card)
            .border(3.dp, Coral, Card)
    } else {
        this
    }

/** Picture House's card: rounded, with the text on the art behind a gradient painted on the card. */
@Composable
private fun ArtCard(item: Item, focused: Boolean, ratio: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .lifted(focused)
            .clip(Card)
            .background(Raised),
    ) {
        if (item.seed != null) Art(item.seed, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Bg.copy(alpha = 0f), 0.95f to Bg),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                item.title,
                color = Ink,
                fontFamily = Body,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            OneLine(statusText(item), if (focused) Coral else Soft, 15, Body, weight = FontWeight.Medium)
        }
        val watch = item.watch
        if (watch is Watch.InProgress) {
            ProgressLine(watch.fraction, Raised, Coral, Modifier.align(Alignment.BottomCenter), 4.dp)
        }
        if (focused) Box(Modifier.fillMaxSize().border(3.dp, Coral, Card))
    }
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
        // Geometry alone sends Down from this wide block to a card mid-row, and #87 allows one Down to
        // the first Continue Watching card.
        val firstCard = remember { FocusRequester() }
        if (preview) LivePreview(firstCard)
        Column {
            Heading("Continue Watching")
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 5.dp, end = 48.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ProtoData.continueWatching.forEachIndexed { index, item ->
                    Tile(
                        Modifier
                            .width(if (index == 0 && !preview) 300.dp else 186.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstCard) else Modifier),
                        initial = index == 0 && !preview,
                    ) { focused -> ArtCard(item, focused, STILL) }
                }
            }
        }
        HomeRow("Favourites", ProtoData.favourites)
        HomeRow("New", ProtoData.newTitles)
    }
}

/** Cover art, so portrait, as on the Shows and Movies tabs. Only progress earns a landscape still. */
@Composable
private fun HomeRow(title: String, items: List<Item>) {
    Column {
        Heading(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 5.dp, end = 48.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEach { item ->
                Tile(Modifier.width(CoverWidth)) { focused -> ArtCard(item, focused, COVER) }
            }
        }
    }
}

/**
 * Listings' layout for the Live Preview, selected as one block as in Picture House. Only after live
 * playback, so Home without it opens on Continue Watching.
 */
@Composable
private fun LivePreview(down: FocusRequester) {
    val channel = ProtoData.channels.first()
    Tile(
        Modifier
            .padding(start = 5.dp, end = 48.dp)
            .fillMaxWidth()
            .focusProperties { this.down = down },
        initial = true,
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(138.dp)
                .lifted(focused, 1.02f)
                .clip(Card)
                .background(if (focused) Raised else Panel),
        ) {
            Art(ProtoData.LIVE_SEED, Modifier.fillMaxHeight().aspectRatio(STILL))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).padding(top = 10.dp, end = 18.dp)) {
                OneLine(ProtoData.liveProgramme, Ink, 28, Title)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoTile(channel, 24.dp, fontFamily = Title)
                    Spacer(Modifier.width(8.dp))
                    OneLine("${channel.name}  ·  ${ProtoData.liveTimes}", Coral, 15, Body, weight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                ProgressLine(ProtoData.LIVE_PROGRESS, Rule, Coral)
                Spacer(Modifier.height(8.dp))
                Text(
                    ProtoData.liveBlurb,
                    color = Soft,
                    fontFamily = Body,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

private const val GUIDE_MINUTES = 90f
private const val LIVE_SELECTED = 1

@Composable
private fun Guide(open: Boolean) = WithRail(Bg, { Rail(1) }) {
    var isOpen by remember { mutableStateOf(open) }
    var selected by remember { mutableStateOf(LIVE_SELECTED) }
    Row(Modifier.fillMaxSize()) {
        if (isOpen) {
            Sidebar(liveCategories, selected, "categories", { selected = it }, { isOpen = false })
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp, end = 48.dp, bottom = 27.dp)) {
            CategoryHeader(liveCategories[selected].name, liveCategories[selected].count, "channels", isOpen)
            Spacer(Modifier.height(10.dp))
            val channelWidth = if (isOpen) 150.dp else 190.dp
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                OneLine("Now ${ProtoData.nowLabel}", Coral, 15, Body, Modifier.width(channelWidth), FontWeight.Bold)
                Row(Modifier.weight(1f)) {
                    ProtoData.times.forEach { OneLine(it, Soft, 15, Body, Modifier.weight(1f), FontWeight.Medium) }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Ink))
            ProtoData.channels.forEachIndexed { row, channel ->
                Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(channelWidth), verticalAlignment = Alignment.CenterVertically) {
                        LogoTile(channel, 28.dp, fontFamily = Title)
                        Spacer(Modifier.width(8.dp))
                        OneLine(channel.name, Ink, 16, Body, Modifier.weight(1f), FontWeight.Bold)
                    }
                    Row(Modifier.weight(1f).fillMaxHeight()) {
                        channel.programmes.forEachIndexed { index, programme ->
                            GuideCell(programme, index, row == 0 && !open, Modifier.weight(programme.minutes / GUIDE_MINUTES)) {
                                isOpen = true
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
}

@Composable
private fun GuideCell(programme: Programme, index: Int, firstRow: Boolean, modifier: Modifier, openSidebar: () -> Unit) {
    Tile(
        modifier
            .fillMaxHeight()
            .then(if (index == 0) Modifier.leftOpens(openSidebar) else Modifier),
        initial = firstRow && index == 0,
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(vertical = 3.dp)
                .clip(Corner)
                .background(if (focused) Coral else Bg)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            OneLine(
                programme.title,
                when {
                    focused -> OnCoral
                    index == 0 -> Ink
                    else -> Soft
                },
                16,
                Body,
                weight = if (focused || index == 0) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/**
 * Movies and Shows share this. "For you" keeps the tab's rows (#87); any other category is that
 * category's whole grid, which is what 100k titles needs and what chips open on main today.
 */
@Composable
private fun Browse(movies: Boolean, open: Boolean) {
    val nav = LocalProtoNav.current
    val categories = if (movies) movieCategories else showCategories
    val unit = if (movies) "films" else "shows"
    val toFilm = { item: Item -> item.copy(sub = "Film") }
    val first = if (movies) ProtoData.newTitles.map(toFilm) else ProtoData.drama
    val second = if (movies) ProtoData.drama.map(toFilm) else ProtoData.comedy
    var isOpen by remember { mutableStateOf(open) }
    var selected by remember { mutableStateOf(if (open) 2 else 0) }
    var lead by remember { mutableStateOf(first.first()) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Full-bleed, swapped on each press, so provisional until #84 reports. Two gradients here
        // would need merging into the one scrim #86 allows.
        lead.seed?.let { Art(it, Modifier.fillMaxWidth().height(290.dp)) }
        Box(
            Modifier.fillMaxWidth().height(292.dp).background(
                Brush.verticalGradient(0.3f to Bg.copy(alpha = 0f), 1f to Bg),
            ),
        )
        Box(
            Modifier.fillMaxWidth().height(292.dp).background(
                Brush.horizontalGradient(0.1f to Bg, 0.7f to Bg.copy(alpha = 0f)),
            ),
        )
        WithRail(Color.Transparent, { Rail(if (movies) 2 else 3) }) {
            Row(Modifier.fillMaxSize()) {
                if (isOpen) {
                    Sidebar(categories, selected, "categories", { selected = it }, { isOpen = false })
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 27.dp)) {
                    Box(Modifier.padding(start = 5.dp, end = 48.dp)) {
                        CategoryHeader(categories[selected].name, categories[selected].count, unit, isOpen)
                    }
                    Spacer(Modifier.height(8.dp))
                    OneLine(lead.title, Ink, 32, Title, Modifier.padding(start = 5.dp, end = 48.dp))
                    OneLine(
                        if (lead.watch == Watch.Unwatched) lead.sub else "${lead.sub}  ·  ${statusText(lead)}",
                        Coral,
                        15,
                        Body,
                        Modifier.padding(start = 5.dp),
                        FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        ProtoData.detailBlurb,
                        color = Ink,
                        fontFamily = Body,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 5.dp).width(470.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 27.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selected == 0) {
                            PosterRow("Recently added", first, !open, { lead = it }, { isOpen = true }) { nav(Screen.DETAILS) }
                            PosterRow(if (movies) "Top rated" else "New episodes", second, false, { lead = it }, { isOpen = true }) {
                                nav(Screen.DETAILS)
                            }
                        } else {
                            val perRow = if (isOpen) 4 else 5
                            (first + second).chunked(perRow).forEachIndexed { row, items ->
                                Row(Modifier.padding(start = 5.dp, end = 48.dp, top = 5.dp, bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items.forEachIndexed { index, item ->
                                        PosterTile(
                                            item,
                                            row == 0 && index == 0 && !open,
                                            index == 0,
                                            { lead = item },
                                            { isOpen = true },
                                        ) { nav(Screen.DETAILS) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    items: List<Item>,
    initial: Boolean,
    onLead: (Item) -> Unit,
    openSidebar: () -> Unit,
    onClick: () -> Unit,
) {
    Column {
        Heading(title)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(start = 5.dp, end = 48.dp, top = 5.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items.forEachIndexed { index, item ->
                PosterTile(item, initial && index == 0, index == 0, { onLead(item) }, openSidebar, onClick)
            }
        }
    }
}

@Composable
private fun PosterTile(
    item: Item,
    initial: Boolean,
    leftmost: Boolean,
    onFocused: () -> Unit,
    openSidebar: () -> Unit,
    onClick: () -> Unit,
) {
    Tile(
        Modifier
            .width(CoverWidth)
            .then(if (leftmost) Modifier.leftOpens(openSidebar) else Modifier),
        initial = initial,
        onFocused = onFocused,
        onClick = onClick,
    ) { focused -> ArtCard(item, focused, COVER) }
}

private val seriesEpisodes =
    listOf(
        Item("Home Water", "S2 E1", 36, Watch.Watched),
        Item("The Keeper's Log", "S2 E2", 37, Watch.Watched),
    ) + ProtoData.episodes + Item("Lights Out", "S2 E8", 38, Watch.Unwatched)

/**
 * Picture House's hero with the real app's thumbnail-left rows under it, so a film's page is the same
 * hero with nothing beneath. The page scrolls as one, carrying the hero off as the highlight goes down.
 */
@Composable
private fun Details() {
    val current = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var listTop by remember { mutableStateOf(0) }
    // Snaps in one step between the hero and the list: left to itself the page scrolls only enough to
    // show the row, which cuts the title in half and leaves the highlight in the overscan margin.
    val showHero = { scope.launch { scroll.scrollTo(0) } }
    val showList = { if (scroll.value < listTop) scope.launch { scroll.scrollTo(listTop) } }
    Box(Modifier.fillMaxSize().background(Bg)) {
        // Leaves with the hero, through graphicsLayer so scrolling never redraws or re-measures it.
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = -scroll.value.toFloat() }) {
            Art(11, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.2f to Bg.copy(alpha = 0f), 0.62f to Bg),
                ),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 48.dp, end = 48.dp, top = 76.dp, bottom = 27.dp),
        ) {
            OneLine(ProtoData.detailTitle, Ink, 54, Title)
            OneLine(ProtoData.detailMeta, Coral, 16, Body, weight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                ProtoData.detailBlurb,
                color = Ink,
                fontFamily = Body,
                fontSize = 18.sp,
                lineHeight = 23.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(620.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(Icons.Filled.PlayArrow, "Resume S2 E5", true, current) { showHero() }
                Action(Icons.Filled.StarBorder, "Favourite", false, current) { showHero() }
            }
            Spacer(Modifier.height(14.dp))
            val margin = with(LocalDensity.current) { 27.dp.roundToPx() }
            OneLine(
                "Series 2",
                Ink,
                18,
                Title,
                Modifier.onPlaced { listTop = it.positionInRoot().y.toInt() + scroll.value - margin },
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Rule))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Down from either button lands on the episode in progress, not the top of the list.
                seriesEpisodes.forEach { episode ->
                    val inProgress = episode.watch is Watch.InProgress
                    Tile(
                        Modifier
                            .fillMaxWidth()
                            .then(if (inProgress) Modifier.focusRequester(current) else Modifier),
                        onFocused = { showList() },
                    ) { focused -> EpisodeRow(episode, focused) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Item, focused: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(Card)
            .background(if (focused) Coral else Panel)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(7.dp))) {
            if (episode.seed != null) Art(episode.seed, Modifier.fillMaxSize())
            val watch = episode.watch
            if (watch is Watch.InProgress) {
                ProgressLine(watch.fraction, Raised, if (focused) Ink else Coral, Modifier.align(Alignment.BottomCenter), 4.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Watched episodes step back by colour rather than alpha, which would force a layer (#85).
            OneLine(
                episode.title,
                when {
                    focused -> OnCoral
                    episode.watch == Watch.Watched -> Soft
                    else -> Ink
                },
                17,
                Body,
                weight = FontWeight.Bold,
            )
            OneLine(episode.sub, if (focused) OnCoral else Soft, 15, Body)
        }
        val status = when (val watch = episode.watch) {
            Watch.Watched -> "✓ Watched"
            is Watch.InProgress -> watch.left
            else -> ""
        }
        OneLine(
            status,
            when {
                focused -> OnCoral
                episode.watch == Watch.Watched -> Soft
                else -> Coral
            },
            15,
            Body,
            Modifier.padding(start = 8.dp, end = 10.dp),
            FontWeight.Medium,
        )
    }
}

@Composable
private fun Action(icon: ImageVector, text: String, primary: Boolean, down: FocusRequester, onFocused: () -> Unit) {
    Tile(Modifier.focusProperties { this.down = down }, initial = primary, onFocused = onFocused) { focused ->
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(50))
                .background(if (focused) Coral else Raised)
                .padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (focused) OnCoral else Ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            OneLine(text, if (focused) OnCoral else Ink, 17, Body, weight = FontWeight.Bold)
        }
    }
}
