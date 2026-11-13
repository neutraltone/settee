/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Episode
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.model.StreamKind
import app.opentv.player.DecoderList
import app.opentv.player.playsQualityRanked
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which Version of a Movie or Episode plays. A provider lists each of a title's Versions as a row of its
 * own in its own category (`4K-TOP - Superbad`, `EN - Superbad`), and the prefixes differ, so names don't
 * join them and browsing one category shows only one. What joins them is their TMDB id (ADR 0007).
 *
 * What plays is the Version the Profile last played or chose, whichever was later, when this device can
 * play it. Otherwise it's the best Quality this device can play in its language, with cam copies last.
 * Settee never switches language on its own: when only another language plays, the Plan says so and
 * the viewer is asked.
 */
class VodQualities(
    db: OpenTvDatabase,
    catalog: CatalogRepository,
    private val decoders: DecoderList,
    private val profileSettings: ProfileSettings,
    // Normalised, as an older Android names Indonesian `in` and Hebrew `iw`.
    private val deviceLanguage: () -> String = { baseLanguage(Locale.getDefault().language) ?: "" },
    versionIds: VersionIds = VersionIds.BY_TMDB,
) {
    private val versions = TitleVersionLookup(db, catalog, decoders, profileSettings.activeProfileId, versionIds)

    /** One Version: what plays it and what a viewer calls its Quality, which may be nothing. */
    data class Quality(val key: MediaKey, val label: String)

    /**
     * [start] is what plays, and [lower] what to step down to, best first. [skipped] is what the title
     * opened was called, when this device isn't going to try it at all. [otherLanguages] are the
     * Versions that would play when none in the device's language does: [start] is then the one opened,
     * and the viewer is asked before any of them plays, in [otherLanguage] if their names say which.
     */
    data class Plan(
        val start: Quality,
        val lower: List<Quality> = emptyList(),
        val skipped: String? = null,
        val otherLanguages: List<Quality> = emptyList(),
        val otherLanguage: String? = null,
    ) {
        /** Nothing of the title plays on this device, in any language: Play can only fail. */
        fun playsNothing(decoders: DecoderList) =
            otherLanguages.isEmpty() && lower.isEmpty() && !decoders.playsQualityRanked(qualityRankOf(start.label))
    }

    /**
     * One entry in the Quality pill: a Version, standing for every other that differs from it in nothing
     * a viewer would notice. [traits] leave out the device's own language, which a shelf only names when
     * the others are in it too. [playing] is the one that plays now.
     */
    data class Choice(val key: MediaKey, val traits: VersionTraits, val playable: Boolean, val playing: Boolean)

    /** The Plan for what was opened. Anything without Versions of this kind just plays. */
    suspend fun planFor(opened: MediaKey): Plan = versions.of(opened)?.let { plan(it) } ?: Plan(Quality(opened, ""))

    /**
     * The Quality pill's entries for what was opened, best Quality first: one per way its Versions
     * differ. One entry, or none, means there's nothing to choose and no pill.
     */
    suspend fun choicesFor(opened: MediaKey): List<Choice> =
        versions.of(opened)?.let { choicesIn(it, plan(it)) }.orEmpty()

    /** The Plan and the Quality pill's entries together, from one look at the title's Versions, for a page. */
    suspend fun pageFor(opened: MediaKey): Pair<Plan, List<Choice>> {
        val title = versions.of(opened) ?: return Plan(Quality(opened, "")) to emptyList()
        val plan = plan(title)
        return plan to choicesIn(title, plan)
    }

    private fun choicesIn(title: TitleVersionLookup.Versions, plan: Plan): List<Choice> {
        val playing = plan.start.key
        val language = deviceLanguage()
        return title.all
            .sortedWith(
                compareBy<Candidate> { it.traits.cam }
                    .thenByDescending { it.traits.qualityRank }
                    .thenByDescending { it.traits.inLanguage(language) }
                    .thenByDescending { it.quality.key == playing },
            )
            .groupBy { it.traits.copy(language = it.traits.language.takeUnless { code -> code == language }) }
            .map { (traits, alike) ->
                val shown = alike.firstOrNull { it.quality.key == playing } ?: alike.first()
                Choice(
                    key = shown.quality.key,
                    traits = traits,
                    playable = alike.any { decoders.playsQualityRanked(it.traits.qualityRank) },
                    playing = alike.any { it.quality.key == playing },
                )
            }
    }

    /**
     * The viewer chose [version] in the Quality pill, or agreed to a Version in another language: it's
     * what plays for this Profile from now on, until it plays another. A title with no TMDB id has no
     * other Versions to choose between here, so nothing is kept.
     */
    suspend fun choose(version: MediaKey) {
        versions.chosenAs(version)?.let { (title, id) -> profileSettings.chooseVersion(title, id) }
    }

    private suspend fun plan(title: TitleVersionLookup.Versions): Plan {
        val opened = title.opened
        val playable = title.all.filter { decoders.playsQualityRanked(it.traits.qualityRank) }
        val language = deviceLanguage()
        val usual = playable.filter { it.traits.inLanguage(language) }.sortedWith(
            compareBy<Candidate> { it.traits.cam }
                .thenByDescending { it.traits.qualityRank }
                // The same shelf next: a provider's shelves differ in more than their names say.
                .thenByDescending { it.shelf != null && it.shelf == opened.shelf }
                .thenByDescending { it === opened },
        )
        val preferred = preferredOf(playable, title.key)
        val ordered = listOfNotNull(preferred) + usual.filter { it !== preferred }
        return when {
            ordered.isEmpty() && playable.isNotEmpty() -> {
                val others = playable.sortedByDescending { it.traits.qualityRank }
                Plan(
                    opened.quality,
                    otherLanguages = others.map {
                        it.quality
                    },
                    otherLanguage = others.first().traits.language,
                )
            }
            ordered.isEmpty() -> Plan(opened.quality)
            else -> Plan(
                start = ordered.first().quality,
                lower = ordered.drop(1).map { it.quality },
                skipped = opened.quality.label.takeUnless { opened in playable },
            )
        }
    }

    /** What this Profile last played or chose of the title, whichever was later, if it plays here. */
    private suspend fun preferredOf(playable: List<Candidate>, title: String?): Candidate? {
        val lastPlayed = playable.filter { it.playedAt != null }.maxByOrNull { it.playedAt ?: 0 }
        val choice = title?.let { profileSettings.chosenVersion(it) }
        val chosen = choice?.let { c -> playable.firstOrNull { it.version == c.version } }
        return when {
            chosen == null -> lastPlayed
            choice.atMillis >= (lastPlayed?.playedAt ?: 0) -> chosen
            else -> lastPlayed
        }
    }
}

