/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.opentv.R
import kotlin.math.sin
import kotlin.random.Random

/*
 * PROTOTYPE for #89: which visual direction is Settee? Throwaway, never merged.
 *
 * Four directions over the same fictional catalogue, each free to lay its screens out its own way.
 * Everything here is invented and the artwork is generated, so nothing is copied from a reference
 * and no provider data is involved. Limits from #85 and #86: 960x540dp, 5% overscan, 14sp minimum,
 * no blur, no alpha-dimmed shelves, no animated layout, shadow on the focused item only.
 */

enum class Screen {
    HOME_PREVIEW,
    HOME,
    GUIDE,
    SHOWS,
    DETAILS,

    // Only E draws these; A to D fall back to the nearest screen they have.
    GUIDE_CATEGORIES,
    SHOWS_CATEGORIES,
    MOVIES,
    MOVIES_CATEGORIES,
}

/** What a highlight's OK does on the emulator build. Screenshots never press it. */
val LocalProtoNav = compositionLocalOf<(Screen) -> Unit> { {} }

val Atkinson = FontFamily(
    Font(R.font.proto_atkinson_regular, FontWeight.Normal),
    Font(R.font.proto_atkinson_bold, FontWeight.Bold),
)
val Alegreya = FontFamily(
    Font(R.font.proto_alegreya_regular, FontWeight.Normal),
    Font(R.font.proto_alegreya_medium, FontWeight.Medium),
    Font(R.font.proto_alegreya_bold, FontWeight.Bold),
)
val YoungSerif = FontFamily(Font(R.font.proto_young_serif, FontWeight.Normal))
val Zilla = FontFamily(
    Font(R.font.proto_zilla_medium, FontWeight.Medium),
    Font(R.font.proto_zilla_semibold, FontWeight.SemiBold),
)
val Poppins = FontFamily(
    Font(R.font.proto_poppins_regular, FontWeight.Normal),
    Font(R.font.proto_poppins_medium, FontWeight.Medium),
    Font(R.font.proto_poppins_semibold, FontWeight.SemiBold),
)

sealed interface Watch {
    data object Unwatched : Watch
    data class InProgress(val fraction: Float, val left: String) : Watch
    data object Watched : Watch
    data object UpNext : Watch
    data object NewEpisode : Watch
    data class EpisodesLeft(val count: Int) : Watch
}

fun Watch.label(): String = when (this) {
    Watch.Unwatched -> "Unwatched"
    is Watch.InProgress -> left
    Watch.Watched -> "Watched"
    Watch.UpNext -> "Up next"
    Watch.NewEpisode -> "New episode"
    is Watch.EpisodesLeft -> "$count to watch"
}

/** A null [seed] is a title with no artwork, which IPTV providers often leave out (#88). */
data class Item(val title: String, val sub: String, val seed: Int?, val watch: Watch)

data class Programme(val title: String, val minutes: Int)

data class Channel(val number: Int, val name: String, val programmes: List<Programme>, val logo: Color)

object ProtoData {
    val nowLabel = "20:10"
    val liveChannel = "Hearth TV"
    val liveProgramme = "The Evening Programme"
    val liveTimes = "20:00 to 21:00"
    const val LIVE_PROGRESS = 0.17f
    const val LIVE_SEED = 23

