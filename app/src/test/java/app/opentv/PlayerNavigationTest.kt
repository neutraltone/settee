/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.data.repo.MediaKey
import app.opentv.ui.Tab
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where Back goes, through the app's real routes. Each screen is a stand-in that only says which
 * screen it is (and, for the main screen, which tab it's on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlayerNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun setApp() = compose.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = Routes.MAIN) {
            composable(Routes.MAIN) { entry ->
                val tab by entry.shellTab()
                Text("Main: $tab")
            }
            composable(Routes.MOVIE_DETAIL) { entry -> Text("Movie ${entry.arguments?.getString("movieId")}") }
            composable(Routes.SERIES_DETAIL) { entry -> Text("Series ${entry.arguments?.getString("seriesId")}") }
            composable(Routes.SEARCH) { Text("Search") }
            composable(Routes.PLAYER) { Text("Live player") }
            composable(Routes.VOD_PLAYER) { Text("Movie player") }
        }
    }

    private fun act(block: NavHostController.() -> Unit) = compose.runOnIdle { nav.block() }

    private fun back() = act { popBackStack() }

    private fun showing(text: String) = compose.onNodeWithText(text).assertExists()

    @Test
    fun `the main screen is still on the tab you left when you come back from a detail page`() {
        setApp()
        act {
            selectShellTab(Tab.MOVIES)
            navigate(Routes.movieDetail(9))
        }
        showing("Movie 9")

        back()

        showing("Main: MOVIES")
    }

    @Test
    fun `a channel opened on launch or from a reminder goes back to the Live guide`() {
        setApp()
        act { openLivePlayerOverGuide(channelId = 5) }
        showing("Live player")

        back()

        showing("Main: LIVE")
    }

    @Test
    fun `a reminder tapped while on another screen still goes back to the Live guide`() {
        setApp()
        act {
            navigate(Routes.movieDetail(9))
            openLivePlayerOverGuide(channelId = 5)
        }

        back()

        showing("Main: LIVE")
    }

    @Test
    fun `a channel opened from Search goes back to Search`() {
        setApp()
        act {
            navigate(Routes.SEARCH)
            navigate(Routes.player(5))
        }

        back()

        showing("Search")
    }

    @Test
    fun `Back from a recording the live player switched to never reopens live TV`() {
        setApp()
        act {
            selectShellTab(Tab.LIVE)
            navigate(Routes.player(5))
            switchToRecording(Routes.vodPlayer(MediaKey.Recording(1)))
        }
        showing("Movie player")

        back()

        showing("Main: LIVE")
    }

    private fun playMovie(key: MediaKey = MediaKey.Movie(9)) = Routes.vodPlayer(key)

    @Test
    fun `Back from a movie started on Home opens the movie's page, and Back from there goes Home`() {
        setApp()
        act { navigate(playMovie()) }

        act { leaveVodPlayer(currentBackStackEntry!!, Routes.movieDetail(9)) }
        showing("Movie 9")
        back()

        showing("Main: HOME")
    }

    @Test
    fun `Back from a movie started on its page goes back to that page, not a second copy of it`() {
        setApp()
        act {
            navigate(Routes.movieDetail(9))
            navigate(playMovie())
        }

        act { leaveVodPlayer(currentBackStackEntry!!, Routes.movieDetail(9)) }
        showing("Movie 9")
        back()

        showing("Main: HOME")
    }

    @Test
    fun `Back from an episode started on its series' page goes back to that page`() {
        setApp()
        act {
            navigate(Routes.seriesDetail(3))
            navigate(playMovie(MediaKey.Episode(5)))
        }

        act { leaveVodPlayer(currentBackStackEntry!!, Routes.seriesDetail(3)) }
        showing("Series 3")
        back()

        showing("Main: HOME")
    }

    @Test
    fun `Back from something with no page of its own goes back to where it started`() {
        setApp()
        act {
            navigate(Routes.SEARCH)
            navigate(playMovie(MediaKey.Recording(1)))
        }

        act { leaveVodPlayer(currentBackStackEntry!!, titlePage = null) }

        showing("Search")
    }

    @Test
    fun `a movie's page is the movie's, and an episode's is its series'`() = runTest {
        val seriesOfEpisode: suspend (Long) -> Long? = { episodeId -> if (episodeId == 5L) 3L else null }

        assertThat(titlePageFor(MediaKey.Movie(9), seriesOfEpisode)).isEqualTo(Routes.movieDetail(9))
        assertThat(titlePageFor(MediaKey.Episode(5), seriesOfEpisode)).isEqualTo(Routes.seriesDetail(3))
    }

    @Test
    fun `recordings, catch-up and an episode whose series is gone have no page`() = runTest {
        val seriesOfEpisode: suspend (Long) -> Long? = { null }

        assertThat(titlePageFor(MediaKey.Recording(1), seriesOfEpisode)).isNull()
        assertThat(titlePageFor(MediaKey.Catchup(7, 1_700_000_000_000L), seriesOfEpisode)).isNull()
        assertThat(titlePageFor(MediaKey.Episode(5), seriesOfEpisode)).isNull()
    }

    @Test
    fun `tapping the live player's Back twice only leaves the player once`() {
        setApp()
        act { navigate(Routes.player(5)) }

        act {
            val player = currentBackStackEntry!!
            leaveLivePlayer(player)
            leaveLivePlayer(player)
        }

        showing("Main: HOME")
    }

    @Test
    fun `tapping the movie player's Back twice only leaves the player once`() {
        setApp()
        act { navigate(playMovie()) }

        act {
            val player = currentBackStackEntry!!
            leaveVodPlayer(player, Routes.movieDetail(9))
            leaveVodPlayer(player, Routes.movieDetail(9))
        }
        showing("Movie 9")
        back()

        showing("Main: HOME")
    }
}