/** Finds the Versions of what was opened, with what [VodQualities] weighs them by. */
private class TitleVersionLookup(
    private val db: OpenTvDatabase,
    private val catalog: CatalogRepository,
    private val decoders: DecoderList,
    private val activeProfileId: StateFlow<Long>,
    private val versionIds: VersionIds,
) {
    /** [opened] among [all] a title's Versions; [key] names the title for a chosen Version, if it has an id. */
    class Versions(val opened: Candidate, val all: List<Candidate>, val key: String?)

    suspend fun of(opened: MediaKey): Versions? = when (opened) {
        is MediaKey.Movie -> movie(opened)
        is MediaKey.Episode -> episode(opened)
        else -> null
    }

    /** The title [version] is a Version of, and how that Version is named in a choice. */
    suspend fun chosenAs(version: MediaKey): Pair<String, String>? {
        val found = when (version) {
            is MediaKey.Movie -> db.movies().byId(version.movieId)
                ?.let { Triple(knownTmdbId(it.tmdbId)?.let { id -> "movie:$id" }, it.sourceId, it.streamId) }
            is MediaKey.Episode -> db.episodes().byId(version.episodeId)
                ?.let { db.series().bySeriesId(it.sourceId, it.seriesId) }
                ?.let { Triple(knownTmdbId(it.tmdbId)?.let { id -> "series:$id" }, it.sourceId, it.seriesId) }
            else -> null
        }
        val title = found?.first ?: return null
        return versionId(found.second, found.third)?.let { title to it }
    }

    private suspend fun movie(opened: MediaKey.Movie): Versions? {
        val movie = db.movies().byId(opened.movieId) ?: return null
        val tmdbId = knownTmdbId(movie.tmdbId)
        val rows = (listOf(movie) + db.versionsOf(movie, versionIds)).distinctBy { it.id }
        val profile = activeProfileId.value
        val candidates = rows.map { row ->
            val key = MediaKey.Movie(row.id)
            candidate(
                key = key,
                name = row.name,
                category = row.categoryId?.let { db.categories().nameOf(row.sourceId, it, StreamKind.MOVIE) },
                version = versionId(row.sourceId, row.streamId),
                playedAt = db.positions().get(profile, key.stored)?.updatedAtMillis,
            )
        }
        return Versions(candidates.first(), candidates, tmdbId?.let { "movie:$it" })
    }

    private suspend fun episode(opened: MediaKey.Episode): Versions? {
        val episode = db.episodes().byId(opened.episodeId)
        val series = episode?.let { db.series().bySeriesId(it.sourceId, it.seriesId) }
        if (episode == null || series == null) return null
        // Read once for every Version, rather than once each.
        val played = db.positions().ofKind(activeProfileId.value, EPISODE_PREFIX)
        val self = seriesCandidate(series, opened, played)
        return Versions(
            self,
            listOf(self) + copiesOf(episode, series, played),
            knownTmdbId(series.tmdbId)?.let {
                "series:$it"
            },
        )
    }

    private suspend fun copiesOf(episode: Episode, series: Series, played: List<PlaybackPosition>): List<Candidate> {
        // A series' episodes are fetched when its page is opened, so another Version's may never have
        // been. Waiting on the provider is only worth it when this device won't try the one opened.
        val fetch = !decoders.playsName(series.name)
        val copies = db.versionsOf(series, versionIds).filter { it.id != series.id }
        return copies.filter { decoders.playsName(it.name) }.mapNotNull { copy ->
            sameEpisodeIn(copy, episode, fetch)?.let { seriesCandidate(copy, MediaKey.Episode(it), played) }
        }
    }

    private suspend fun seriesCandidate(
        series: Series,
        key: MediaKey.Episode,
        played: List<PlaybackPosition>,
    ): Candidate {
        val episodeKeys = db.episodes().forSeries(series.sourceId, series.seriesId)
            .map { MediaKey.Episode(it.id).stored }.toSet()
        val playedAt = played.filter { it.mediaKey in episodeKeys }.maxOfOrNull { it.updatedAtMillis }
        return candidate(
            key = key,
            name = series.name,
            category = series.categoryId?.let { db.categories().nameOf(series.sourceId, it, StreamKind.SERIES) },
            version = versionId(series.sourceId, series.seriesId),
            playedAt = playedAt,
        )
    }

    private suspend fun sameEpisodeIn(copy: Series, episode: Episode, fetch: Boolean): Long? {
        suspend fun stored() = db.episodes().forSeries(copy.sourceId, copy.seriesId)
            .firstOrNull { it.season == episode.season && it.episodeNumber == episode.episodeNumber }?.id

        val source = if (fetch && stored() == null) db.sources().byId(copy.sourceId) else null
        source?.let { withTimeoutOrNull(FETCH_LIMIT_MILLIS) { catalog.ensureEpisodes(it, copy.seriesId) } }
        return stored()
    }

    /** A Version by its source's name and the provider's id, as a Favourite is, so it syncs (ADR 0004). */
    private suspend fun versionId(sourceId: Long, providerId: String): String? =
        db.sources().byId(sourceId)?.name?.let { "$providerId|$it" }

    private companion object {
        const val FETCH_LIMIT_MILLIS = 10_000L
        const val EPISODE_PREFIX = "ep:"
    }
}

/** One Version as the Plan weighs it: what plays it, what it is, and when this Profile last played it. */
private class Candidate(
    val quality: VodQualities.Quality,
    val traits: VersionTraits,
    val shelf: String?,
    val version: String?,
    val playedAt: Long?,
)

private fun candidate(key: MediaKey, name: String, category: String?, version: String?, playedAt: Long?): Candidate {
    val traits = versionTraits(name, category)
    val shelf = SHELF.find(name)?.groupValues?.get(1)?.lowercase()
    return Candidate(VodQualities.Quality(key, traits.qualityLabel), traits, shelf, version, playedAt)
}

/** What's before the title in `4K-EN - Superbad` and `EN - Superbad`: the same shelf, EN. */
private val SHELF = Regex("""^\s*(?:[48]K-)?(.+?)\s+-\s""", RegexOption.IGNORE_CASE)

/** Whether a Version could play here, going by the Quality its name promises. */
private fun DecoderList.playsName(name: String) = playsQualityRanked(versionTraits(name, null).qualityRank)

/** The rank of a Quality's label, as [VersionTraits.qualityLabel] writes it. */
private fun qualityRankOf(label: String) = versionTraits(label, null).qualityRank
