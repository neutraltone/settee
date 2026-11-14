/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import app.opentv.ui.LocalShellCovered
import app.opentv.ui.LocalShellReturn
import app.opentv.ui.ShellReturn
import app.opentv.ui.design.HighlightMemory
import app.opentv.ui.design.LocalHighlightMemory

/*
 * The main screen stays built under the screens opened over it (#192). Navigation disposes a
 * destination while another covers it, so Back to the main screen built it again: on a Fire TV
 * Stick, about 345 ms for Home even with its rows ready, over the 300 ms budget for a new screen.
 *
 * So the main screen is drawn beside the NavHost rather than in it, and its route there is left
 * empty. Navigation `dialog` destinations, which keep the screen under them, were rejected: each opens
 * a window of its own, with its own focus and Back, which a TV remote and the screenshot tests both
 * trip over. Turning Settings and the rest into overlays the shell draws would work too, but means
 * rewriting navigation for one screen's sake.
 */

/** The main screen's place in the NavHost: empty, since [KeptShell] draws the main screen. */
fun NavGraphBuilder.mainShellRoute() = composable(Routes.MAIN) {}

/**
 * Draws [shell] (the main screen) under [navHost], owned by the [Routes.MAIN] back stack entry as it
 * would be inside the NavHost: the same view models, saved state and tab ([shellTab]), and the same
 * lifecycle, so the Live Preview and the guide's listings still stop while it's covered.
 *
 * Covered by any screen but a player, it stays built without being placed: nothing of it is drawn
 * and the remote can't reach it, and Back finds it as it was, the highlight where it was left. Under a
 * player it's let go, as before, so Home waits for rows that know what was just watched rather than
 * show a card about to move or go (see [KeepHomeLive]).
 */
@Composable
fun KeptShell(nav: NavHostController, shell: @Composable (NavBackStackEntry) -> Unit, navHost: @Composable () -> Unit) {
    // NavController's whole back stack is library-only API, so the main screen's entry is looked up
    // whenever the top one changes, which every change to the stack does.
    val top by nav.currentBackStackEntryAsState()
    val main = remember(top) { top?.let { runCatching { nav.getBackStackEntry(Routes.MAIN) }.getOrNull() } }
    val saveable = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
        if (main != null && !isPlayerRoute(top?.destination?.route)) {
            key(main.id) { ShellLayer(main, covered = top != main, saveable, shell) }
        }
        navHost()
    }
    ForgetGoneEntries(main?.id, saveable)
}

/** The live player and the movie and episode player, which the main screen isn't kept under. */
internal fun isPlayerRoute(route: String?): Boolean = route == Routes.PLAYER || route == Routes.VOD_PLAYER

/**
 * Drops what the main screen saved for an entry that has gone (Setup finished, a new Profile's
 * navigation), as the NavHost does for its own. After composition, so a shell leaving in the same
 * frame has already saved into [saveable] what this removes.
 */
@Composable
private fun ForgetGoneEntries(mainId: String?, saveable: SaveableStateHolder) {
    val known = remember { mutableSetOf<String>() }
    LaunchedEffect(mainId) {
        known.filterNot { it == mainId }.forEach {
            saveable.removeState(it)
            known.remove(it)
        }
        mainId?.let(known::add)
    }
}

@Composable
private fun ShellLayer(
    entry: NavBackStackEntry,
    covered: Boolean,
    saveable: SaveableStateHolder,
    shell: @Composable (NavBackStackEntry) -> Unit,
) {
    val lifecycle by entry.lifecycle.currentStateFlow.collectAsState()
    // Placed while it's on top, and while the screen over it fades in or out, as it was in the NavHost.
    val placed = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val coveredNow by rememberUpdatedState(covered)
    val anywhere = remember { FocusRequester() }
    val memory = remember { HighlightMemory() }
    val shellReturn = remember { ShellReturn() }
    var hasFocus by remember { mutableStateOf(false) }
    var wasCovered by remember { mutableStateOf(covered) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(covered) {
        if (covered) {
            wasCovered = true
            // Every screen takes the highlight as it opens. One that didn't would leave it on the
            // hidden shell, where OK would still press whatever has it. [memory] still knows it.
            withFrameNanos {}
            withFrameNanos {}
            if (hasFocus) focusManager.clearFocus(force = true)
        } else if (wasCovered) {
            wasCovered = false
            returnHighlight(memory, shellReturn, hasFocus = { hasFocus }, anywhere)
        }
    }
    entry.LocalOwnersProvider(saveable) {
        CompositionLocalProvider(
            LocalShellCovered provides covered,
            LocalShellReturn provides shellReturn,
            LocalHighlightMemory provides memory,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .placedOnlyIf(placed)
                    .onFocusChanged { hasFocus = it.hasFocus }
                    // While the screen over it fades in, the shell is still placed: keep the remote out.
                    .focusProperties { onEnter = { if (coveredNow) cancelFocusChange() } }
                    .focusRequester(anywhere)
                    .focusGroup(),
            ) {
                shell(entry)
            }
        }
    }
}

/**
 * Back on the main screen: the highlight goes to the item that had it as the screen opened over it.
 * When that's gone (the shell was built again after a player let it go, or the card went), the tab
 * on show gets a few frames to place it ([ShellReturn]), and failing that it goes anywhere in the
 * shell rather than nowhere.
 */
private suspend fun returnHighlight(
    memory: HighlightMemory,
    shellReturn: ShellReturn,
    hasFocus: () -> Boolean,
    anywhere: FocusRequester,
) {
    if (memory.restore()) return
    shellReturn.asked = true
    repeat(RETURN_FRAMES) { withFrameNanos {} }
    shellReturn.asked = false
    if (!hasFocus()) runCatching { anywhere.requestFocus() }
}

private const val RETURN_FRAMES = 3

/**
 * Measured, so its lazy lists keep their items, but placed only when [placed]: an unplaced node is
 * neither drawn nor found by the remote's moves.
 */
private fun Modifier.placedOnlyIf(placed: Boolean) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (placed) placeable.place(0, 0) }
}
