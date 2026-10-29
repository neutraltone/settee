/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import app.opentv.data.repo.WatchStatus

/** One thing the options panel can do to the highlighted item. */
enum class ItemOption { MarkWatched, MarkUnwatched, RemoveFromContinueWatching, AddFavourite, RemoveFavourite }

/**
 * What the options panel is open on, with the facts that decide which [ItemOption]s apply. Each
 * kind works out its own list, so the panel shows only what applies without knowing the kinds.
 */
sealed interface OptionsFor {
    fun options(): List<ItemOption>

    data class Movie(val status: WatchStatus, val favourite: Boolean) : OptionsFor {
        override fun options() = marks(status) + favourite(favourite)
    }

    data class Episode(val status: WatchStatus) : OptionsFor {
        override fun options() = marks(status)
    }

    /**
     * A Continue Watching card: a movie or episode not yet Watched, so never Mark unwatched
     * (removing the card is how to give up on it). [favourite] is its movie's or series', or null
     * when its title isn't in the catalogue to be made one.
     */
    data class ContinueWatching(val favourite: Boolean?) : OptionsFor {
        override fun options() =
            listOfNotNull(ItemOption.MarkWatched, ItemOption.RemoveFromContinueWatching, favourite?.let(::favourite))
    }

    data class Series(val favourite: Boolean) : OptionsFor {
        override fun options() = listOf(favourite(favourite))
    }

    data class Channel(val favourite: Boolean) : OptionsFor {
        override fun options() = listOf(favourite(favourite))
    }
}

// In Progress offers both: Watched to finish it, Unwatched to start again from the beginning.
private fun marks(status: WatchStatus) = buildList {
    if (status != WatchStatus.Watched) add(ItemOption.MarkWatched)
    if (status != WatchStatus.Unwatched) add(ItemOption.MarkUnwatched)
}

private fun favourite(isFavourite: Boolean) = if (isFavourite) ItemOption.RemoveFavourite else ItemOption.AddFavourite
