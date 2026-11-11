/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.MOVIES_WITH_PERSON
import app.opentv.data.db.SERIES_WITH_PERSON
import app.opentv.data.repo.VodTitle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a person's page lists: every film and show in the library with them in it, as an actor or
 * the director, including films nobody has opened yet.
 */
// Plain Application: OpenTvApp schedules WorkManager on start, which the database doesn't need.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PersonTitlesTest {

    private val test = TestCatalog(ApplicationProvider.getApplicationContext())
    private val panel = test.panel

    @After
    fun close() = test.close()

    private suspend fun titlesWith(name: String): List<String> = test.catalog.titlesWithPerson(name).map {
        when (it) {
            is VodTitle.OfMovie -> it.movie.name
            is VodTitle.OfSeries -> it.series.name
        }
    }

    @Test
    fun `a film nobody has opened is on its actor's and director's pages after a sync`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 3, "name": "Drive"}]"""
        panel.replies["get_vod_info"] =
            """{"info": {"genre": "Crime", "cast": "Ryan Gosling, Carey Mulligan",
                "director": "Nicolas Winding Refn"}}"""

        test.syncVod()

        assertThat(titlesWith("Carey Mulligan")).containsExactly("Drive")
        assertThat(titlesWith("Nicolas Winding Refn")).containsExactly("Drive")
    }

    @Test
    fun `a name finds only that person, not names it's part of`() = runTest {
        panel.replies["get_vod_streams"] = """[
            {"stream_id": 1, "name": "The Abyss", "cast": "Ed Harris, Mary Elizabeth Mastrantonio"},
            {"stream_id": 2, "name": "Somewhere Else", "cast": "Ted Harrison, Jo Bloggs"}
        ]"""

        test.syncVod()

        assertThat(titlesWith("Ed Harris")).containsExactly("The Abyss")
    }

    @Test
    fun `a name matches however a provider spells its accents, case and spaces`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 1, "name": "Volver", "cast": "Penélope  Cruz"}]"""

        test.syncVod()

        assertThat(titlesWith("penelope cruz")).containsExactly("Volver")
    }

    @Test
    fun `a film whose cast changes on a refresh moves to its new actors' pages`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 1, "name": "Heat", "cast": "Al Pacino"}]"""
        test.syncVod()
        panel.replies["get_vod_streams"] = """[{"stream_id": 1, "name": "Heat", "cast": "Robert De Niro"}]"""

        test.syncVod()

        assertThat(titlesWith("Al Pacino")).isEmpty()
        assertThat(titlesWith("Robert De Niro")).containsExactly("Heat")
    }

    @Test
    fun `a person's shows follow their films, each best-rated first`() = runTest {
        panel.replies["get_vod_streams"] = """[
            {"stream_id": 1, "name": "Okay Film", "rating": "6", "cast": "Olivia Colman"},
            {"stream_id": 2, "name": "Great Film", "rating": "8", "director": "Olivia Colman"}
        ]"""
        panel.replies["get_series"] =
            """[{"series_id": 9, "name": "Broadchurch", "rating": "9", "cast": "Olivia Colman, David Tennant"}]"""

        test.syncVod()

        assertThat(titlesWith("Olivia Colman")).containsExactly("Great Film", "Okay Film", "Broadchurch").inOrder()
    }

    @Test
    fun `a film's cast found when it's opened puts it on their pages`() = runTest {
        panel.replies["get_vod_streams"] = """[{"stream_id": 1, "name": "Arrival", "genre": "Sci-Fi"}]"""
        test.syncVod()
        panel.replies["get_vod_info"] = """{"info": {"cast": "Amy Adams"}}"""

        test.catalog.movieDetail(test.db.movies().all().single().id)

        assertThat(titlesWith("Amy Adams")).containsExactly("Arrival")
    }

    @Test
    fun `a person's page looks them up in an index rather than reading every title`() {
        for (sql in listOf(MOVIES_WITH_PERSON, SERIES_WITH_PERSON)) {
            val plan = test.db.query(
                SimpleSQLiteQuery(
                    "EXPLAIN QUERY PLAN " + sql.replace(":nameKey", "?").replace(":limit", "?"),
                    arrayOf<Any>("ed harris", 40),
                ),
            ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(3)) } }

            assertThat(plan.filter { it.startsWith("SCAN") }).isEmpty()
        }
    }
}