    val continueWatching = listOf(
        Item("Harbour Lights", "S2 E5", 11, Watch.InProgress(0.62f, "18 min left")),
        Item("The Allotment", "S4 E2", 12, Watch.UpNext),
        Item("Night Ferry", "Film", 13, Watch.InProgress(0.55f, "41 min left")),
        Item("Copper Street", "S1 E8", 14, Watch.NewEpisode),
        Item("Kestrel Road", "S3 E1", null, Watch.InProgress(0.2f, "36 min left")),
    )
    val favourites = listOf(
        Item("The Long Field", "Drama", 15, Watch.EpisodesLeft(3)),
        Item("Low Tide", "Mystery", 16, Watch.Watched),
        Item("Northern Line", "Crime", 17, Watch.EpisodesLeft(7)),
        Item("Kestrel Road", "Drama", null, Watch.EpisodesLeft(9)),
        Item("Copper Street", "Comedy", 14, Watch.EpisodesLeft(1)),
        Item("Harbour Lights", "Drama", 11, Watch.EpisodesLeft(5)),
    )
    val newTitles = listOf(
        Item("Salt Road", "Drama", 18, Watch.Unwatched),
        Item("Winter Orchard", "Film", 19, Watch.Unwatched),
        Item("The Glasshouse", "Thriller", 20, Watch.Unwatched),
        Item("Quiet Hours", "Film", 21, Watch.Unwatched),
        Item("Eastern Light", "Documentary", null, Watch.Unwatched),
        Item("Fenwick", "Comedy", 22, Watch.Unwatched),
    )
    val drama = listOf(
        Item("Harbour Lights", "2 series", 11, Watch.EpisodesLeft(5)),
        Item("The Long Field", "3 series", 15, Watch.EpisodesLeft(3)),
        Item("Salt Road", "1 series", 18, Watch.Unwatched),
        Item("Kestrel Road", "3 series", null, Watch.EpisodesLeft(9)),
        Item("Low Tide", "2 series", 16, Watch.Watched),
        Item("Fenwick", "4 series", 22, Watch.Unwatched),
    )
    val comedy = listOf(
        Item("Copper Street", "1 series", 14, Watch.EpisodesLeft(1)),
        Item("The Allotment", "5 series", 12, Watch.EpisodesLeft(12)),
        Item("Quiet Hours", "2 series", 21, Watch.Unwatched),
        Item("Northern Line", "6 series", 17, Watch.EpisodesLeft(7)),
        Item("The Glasshouse", "1 series", 20, Watch.Watched),
        Item("Eastern Light", "1 series", null, Watch.Unwatched),
    )
    val episodes = listOf(
        Item("The Pilot Boat", "S2 E3", 31, Watch.Watched),
        Item("Fog Signal", "S2 E4", 32, Watch.Watched),
        Item("Slack Water", "S2 E5", 33, Watch.InProgress(0.62f, "18 min left")),
        Item("Ebb", "S2 E6", 34, Watch.Unwatched),
        Item("The Long Pier", "S2 E7", 35, Watch.Unwatched),
    )
    val detailTitle = "Harbour Lights"
    val detailMeta = "Drama  ·  2 series  ·  2024"
    val detailBlurb = "When the harbour master of a small northern port goes missing, " +
        "his daughter comes home to keep the lights on, and finds the town has been keeping " +
        "more than one kind of secret from the sea."
    val categories = listOf("Favourites", "All channels", "Entertainment", "News", "Films", "Sport")
    val times = listOf("20:00", "20:30", "21:00")
    val channels = listOf(
        Channel(
            1, "Hearth TV", listOf(Programme("The Evening Programme", 60), Programme("Fireside Tales", 30)),
            Color(0xFFB5533C),
        ),
        Channel(
            2, "Northgate One", listOf(Programme("News at Eight", 30), Programme("Gardeners' Hour", 60)),
            Color(0xFF3F6C8C),
        ),
        Channel(
            3, "Meridian News", listOf(Programme("Meridian Tonight", 45), Programme("The World Now", 45)),
            Color(0xFF8C2F39),
        ),
        Channel(
            4, "Tideline Sport", listOf(Programme("Cup Replay", 90)),
            Color(0xFF2E7D62),
        ),
        Channel(
            5, "Kino", listOf(Programme("Winter Orchard", 75), Programme("Short Film", 15)),
            Color(0xFF6B4E8E),
        ),
        Channel(
            6, "Fenwick Films", listOf(Programme("Quiet Hours", 90)),
            Color(0xFF9A7B2F),
        ),
        Channel(
            7, "Arcade", listOf(Programme("Pixel Pals", 15), Programme("Robot Farm", 30), Programme("Bedtime", 45)),
            Color(0xFFC06C84),
        ),
        Channel(
            8, "Vale Local", listOf(Programme("Vale Today", 30), Programme("Local Lives", 30), Programme("Vale Late", 30)),
            Color(0xFF55708A),
        ),
    )
    const val NOW_MINUTES = 10
    val liveBlurb = "An hour of talk and music from the fireside."
}

private const val HILL_STEPS = 40

private data class ArtPalette(val top: Color, val bottom: Color, val sun: Color, val near: Color, val far: Color)

private val palettes = listOf(
    ArtPalette(Color(0xFF2B3A67), Color(0xFFE08E45), Color(0xFFFFD08A), Color(0xFF17202F), Color(0xFF3C3E5C)),
    ArtPalette(Color(0xFF16505B), Color(0xFF9FD2C2), Color(0xFFF4F1DE), Color(0xFF0E2F35), Color(0xFF2A7377)),
    ArtPalette(Color(0xFF5B2333), Color(0xFFE8A87C), Color(0xFFFFE3B3), Color(0xFF2A1119), Color(0xFF7E3B45)),
    ArtPalette(Color(0xFF233D2B), Color(0xFFB8C480), Color(0xFFF2EFC7), Color(0xFF12201A), Color(0xFF3F5F3F)),
    ArtPalette(Color(0xFF1F2A44), Color(0xFF7C9DB5), Color(0xFFDDE7EC), Color(0xFF10172A), Color(0xFF3A5068)),
    ArtPalette(Color(0xFF4A2C5E), Color(0xFFD97B8C), Color(0xFFFFD4B8), Color(0xFF241330), Color(0xFF6C3F73)),
    ArtPalette(Color(0xFF6E3B1E), Color(0xFFE5B567), Color(0xFFFFF0C2), Color(0xFF301A0D), Color(0xFF8E5A2B)),
)

