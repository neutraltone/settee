/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.text.Normalizer

// A person's page reads these rather than searching the cast and director text with LIKE, which
// scans every title on each open and finds "Ed Harris" in "Ted Harrison". A row per person a title
// credits, under their folded name, is an index lookup that only finds that name.
//
// Rows go with their title (the foreign key cascades) and are rewritten only when its cast or
// director changes. Not an FTS table: Room keeps those in step with triggers on every write, and a
// catalogue refresh rewrites every title each time, so a 30,000-film list would reindex them all.

/** Someone a movie credits: [directed] it, or acts in it. [nameKey] is their name, [personKey]ed. */
@Entity(
    tableName = "movie_credits",
    primaryKeys = ["movieId", "directed", "nameKey"],
    foreignKeys = [
        ForeignKey(
            entity = Movie::class,
            parentColumns = ["id"],
            childColumns = ["movieId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("nameKey")],
)
data class MovieCredit(val movieId: Long, val directed: Boolean, val nameKey: String)

/** Someone a series' cast lists. Series have no director. See [MovieCredit]. */
@Entity(
    tableName = "series_credits",
    primaryKeys = ["seriesId", "nameKey"],
    foreignKeys = [
        ForeignKey(
            entity = Series::class,
            parentColumns = ["id"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("nameKey")],
)
data class SeriesCredit(val seriesId: Long, val nameKey: String)

/** Splits a provider's cast or director text: comma-separated mostly, some panels use | or ;. */
private val NAME_SEPARATORS = Regex("[,|;]")

private val ACCENTS = Regex("\\p{Mn}+")

private val SPACES = Regex("\\s+")

/**
 * A name as credits are filed and looked up under: lower case, accents and extra spaces gone, so
 * "Penélope Cruz" from TMDB and "penelope  cruz" from a panel are one person. Blank for no name.
 */
fun personKey(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
    .replace(ACCENTS, "")
    .replace(SPACES, " ")
    .trim()
    .lowercase()

/** Every person in a provider's cast or director [names], [personKey]ed. */
fun personKeys(names: String?): Set<String> =
    names?.split(NAME_SEPARATORS)?.map(::personKey)?.filterTo(LinkedHashSet()) { it.isNotEmpty() }.orEmpty()

/** The [MovieCredit] rows for a movie with this [cast] and [director]. */
fun movieCredits(movieId: Long, cast: String?, director: String?): List<MovieCredit> =
    personKeys(cast).map { MovieCredit(movieId, directed = false, nameKey = it) } +
        personKeys(director).map { MovieCredit(movieId, directed = true, nameKey = it) }

/** The [SeriesCredit] rows for a series with this [cast]. */
fun seriesCredits(seriesId: Long, cast: String?): List<SeriesCredit> =
    personKeys(cast).map { SeriesCredit(seriesId, nameKey = it) }

/** Who a title credits, as last saved: what [MovieCredit]s are rewritten from when it changes. */
data class CreditedTitle(val id: Long, val cast: String?, val director: String?)
