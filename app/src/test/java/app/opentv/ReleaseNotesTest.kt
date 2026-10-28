/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.update.NotesHeading
import app.opentv.update.NotesSection
import app.opentv.update.ReleaseNotes
import app.opentv.update.VersionNotes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The changelog as release-please writes it, turned into what a viewer is told after an update. */
class ReleaseNotesTest {

    private val changelog = """
        # Changelog

        Settee is based on OpenTV 0.11.8; everything before this is in [OpenTV's changelog](https://github.com/opentvproject/opentv/blob/v0.11.8/CHANGELOG.md).

        ## [0.4.0](https://github.com/neutraltone/settee/compare/v0.3.0...v0.4.0) (2026-12-01)


        ### Features

        * **#20:** pick up where you left off on any series from Home ([aaaaaaa](https://github.com/neutraltone/settee/commit/aaaaaaa))

        ## [0.3.0](https://github.com/neutraltone/settee/compare/v0.2.0...v0.3.0) (2026-11-15)


        ### Bug Fixes

        * **#12:** the guide no longer jumps back to the top after a refresh ([bbbbbbb](https://github.com/neutraltone/settee/commit/bbbbbbb))

        ## [0.2.0](https://github.com/neutraltone/settee/compare/v0.1.0...v0.2.0) (2026-10-28)


        ### Features

        * **#9:** episodes show how far through them you are ([ccccccc](https://github.com/neutraltone/settee/commit/ccccccc))

        ## 0.1.0

        - **Settee.** The first release under the new name.
    """.trimIndent()

    @Test
    fun `shows the versions after the last-seen one up to the installed one`() {
        val notes = ReleaseNotes.fromChangelog(changelog, after = "0.2.0", upTo = "0.3.0")

        assertThat(notes.map { it.version }).containsExactly("0.3.0")
    }

    @Test
    fun `versions skipped by a bigger jump all show, newest first`() {
        val notes = ReleaseNotes.fromChangelog(changelog, after = "0.1.0", upTo = "0.4.0")

        assertThat(notes.map { it.version }).containsExactly("0.4.0", "0.3.0", "0.2.0").inOrder()
    }

    @Test
    fun `release-please headings become the viewer's headings, breaking changes first`() {
        val release = """
            ## [1.0.0](https://github.com/neutraltone/settee/compare/v0.4.0...v1.0.0) (2027-01-01)


            ### ⚠ BREAKING CHANGES

            * **#30:** add your providers again after this update

            ### Features

            * **#31:** a new thing

            ### Bug Fixes

            * **#32:** a fixed thing

            ### Performance Improvements

            * **#33:** a faster thing
        """.trimIndent()

        val notes = ReleaseNotes.fromChangelog(release, after = "0.4.0", upTo = "1.0.0").single()

        assertThat(notes.sections.map { it.heading }).containsExactly(
            NotesHeading.HeadsUp,
            NotesHeading.NewFeatures,
            NotesHeading.BugFixes,
            NotesHeading.PerformanceImprovements,
        ).inOrder()
    }

    @Test
    fun `sections a viewer would not care about are dropped`() {
        val release = """
            ## [0.5.0](https://github.com/neutraltone/settee/compare/v0.4.0...v0.5.0) (2027-01-01)


            ### docs

            * **#8:** add Fire TV install steps

            ### Bug fixes

            * **#40:** subtitles stay on after a channel change

            ### Code Refactoring

            * **#41:** split the player

            ### chore

            * bump dependencies
        """.trimIndent()

        val notes = ReleaseNotes.fromChangelog(release, after = "0.4.0", upTo = "0.5.0").single()

        assertThat(notes.sections.map { it.heading }).containsExactly(NotesHeading.BugFixes)
    }

    @Test
    fun `entries lose the issue prefix and the commit link`() {
        val notes = ReleaseNotes.fromChangelog(changelog, after = "0.3.0", upTo = "0.4.0").single()

        assertThat(notes.sections).containsExactly(
            NotesSection(NotesHeading.NewFeatures, listOf("pick up where you left off on any series from Home")),
        )
    }