/**
 * Generated stand-in for provider artwork: a sky, a sun and layered hills, so screens can be judged
 * with something that behaves like art instead of a flat block. Real art is a decoded bitmap, so
 * this says nothing about image-decoding cost.
 */
@Composable
fun Art(seed: Int, modifier: Modifier = Modifier, label: String? = null) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val random = Random(seed * 7919)
            val palette = palettes[seed.mod(palettes.size)]
            drawRect(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
            val sunCentre = Offset(
                size.width * (0.2f + random.nextFloat() * 0.6f),
                size.height * (0.28f + random.nextFloat() * 0.25f),
            )
            val sunRadius = size.minDimension * (0.06f + random.nextFloat() * 0.06f)
            drawCircle(
                Brush.radialGradient(
                    listOf(palette.sun, palette.sun.copy(alpha = 0f)),
                    center = sunCentre,
                    radius = sunRadius * 3.2f,
                ),
                radius = sunRadius * 3.2f,
                center = sunCentre,
            )
            drawCircle(palette.sun, sunRadius, sunCentre)
            listOf(palette.far to 0.62f, palette.near to 0.78f).forEach { (colour, level) ->
                val phase = random.nextFloat() * 6f
                val frequency = 2f + random.nextFloat() * 4f
                val hills = Path().apply {
                    moveTo(0f, size.height)
                    for (step in 0..HILL_STEPS) {
                        val x = size.width * step / HILL_STEPS
                        val wave = sin(x / size.width * frequency + phase)
                        lineTo(x, size.height * (level + wave * 0.07f))
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(hills, colour)
            }
        }
        if (label != null) {
            Text(
                label.uppercase(),
                color = Color.White,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * Anything the remote can land on. Each direction draws its own highlight from [content]'s flag;
 * the flag lives here, not at screen level, so a press recomposes one tile (#85).
 */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    initial: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    content: @Composable (focused: Boolean) -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    if (initial) LaunchedEffect(Unit) { requester.requestFocus() }
    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { event ->
                val press = event.type == KeyEventType.KeyUp &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                if (press) onClick()
                press
            }
            .focusable(),
    ) { content(focused) }
}

private val railWidth = 104.dp

private data class RailEntry(val icon: ImageVector, val target: Screen?)

/**
 * The same plain rail in every direction: #87's five tabs, collapsed at #90's 104dp so its marks stay
 * inside the overscan margin. Only its colours follow the direction; how it behaves is #90's question.
 */
@Composable
fun PlainRail(
    current: Int,
    background: Color,
    icon: Color,
    currentIcon: Color,
    mark: Color,
    focusFill: Color,
    onFocusFill: Color,
) {
    val nav = LocalProtoNav.current
    val entries = listOf(
        RailEntry(Icons.Filled.Home, Screen.HOME),
        RailEntry(Icons.Filled.LiveTv, Screen.GUIDE),
        RailEntry(Icons.Filled.Movie, Screen.MOVIES),
        RailEntry(Icons.Filled.Tv, Screen.SHOWS),
        RailEntry(Icons.Filled.FiberManualRecord, null),
    )
    Column(
        Modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(background)
            .padding(start = 48.dp, end = 8.dp, top = 60.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            Tile(
                Modifier.padding(vertical = 6.dp),
                onClick = { entry.target?.let(nav) },
            ) { focused ->
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(shape)
                        .background(if (focused) focusFill else background),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = when {
                            focused -> onFocusFill
                            index == current -> currentIcon
                            else -> icon
                        },
                        modifier = Modifier.size(24.dp),
                    )
                    if (index == current && !focused) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .size(width = 18.dp, height = 3.dp)
                                .background(mark, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** A channel's logo stand-in: providers' logos are patchy, so a plain initial tile is honest. */
@Composable
fun LogoTile(channel: Channel, size: Dp, modifier: Modifier = Modifier, fontFamily: FontFamily = FontFamily.SansSerif) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.18f))
            .background(channel.logo),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            channel.name.first().toString(),
            color = Color.White,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}

@Composable
fun ProgressLine(fraction: Float, track: Color, fill: Color, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(fill),
        )
    }
}

@Composable
fun OneLine(
    text: String,
    color: Color,
    size: Int,
    family: FontFamily,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
) {
    Text(
        text,
        color = color,
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = (size * 1.15f).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        modifier = modifier,
    )
}

/** The rail beside a screen's own content. Details pages opt out of it, as they do today. */
@Composable
fun WithRail(background: Color, rail: @Composable () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxSize()
            .background(background),
    ) {
        rail()
        Spacer(Modifier.width(16.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}
