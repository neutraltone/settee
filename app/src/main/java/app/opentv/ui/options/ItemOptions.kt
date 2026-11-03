/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.options

import app.opentv.data.model.Programme
import app.opentv.data.repo.WatchStatus

/** One thing the options panel can do to the highlighted item. */
sealed interface ItemOption {
    data object PlayFromStart : ItemOption

    data object ShowInfo : ItemOption

    data object MarkWatched : ItemOption

    data object MarkUnwatched : ItemOption

    data object RemoveFromContinueWatching : ItemOption

    data object AddFavourite : ItemOption

    data object RemoveFavourite : ItemOption

    data object RemoveRecentSearch : ItemOption

    /** What only a channel offers; kept apart so each kind's labels stay short and checked by the compiler. */
    sealed interface ChannelAction : ItemOption

    data object Watch : ChannelAction

    data object HideChannel : ChannelAction

    data object OpenInExternalPlayer : ChannelAction

    data object RecordNow : ChannelAction

    data object StopRecording : ChannelAction

    /**
     * The [programme] on now when the panel opened. Carried rather than looked up again, so the series
     * set is the one the label named even if that programme has ended by the time it's chosen.
     */
    data class RecordWholeSeries(val programme: Programme) : ChannelAction

    data object ScheduleLater : ChannelAction

    /** One of the programmes Schedule a later programme lists. */
    data class LaterProgramme(val programme: Programme) : ChannelAction
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

    data class Episode(val status: WatchStatus) : OptionsFor {
        override fun options() = playFromStart(status) + marks(status)
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

    /**
     * A channel, however many Qualities it has: [recording] if any of them is, [programmeOnNow] what's
     * on it now, and [hasUpcoming] whether the guide lists anything later.
     */
    data class Channel(
        val favourite: Boolean,
        val recording: Boolean,
        val programmeOnNow: Programme?,
        val hasUpcoming: Boolean,
    ) : OptionsFor {
        override fun options() = buildList {
            add(ItemOption.Watch)
            add(favourite(favourite))
            add(ItemOption.HideChannel)
            add(ItemOption.OpenInExternalPlayer)
            add(if (recording) ItemOption.StopRecording else ItemOption.RecordNow)
            programmeOnNow?.let { add(ItemOption.RecordWholeSeries(it)) }
            if (hasUpcoming) add(ItemOption.ScheduleLater)
        }
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