    @Test
    fun `a range with nothing a viewer would notice has no notes`() {
        val docsOnly = """
            ## [0.2.0](https://github.com/neutraltone/settee/compare/v0.1.0...v0.2.0) (2026-10-28)


            ### docs

            * **#8:** add Fire TV install steps ([4dfb10e](https://github.com/neutraltone/settee/commit/4dfb10e))

            ## 0.1.0

            - **Settee.** The first release under the new name.
        """.trimIndent()

        assertThat(ReleaseNotes.fromChangelog(docsOnly, after = "0.1.0", upTo = "0.2.0")).isEmpty()
    }

    @Test
    fun `a GitHub release body gives the same notes as the changelog`() {
        val body = """
            ### Features

            * **#20:** pick up where you left off on any series from Home ([aaaaaaa](https://github.com/neutraltone/settee/commit/aaaaaaa))

            ### docs

            * **#21:** explain the remote's shortcuts ([ddddddd](https://github.com/neutraltone/settee/commit/ddddddd))


            ---
            signed: yes
            certificate SHA-256: 0000

            Built by GitHub Actions from the tagged commit — the workflow is public, so anyone can verify this APK against the source.

            ---
        """.trimIndent()

        assertThat(ReleaseNotes.fromReleaseBody(body, version = "0.4.0")).isEqualTo(
            VersionNotes(
                "0.4.0",
                listOf(
                    NotesSection(
                        NotesHeading.NewFeatures,
                        listOf("pick up where you left off on any series from Home"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a breaking change shows once, under Heads up`() {
        val release = """
            ### ⚠ BREAKING CHANGES

            * **#30:** add your providers again after this update

            ### Features

            * **#30:** add your providers again after this update ([eeeeeee](https://github.com/neutraltone/settee/commit/eeeeeee))
            * **#31:** a new thing ([fffffff](https://github.com/neutraltone/settee/commit/fffffff))
        """.trimIndent()

        assertThat(ReleaseNotes.fromReleaseBody(release, version = "1.0.0")?.sections).containsExactly(
            NotesSection(NotesHeading.HeadsUp, listOf("add your providers again after this update")),
            NotesSection(NotesHeading.NewFeatures, listOf("a new thing")),
        ).inOrder()
    }

    @Test
    fun `an entry's indented lines stay part of it`() {
        val release = """
            ### ⚠ BREAKING CHANGES

            * **#30:** your favourites are cleared by this update,
              so mark them again from each channel's menu
            * **#32:** settings move to a new screen
              * the old one is gone
        """.trimIndent()

        assertThat(ReleaseNotes.fromReleaseBody(release, version = "1.0.0")?.sections).containsExactly(
            NotesSection(
                NotesHeading.HeadsUp,
                listOf(
                    "your favourites are cleared by this update, so mark them again from each channel's menu",
                    "settings move to a new screen; the old one is gone",
                ),
            ),
        )
    }

    @Test
    fun `GitHub's own release notes after the changelog are not shown`() {
        val body = """
            ### Bug fixes

            * **#40:** subtitles stay on after a channel change

            ## What's Changed
            * feat: a pull request by someone in https://github.com/neutraltone/settee/pull/99
        """.trimIndent()

        assertThat(ReleaseNotes.fromReleaseBody(body, version = "0.5.0")?.sections).containsExactly(
            NotesSection(NotesHeading.BugFixes, listOf("subtitles stay on after a channel change")),
        )
    }

    @Test
    fun `a link that is part of the sentence keeps its words`() {
        val body = """
            ### Features

            * **#51:** works with [Stremio](https://stremio.com) ([abc1234](https://github.com/neutraltone/settee/commit/abc1234)), closes [#12](https://github.com/neutraltone/settee/issues/12)
        """.trimIndent()

        assertThat(ReleaseNotes.fromReleaseBody(body, version = "0.5.0")?.sections).containsExactly(
            NotesSection(NotesHeading.NewFeatures, listOf("works with Stremio")),
        )
    }
}
