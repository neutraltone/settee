/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

/** Decides whether the What's new dialog shows on this launch, and what it says. */
object WhatsNew {

    /**
     * The notes to show, newest first. Empty means show nothing and store [installed] straight away.
     *
     * With no stored version, Android's install and update times tell a fresh install (equal) from an
     * upgrade over a build that predates this feature (different). That older build's version is
     * unknown, so only the installed version's notes show rather than guessing at a range.
     */
    fun notesToShow(
        installed: String,
        lastSeen: String?,
        firstInstallTime: Long,
        lastUpdateTime: Long,
        changelog: String,
    ): List<VersionNotes> = when {
        lastSeen != null -> ReleaseNotes.fromChangelog(changelog, after = lastSeen, upTo = installed)
        firstInstallTime == lastUpdateTime -> emptyList()
        else -> installedNotes(installed, changelog)
    }

    /** Just [installed]'s own notes, as About's What's new item shows them. Empty if it has none. */
    fun installedNotes(installed: String, changelog: String): List<VersionNotes> =
        ReleaseNotes.fromChangelog(changelog, after = null, upTo = installed)
            .filter { !UpdateChecker.isNewer(installed, it.version) }
}
