/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.data.repo.CoverStatus
import app.opentv.data.repo.EpisodeStatuses
import app.opentv.data.repo.MediaKey
import app.opentv.data.repo.SeriesMainAction
import app.opentv.data.repo.VodTitle
import app.opentv.data.repo.WatchStatus
import app.opentv.ui.Cover
import app.opentv.ui.HomeContent
import app.opentv.ui.MainShell
import app.opentv.ui.ResumeItem
import app.opentv.ui.SeasonEpisode
import app.opentv.ui.Tab
import app.opentv.ui.visibleTabs
import app.opentv.ui.vod.MovieDetailContent
import app.opentv.ui.vod.MoviesScreen
import app.opentv.ui.vod.SeriesDetailContent
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.ErrorResult
import coil.request.SuccessResult
import coil.test.FakeImageLoaderEngine
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * The README's screenshots (#251). The other screenshot tests draw artwork as flat colours, which
 * reads as a developer's screen on the project's front page, so these draw the same screens with
 * real artwork: open films from the Blender Foundation and posters whose copyright has expired,
 * listed with their licences in src/test/resources/readme/CREDITS.md. Studio artwork from TMDB was
 * rejected: the app may show it, but this repository can't redistribute it.
 *
 * The PNGs come out with the rest (./gradlew recordRoborazziDebug, or the Screenshots workflow on a
 * PR) and are copied into docs/screenshots/ by hand when the screens change enough to matter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = TV_1080P, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ReadmeScreenshotsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val artwork = ReadmeArtwork()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val graph = ServiceLocator.Graph(app)
    private val viewModels = TestViewModels()

    @After
    fun close() {
        viewModels.clear()
        graph.database.close()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun art(name: String) = "https://example.com/readme/$name.jpg"

    /**
     * A film whose artwork is `<art>-poster.jpg`, with its year, running time and genres; [withBackdrop]
     * adds `<art>-backdrop.jpg`.
     */
    private fun film(id: Long, name: String, art: String, facts: Facts, plot: String) = Movie(
        id = id,
        sourceId = 1,
        streamId = "$id",
        name = name,
        categoryId = facts.category,
        posterUrl = art("$art-poster"),
        rating = null,
        year = facts.year,
        plot = plot,
        durationSeconds = facts.minutes * 60,
        containerExtension = "mkv",
        streamUrl = "https://example.com/$id.mkv",
        genre = facts.genre,
        addedMillis = id,
    )

    private data class Facts(val year: Int, val minutes: Int, val genre: String, val category: String = "open")

    private fun Movie.withBackdrop(art: String) = copy(backdropUrl = art("$art-backdrop"))

    private val sintel = film(
        id = 1,
        name = "Sintel",
        art = "sintel",
        facts = Facts(2010, 15, "Fantasy, Animation"),
        plot = "A young woman crosses a harsh land to find the baby dragon she raised and lost.",
    ).withBackdrop("sintel").copy(cast = "Halina Reijn, Thom Hoffman", director = "Colin Levy")
    private val tearsOfSteel = film(
        id = 2,
        name = "Tears of Steel",
        art = "tears-of-steel",
        facts = Facts(2012, 12, "Science Fiction"),
        plot = "In a future Amsterdam, scientists try to save the world by reliving one painful moment.",
    ).withBackdrop("tears-of-steel")
    private val bigBuckBunny = film(
        id = 3,
        name = "Big Buck Bunny",
        art = "big-buck-bunny",
        facts = Facts(2008, 10, "Comedy, Animation"),
        plot = "A gentle giant of a rabbit has had enough of the three bullies who wreck his day.",
    ).withBackdrop("big-buck-bunny")
    private val elephantsDream = film(
        id = 4,
        name = "Elephants Dream",
        art = "elephants-dream",
        facts = Facts(2006, 11, "Science Fiction, Animation"),
        plot = "Two men find their way through a vast machine that only one of them believes in.",
    ).withBackdrop("elephants-dream")
    private val cosmosLaundromat = film(
        id = 5,
        name = "Cosmos Laundromat",
        art = "cosmos-laundromat",
        facts = Facts(2015, 12, "Comedy, Animation"),
        plot = "A sheep with nothing left to live for is offered every life he could want.",
    )
    private val spring = film(
        id = 6,
        name = "Spring",
        art = "spring",
        facts = Facts(2019, 8, "Fantasy, Animation"),
        plot = "A shepherd girl and her dog face the ancient spirits that keep the seasons turning.",
    )
    private val theGeneral = film(
        id = 7,
        name = "The General",
        art = "the-general",
        facts = Facts(1926, 78, "Comedy, Action", "silent"),
        plot = "A train driver chases his stolen locomotive, and the woman he loves, behind enemy lines.",
    )
    private val sherlockJr = film(
        id = 8,
        name = "Sherlock Jr.",
        art = "sherlock-jr",
        facts = Facts(1924, 45, "Comedy", "silent"),
        plot = "A projectionist who dreams of being a detective walks into the film he's showing.",
    )
    private val theKid = film(
        id = 9,
        name = "The Kid",
        art = "the-kid",
        facts = Facts(1921, 68, "Comedy, Drama", "silent"),
        plot = "A tramp finds an abandoned baby and brings him up as his own.",
    )
    private val theGoldRush = film(
        id = 10,
        name = "The Gold Rush",
        art = "the-gold-rush",
        facts = Facts(1925, 95, "Comedy, Adventure", "silent"),
        plot = "A lone prospector heads for the Klondike and finds snow, hunger and love.",
    )
    private val safetyLast = film(
        id = 11,
        name = "Safety Last!",
        art = "safety-last",
        facts = Facts(1923, 70, "Comedy", "silent"),
        plot = "A shop clerk climbs the outside of a department store to impress his girl.",
    )
    private val caligari = film(
        id = 12,
        name = "The Cabinet of Dr. Caligari",
        art = "caligari",
        facts = Facts(1920, 77, "Horror", "silent"),
        plot = "A showman and his sleepwalker arrive in a mountain town, and the murders begin.",
    )
    private val detour = film(
        id = 13,
        name = "Detour",
        art = "detour",
        facts = Facts(1945, 68, "Crime, Drama", "classic"),
        plot = "A pianist hitchhiking across America picks up a lift, and a run of very bad luck.",
    )
    private val plan9 = film(
        id = 14,
        name = "Plan 9 from Outer Space",
        art = "plan-9",
        facts = Facts(1957, 79, "Science Fiction", "classic"),
        plot = "Visitors from space raise the dead to stop humanity building a doomsday weapon.",
    )

    private val openFilms = listOf(sintel, tearsOfSteel, bigBuckBunny, elephantsDream, cosmosLaundromat, spring)
    private val classics = listOf(theGeneral, sherlockJr, theKid, theGoldRush, safetyLast, caligari, detour, plan9)

    private val flashGordon = Series(
        id = 1,
        sourceId = 1,
        seriesId = "s",
        name = "Flash Gordon",
        categoryId = null,
        posterUrl = art("flash-gordon-poster"),
        rating = null,
        year = 1936,
        plot = "A polo player and a scientist fly to the planet Mongo to stop Ming the Merciless.",
        backdropUrl = art("flash-gordon-backdrop"),
    )

    private val chapters = listOf(
        "The Planet of Peril",
        "The Tunnel of Terror",
        "Captured by Shark Men",
        "Battling the Sea Beast",
        "The Destroying Ray",
        "Flaming Torture",
        "Shattering Doom",
        "Tournament of Death",
        "Fighting the Fire Dragon",
        "The Unseen Peril",
        "In the Claws of the Tigron",
        "Trapped in the Turret",
        "Rocketing to Earth",
    ).mapIndexed { i, title -> testEpisode(1, i + 1).copy(title = title, durationSeconds = 20 * 60) }

    @Test
    fun `Home, with something to pick up and rows of favourites`() {
        val resume = listOf(
            ResumeItem(MediaKey.Movie(sintel.id), sintel.name, sintel.backdropUrl, progress = 0.4f),
            ResumeItem(
                MediaKey.Episode(chapters[3].id),
                flashGordon.name,
                flashGordon.backdropUrl,
                progress = 0.65f,
                episode = SeasonEpisode(1, 4),
            ),
            ResumeItem(MediaKey.Movie(theGeneral.id), theGeneral.name, theGeneral.posterUrl, progress = 0.2f),
        )
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.HOME,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, takeFocus, onNothingToShow ->
                HomeContent(
                    continueWatching = resume,
                    favouriteShows = listOf(Cover(VodTitle.OfSeries(flashGordon))),
                    favouriteMovies = classics.take(5).map { Cover(VodTitle.OfMovie(it)) },
                    favouriteChannels = emptyList(),
                    recentMovies = openFilms.map { Cover(VodTitle.OfMovie(it)) },
                    onResume = {},
                    onOpenSeries = {},
                    onOpenMovie = {},
                    onPlayChannel = {},
                    takeFocus = takeFocus,
                    onNothingToShow = onNothingToShow,
                )
            }
        }
        artwork.awaitLoaded(compose)

        // Sintel is in Recently added too; the first is its Continue Watching card.
        compose.onAllNodes(hasText(sintel.name) and hasClickAction())[0].assertIsFocused()
        captureScreen("readme_home")
    }

    @Test
    fun `a film's page`() {
        compose.setTvContent {
            MovieDetailContent(
                movie = sintel,
                resumes = true,
                status = CoverStatus.InProgress(0.4f, remainingMillis = 9 * 60_000L),
                favourite = true,
                findAddonStreams = null,
                similar = emptyList(),
                onOpenMovie = {},
                onOpenPerson = {},
                onPlay = {},
                onPlayAddonStream = {},
                onSetFavourite = {},
            )
        }
        artwork.awaitLoaded(compose)

        compose.onNode(hasText("Resume", substring = true) and hasClickAction()).assertIsFocused()
        captureScreen("readme_film")
    }

    @Test
    fun `a show's page`() {
        val watched = (0..2).associate { chapters[it].id to WatchStatus.Watched } +
            (chapters[3].id to WatchStatus.InProgress(0.65f, remainingMillis = 7 * 60_000L))
        compose.setTvContent {
            SeriesDetailContent(
                series = flashGordon,
                episodes = chapters,
                statuses = EpisodeStatuses(watched),
                mainAction = SeriesMainAction.Resume(chapters[3]),
                status = null,
                favourite = true,
                onPlayEpisode = {},
                onSetFavourite = {},
                onOpenPerson = {},
            )
        }
        artwork.awaitLoaded(compose)

        compose.onNodeWithText("Resume S1 E4").assertIsFocused()
        captureScreen("readme_show")
    }

    @Test
    fun `Movies, on a film with a backdrop`() {
        runBlocking {
            graph.database.categories().upsertAll(
                listOf(
                    Category("open", 1, "Open films", StreamKind.MOVIE, sortIndex = 0),
                    Category("silent", 1, "Silent classics", StreamKind.MOVIE, sortIndex = 1),
                    Category("classic", 1, "Cult classics", StreamKind.MOVIE, sortIndex = 2),
                ),
            )
            graph.database.movies().upsertAll(classics + openFilms)
        }
        val viewModel = viewModels.vod(app, graph)
        compose.setTvContent {
            MainShell(
                tabs = visibleTabs(liveEnabled = true, moviesEnabled = true, seriesEnabled = true),
                current = Tab.MOVIES,
                onSelect = {},
                onOpenSearch = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                activeProfileName = "Sam",
                onExit = {},
                modifier = Modifier.fillMaxSize(),
            ) { _, _, _ ->
                MoviesScreen(
                    onOpenMovie = {},
                    onResume = {},
                    hasSources = false,
                    isSyncing = false,
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !viewModel.recommendingMovies.value && viewModel.recommendedMovies.value.isNotEmpty()
        }
        // As the app gets there: OK on Movies in the rail lands on the first cover, then Right
        // along the row to a film with a backdrop. Focusing the cover directly lost out to the
        // screen placing its own first Highlight once it had loaded.
        compose.onNode(hasContentDescription("Movies") or hasText("Movies")).requestFocus()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(500)
        val cover = hasText(tearsOfSteel.name) and hasClickAction() and isFocused()
        repeat(openFilms.size + classics.size) {
            if (compose.onAllNodes(cover).fetchSemanticsNodes().isEmpty()) press(Key.DirectionRight)
        }
        compose.onNode(cover).assertExists()
        compose.waitUntilAtLeastOneExists(hasText(tearsOfSteel.plot!!), 5_000)
        artwork.awaitLoaded(compose)

        captureScreen("readme_movies")
    }
}

/**
 * Serves `https://example.com/readme/<name>.jpg` from src/test/resources/readme/, so a screen draws
 * real artwork without the network. A URL with no file there fails the test, rather than leaving a
 * gap in a README screenshot nobody looks at closely.
 */
class ReadmeArtwork : ExternalResource() {
    private val missing = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val pending = java.util.concurrent.atomic.AtomicInteger()

    override fun before() {
        val engine = FakeImageLoaderEngine.Builder()
            .default { chain ->
                val url = chain.request.data.toString()
                val bitmap = javaClass.classLoader!!
                    .getResourceAsStream("readme/" + url.substringAfterLast('/'))
                    ?.use(BitmapFactory::decodeStream)
                if (bitmap == null) {
                    missing += url
                    ErrorResult(null, chain.request, IllegalStateException("No README artwork for $url"))
                } else {
                    SuccessResult(
                        drawable = BitmapDrawable(chain.request.context.resources, bitmap),
                        request = chain.request,
                        dataSource = DataSource.MEMORY,
                    )
                }
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext())
                .components { add(engine) }
                .eventListener(
                    object : coil.EventListener {
                        override fun onStart(request: coil.request.ImageRequest) {
                            pending.incrementAndGet()
                        }

                        override fun onSuccess(request: coil.request.ImageRequest, result: SuccessResult) {
                            pending.decrementAndGet()
                        }

                        override fun onError(request: coil.request.ImageRequest, result: ErrorResult) {
                            pending.decrementAndGet()
                        }

                        override fun onCancel(request: coil.request.ImageRequest) {
                            pending.decrementAndGet()
                        }
                    },
                )
                .build(),
        )
    }

    /** Waits until every image the screen asked for has arrived; Compose's idling doesn't wait for Coil. */
    fun awaitLoaded(compose: androidx.compose.ui.test.junit4.ComposeTestRule) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitUntil(LOAD_TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            pending.get() == 0
        }
        compose.mainClock.advanceTimeBy(SETTLE_MILLIS)
        compose.waitForIdle()
    }

    override fun after() {
        Coil.reset()
        assertWithMessage("README artwork with no file in src/test/resources/readme/").that(missing).isEmpty()
    }

    private companion object {
        // Long enough for a screen to place its Highlight and fade its backdrop in.
        const val SETTLE_MILLIS = 1_000L
        const val LOAD_TIMEOUT_MILLIS = 10_000L
    }
}
