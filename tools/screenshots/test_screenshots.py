# Tests for screenshots.py. Run with: python3 -m unittest discover tools/screenshots
import tempfile
import unittest
from pathlib import Path

from PIL import Image

import screenshots


def png(directory, name, colour=(20, 20, 30), size=(64, 36), patch=None):
    """Writes a flat image, optionally with a rectangle (x0, y0, x1, y1, rgb) painted on it."""
    image = Image.new("RGB", size, colour)
    if patch:
        x0, y0, x1, y1, rgb = patch
        image.paste(rgb, (x0, y0, x1, y1))
    Path(directory).mkdir(parents=True, exist_ok=True)
    image.save(Path(directory) / f"{name}.png")


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.base = self.tmp / "base"
        self.head = self.tmp / "head"

    def test_an_identical_screen_is_not_a_change(self):
        png(self.base, "episode_row")
        png(self.head, "episode_row")

        self.assertEqual(screenshots.compare(self.base, self.head), [])

    def test_a_screen_whose_pixels_differ_is_changed(self):
        png(self.base, "episode_row")
        png(self.head, "episode_row", patch=(0, 0, 10, 10, (200, 0, 0)))

        self.assertEqual(
            screenshots.compare(self.base, self.head),
            [screenshots.Change("episode_row", before=self.base / "episode_row.png", after=self.head / "episode_row.png")],
        )

    def test_anti_aliasing_noise_is_not_a_change(self):
        # A faint shift everywhere, and a few edge pixels flipping outright, out of 1080p's two million.
        png(self.base, "episode_row", size=(1920, 1080))
        png(self.head, "episode_row", colour=(23, 18, 33), size=(1920, 1080), patch=(500, 500, 503, 501, (255, 255, 255)))

        self.assertEqual(screenshots.compare(self.base, self.head), [])

    def test_a_screen_of_a_different_size_is_changed(self):
        png(self.base, "episode_row")
        png(self.head, "episode_row", size=(64, 40))

        self.assertEqual([c.name for c in screenshots.compare(self.base, self.head)], ["episode_row"])

    def test_a_screen_only_in_head_is_new_and_one_only_in_base_is_removed(self):
        png(self.base, "old_screen")
        png(self.head, "whats_new")

        self.assertEqual(
            screenshots.compare(self.base, self.head),
            [
                screenshots.Change("old_screen", before=self.base / "old_screen.png", after=None),
                screenshots.Change("whats_new", before=None, after=self.head / "whats_new.png"),
            ],
        )

    def test_a_base_that_rendered_nothing_makes_every_screen_new(self):
        # The base commit predates screenshots, or its build failed: there is no directory at all.
        png(self.head, "episode_row")

        self.assertEqual(
            screenshots.compare(self.tmp / "missing", self.head),
            [screenshots.Change("episode_row", before=None, after=self.head / "episode_row.png")],
        )


class SectionTest(unittest.TestCase):
    def test_lists_each_changed_screen_before_and_after_with_new_and_removed_screens_one_sided(self):
        changes = [
            screenshots.Change("episode_row", before=Path("b/episode_row.png"), after=Path("h/episode_row.png")),
            screenshots.Change("old_screen", before=Path("b/old_screen.png"), after=None),
            screenshots.Change("whats_new", before=None, after=Path("h/whats_new.png")),
        ]

        self.assertEqual(
            screenshots.section(changes),
            """<!-- screenshots:start -->
## Screenshots

| Screen | Before | After |
| --- | --- | --- |
| episode_row | ![episode_row before](./episode_row.before.png) | ![episode_row after](./episode_row.after.png) |
| old_screen | ![old_screen before](./old_screen.before.png) | _Removed_ |
| whats_new | _New_ | ![whats_new after](./whats_new.after.png) |

<sub>Rendered by the Screenshots workflow from this PR's base and head. It rewrites this section on every push.</sub>
<!-- screenshots:end -->""",
        )

    def test_says_so_when_nothing_changed(self):
        self.assertEqual(
            screenshots.section([]),
            """<!-- screenshots:start -->
## Screenshots

No visual changes.

<sub>Rendered by the Screenshots workflow from this PR's base and head. It rewrites this section on every push.</sub>
<!-- screenshots:end -->""",
        )

    def test_names_each_image_the_section_references_after_the_file_it_comes_from(self):
        changes = [
            screenshots.Change("episode_row", before=Path("b/episode_row.png"), after=Path("h/episode_row.png")),
            screenshots.Change("whats_new", before=None, after=Path("h/whats_new.png")),
        ]

        self.assertEqual(
            screenshots.attachments(changes),
            [
                (Path("b/episode_row.png"), "episode_row.before.png"),
                (Path("h/episode_row.png"), "episode_row.after.png"),
                (Path("h/whats_new.png"), "whats_new.after.png"),
            ],
        )


class SpliceTest(unittest.TestCase):
    NEW = "<!-- screenshots:start -->\nnew\n<!-- screenshots:end -->"

    def test_replaces_only_the_marked_section_and_keeps_everything_written_around_it(self):
        body = (
            "## Summary\r\n\r\nWhy this changes.\r\n\r\n"
            "<!-- screenshots:start -->\r\nold\r\n<!-- screenshots:end -->\r\n\r\n"
            "Closes #44\r\n"
        )

        self.assertEqual(
            screenshots.splice(body, self.NEW),
            "## Summary\r\n\r\nWhy this changes.\r\n\r\n" + self.NEW + "\r\n\r\nCloses #44\r\n",
        )

    def test_adds_the_section_to_the_end_of_a_description_without_one(self):
        self.assertEqual(screenshots.splice("Why this changes.\n", self.NEW), "Why this changes.\n\n" + self.NEW + "\n")

    def test_an_empty_description_becomes_just_the_section(self):
        self.assertEqual(screenshots.splice("", self.NEW), self.NEW + "\n")


class CommentTest(unittest.TestCase):
    def test_shows_what_changed_since_the_previous_push(self):
        changes = [screenshots.Change("episode_row", before=Path("p/episode_row.png"), after=Path("h/episode_row.png"))]

        self.assertEqual(
            screenshots.comments(changes, since="0123456789abcdef"),
            [
                (
                    """### Screenshots changed since 0123456

| Screen | Before | After |
| --- | --- | --- |
| episode_row | ![episode_row before](./episode_row.before.png) | ![episode_row after](./episode_row.after.png) |""",
                    [(Path("p/episode_row.png"), "episode_row.before.png"), (Path("h/episode_row.png"), "episode_row.after.png")],
                )
            ],
        )

    def test_splits_into_comments_of_at_most_fifty_images_each(self):
        changes = [
            screenshots.Change(f"screen_{i:02}", before=Path(f"p/screen_{i:02}.png"), after=Path(f"h/screen_{i:02}.png"))
            for i in range(26)
        ]

        posted = screenshots.comments(changes, since="0123456789abcdef")

        self.assertEqual([len(files) for _, files in posted], [50, 2])
        self.assertIn("| screen_25 |", posted[1][0])
        self.assertTrue(posted[1][0].startswith("### Screenshots changed since 0123456 (continued)"))

    def test_nothing_changed_since_the_previous_push_means_no_comment(self):
        self.assertEqual(screenshots.comments([], since="0123456789abcdef"), [])


if __name__ == "__main__":
    unittest.main()
