/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.directions

import androidx.compose.runtime.Composable

enum class Direction(val key: String, val label: String) {
    LAMPLIGHT("a_lamplight", "A: Lamplight"),
    HEARTH("b_hearth", "B: Hearth"),
    LISTINGS("c_listings", "C: Listings"),
    PICTURE_HOUSE("d_picture_house", "D: Picture House"),
    COMBINED("e_combined", "E: Combined"),
}

@Composable
fun DirectionScreen(direction: Direction, screen: Screen) {
    val base = when (screen) {
        Screen.GUIDE_CATEGORIES -> Screen.GUIDE
        Screen.SHOWS_CATEGORIES, Screen.MOVIES, Screen.MOVIES_CATEGORIES -> Screen.SHOWS
        else -> screen
    }
    when (direction) {
        Direction.LAMPLIGHT -> LamplightScreen(base)
        Direction.HEARTH -> HearthScreen(base)
        Direction.LISTINGS -> ListingsScreen(base)
        Direction.PICTURE_HOUSE -> PictureHouseScreen(base)
        Direction.COMBINED -> CombinedScreen(screen)
    }
}
