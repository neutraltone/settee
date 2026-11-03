/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import app.opentv.data.repo.WatchStatus

/** One thing the options panel can do to the highlighted item. */
enum class ItemOption {
    PlayFromStart,
    ShowInfo,
    MarkWatched,
    MarkUnwatched,
    MarkWatchedUpToHere,
    MarkSeasonWatched,
    MarkSeasonUnwatched,
    RemoveFromContinueWatching,
    AddFavourite,
    RemoveFavourite,
    RemoveRecentSearch,
}

/**
 * What the options panel is open on, with the facts that decide which [ItemOption]s apply. Each
 * kind works out its own list, so the panel shows only what applies without knowing the kinds.
 */
sealed interface OptionsFor {
    fun options(): List<ItemOption>

    data class Movie(val status: WatchStatus, val favourite: Boolean) : OptionsFor {
        override fun options() = playFromStart(status) + ItemOption.ShowInfo + marks(status) + favourite(favourite)
    }

    /**
     * [earlierToMark] is whether marking up to here would mark an earlier episode; when it wouldn't,
     * up to here would only repeat Mark watched, which sits beside it.
     */
    data class Episode(val status: WatchStatus, val earlierToMark: Boolean) : OptionsFor {
        override fun options() = playFromStart(status) + buildList {
            if (status != WatchStatus.Watched) add(ItemOption.MarkWatched)
            if (earlierToMark) add(ItemOption.MarkWatchedUpToHere)
            if (status != WatchStatus.Unwatched) add(ItemOption.MarkUnwatched)
        }
    }

    /** A season header, with the status of each of the season's [statuses]. */
    data class Season(val statuses: List<WatchStatus>) : OptionsFor {
        override fun options() = buildList {
            if (statuses.any { it != WatchStatus.Watched }) add(ItemOption.MarkSeasonWatched)
            if (statuses.any { it != WatchStatus.Unwatched }) add(ItemOption.MarkSeasonUnwatched)
        }
    }

    /**
     * A Continue Watching card: a movie or episode not yet Watched, so never Mark unwatched
     * (removing the card is how to give up on it). [status] is that movie's or episode's, and
     * [favourite] its movie's or series'. A title no longer [inCatalogue] has nothing to play from
     * the start, no page to show and nothing to make a Favourite.
     */
    data class ContinueWatching(val status: WatchStatus, val inCatalogue: Boolean, val favourite: Boolean) :
        OptionsFor {
        override fun options() = if (inCatalogue) {
            playFromStart(status) + ItemOption.ShowInfo + cardOptions + favourite(favourite)
        } else {
            cardOptions
        }

        private val cardOptions get() = listOf(ItemOption.MarkWatched, ItemOption.RemoveFromContinueWatching)
    }

    data class Series(val favourite: Boolean) : OptionsFor {
        override fun options() = listOf(ItemOption.ShowInfo, favourite(favourite))
    }

    data class Channel(val favourite: Boolean) : OptionsFor {
        override fun options() = listOf(favourite(favourite))
    }

    data object RecentSearch : OptionsFor {
        override fun options() = listOf(ItemOption.RemoveRecentSearch)
    }
}

private fun playFromStart(status: WatchStatus) =
    if (status is WatchStatus.InProgress) listOf(ItemOption.PlayFromStart) else emptyList()

// In Progress offers both: Watched to finish it, Unwatched to start again from the beginning.
private fun marks(status: WatchStatus) = buildList {
    if (status != WatchStatus.Watched) add(ItemOption.MarkWatched)
    if (status != WatchStatus.Unwatched) add(ItemOption.MarkUnwatched)
}

private fun favourite(isFavourite: Boolean) = if (isFavourite) ItemOption.RemoveFavourite else ItemOption.AddFavourite
