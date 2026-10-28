/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

/** The only headings a viewer sees, in the order they appear. Every other changelog section is dropped. */
enum class NotesHeading { HeadsUp, NewFeatures, BugFixes, PerformanceImprovements }

data class NotesSection(val heading: NotesHeading, val entries: List<String>)

/** One release's notes, as a viewer is told them. */
data class VersionNotes(val version: String, val sections: List<NotesSection>)

/**
 * Turns release-please's `CHANGELOG.md`, written for developers, into what a viewer is told
 * after an update.
 */
object ReleaseNotes {

    // release-please writes `## [0.3.0](compare-url) (date)`; the hand-written first entry is `## 0.1.0`.
    private val versionHeading = Regex("""^##\s+\[?v?(\d+(?:\.\d+)*)]?.*$""")

    // Any heading ends the section above it, so a `## What's Changed` GitHub appends is never shown.
    private val sectionHeading = Regex("""^#{1,6}\s+(.+)$""")
    private val bullet = Regex("""^[*-]\s+(.+)$""")

    /**
     * The versions after [after] (exclusive) up to [upTo] (inclusive), newest first. A version
     * with nothing a viewer would notice is left out, so an empty list means there is nothing to tell.
     */
    fun fromChangelog(changelog: String, after: String?, upTo: String): List<VersionNotes> = versionBlocks(changelog)
        .filter { (version, _) ->
            !UpdateChecker.isNewer(version, upTo) && (after == null || UpdateChecker.isNewer(version, after))
        }
        .map { (version, lines) -> VersionNotes(version, sections(lines)) }
        .filter { it.sections.isNotEmpty() }

    /**
     * The notes in a GitHub release body: release-please's changelog entry without its version
     * heading, followed by the release workflow's signing footer after a `---`. Null when nothing
     * in it is viewer-facing.
     */
    fun fromReleaseBody(body: String, version: String): VersionNotes? {
        val notes = body.lines().takeWhile { it.trim() != "---" }
        return VersionNotes(version, sections(notes)).takeIf { it.sections.isNotEmpty() }
    }

    private fun versionBlocks(changelog: String): List<Pair<String, List<String>>> {
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in changelog.lines()) {
            val version = versionHeading.matchEntire(line.trim())?.groupValues?.get(1)
            if (version != null) {
                blocks += version to mutableListOf()
            } else {
                blocks.lastOrNull()?.second?.add(line)
            }
        }
        return blocks
    }

    private fun sections(lines: List<String>): List<NotesSection> {
        val entries = linkedMapOf<NotesHeading, MutableList<String>>()
        var current: NotesHeading? = null
        for (raw in lines) {
            val line = raw.trim()
            val heading = sectionHeading.matchEntire(line)?.groupValues?.get(1)
            val entry = bullet.matchEntire(line)?.groupValues?.get(1)
            val section = current?.let { entries.getOrPut(it) { mutableListOf() } }
            when {
                heading != null -> current = viewerHeading(heading)
                section == null || line.isEmpty() -> Unit
                // An indented line carries on the entry above: a wrapped sentence, or a sub-point.
                raw.first().isWhitespace() && section.isNotEmpty() ->
                    section[section.lastIndex] += if (entry != null) "; $entry" else " $line"
                entry != null -> section += entry
            }
        }
        // release-please also lists a breaking commit under its own type; Heads up is where it belongs.
        val cleaned = entries.mapValues { (_, list) -> list.map(::clean) }
        val headsUp = cleaned[NotesHeading.HeadsUp].orEmpty().toSet()
        return cleaned.entries.sortedBy { it.key }
            .map { (heading, list) ->
                NotesSection(heading, if (heading == NotesHeading.HeadsUp) list else list.filterNot { it in headsUp })
            }
            .filter { it.entries.isNotEmpty() }
    }

    private val scopePrefix = Regex("""^\*\*[^*]+:\*\*\s*""")

    // The trailing `([abc1234](commit-url))`, and the `, closes [#12](issue-url)` release-please adds.
    // Only links labelled with a commit hash or an issue number go: a link in the sentence keeps its words.
    private val trailingLinks = Regex("""(\s*\(\[[0-9a-f]{7,40}]\([^)]*\)\)|\s*,?\s*closes\s+\[#\d+]\([^)]*\))+$""")
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")

    private fun clean(entry: String): String = entry
        .replace(scopePrefix, "")
        .replace(trailingLinks, "")
        .replace(link, "$1")
        .trim()

    // release-please's default names, plus the shorter ones this repo's changelog-sections config uses.
    private fun viewerHeading(name: String): NotesHeading? {
        val n = name.lowercase()
        return when {
            "breaking" in n -> NotesHeading.HeadsUp
            n == "features" -> NotesHeading.NewFeatures
            n == "bug fixes" -> NotesHeading.BugFixes
            n.startsWith("performance") -> NotesHeading.PerformanceImprovements
            else -> null
        }
    }
}
