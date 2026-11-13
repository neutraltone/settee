"""Every committed copy of Settee's mark matches what mark.py writes, so none drifts on its own."""
import hashlib
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mark  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


class MarkTest(unittest.TestCase):
    def test_each_committed_copy_of_the_mark_is_what_mark_py_writes(self):
        for path, text in mark.outputs(ROOT).items():
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertEqual(path.read_text(), text, "run tools/render-launcher-art.sh and commit the result")

    def test_the_debug_build_overrides_each_launcher_drawable_and_has_its_own_png_sources(self):
        written = {path.relative_to(ROOT).as_posix() for path in mark.outputs(ROOT)}

        self.assertEqual({p for p in written if p.startswith("app/src/debug/")}, {
            "app/src/debug/res/drawable/ic_launcher_foreground.xml",
            "app/src/debug/res/drawable/ic_launcher_monochrome.xml",
        })
        self.assertIn("tools/launcher-art/settee-icon-debug.svg", written)
        self.assertIn("tools/launcher-art/settee-banner-debug.svg", written)

    def test_release_builds_get_only_the_main_copies_they_always_had(self):
        written = {path.relative_to(ROOT).as_posix() for path in mark.outputs(ROOT)}

        self.assertEqual({p for p in written if p.startswith("app/src/main/")}, {
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml",
            "app/src/main/res/drawable/ic_notification.xml",
            "app/src/main/res/drawable/ic_settee_mark.xml",
            "app/src/main/res/drawable/ic_waiting_tv.xml",
            "app/src/main/res/drawable/ic_waiting_tv_small.xml",
            "app/src/main/java/app/opentv/ui/design/AerialTvShape.kt",
            "app/src/main/java/app/opentv/pairing/PairingLockup.kt",
        })

    def test_each_png_was_rendered_from_the_svg_now_committed(self):
        # CI has no renderer, so render-launcher-art.sh records what it rendered from and to.
        recorded = {}
        for line in (ROOT / "tools/launcher-art/rendered.sha256").read_text().splitlines():
            digest, path = line.split("  ", 1)
            recorded[path] = digest

        self.assertEqual(set(recorded), {
            "tools/launcher-art/settee-icon.svg",
            "tools/launcher-art/settee-banner.svg",
            "tools/launcher-art/settee-icon-debug.svg",
            "tools/launcher-art/settee-banner-debug.svg",
        } | {f"app/src/{s}/res/mipmap-{d}/ic_launcher.png"
             for s in ("main", "debug") for d in ("xhdpi", "xxhdpi", "xxxhdpi")}
          | {f"app/src/{s}/res/mipmap-xhdpi/banner.png" for s in ("main", "debug")})
        for path, digest in recorded.items():
            with self.subTest(path=path):
                self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest,
                                 "run tools/render-launcher-art.sh and commit the result")

    def test_no_launcher_mask_clips_the_debug_badge(self):
        # Android keeps a circle of radius 33 (of the adaptive icon's 108) inside every mask shape.
        self.assertLess(mark.badge_reach(ROOT / "tools/launcher-art"), 33)

    def test_the_colour_resources_are_the_mark_s_colours(self):
        colours = (ROOT / "app/src/main/res/values/colors.xml").read_text()

        def resource(name):
            return "#" + re.search(rf'<color name="{name}">#FF([0-9A-F]{{6}})</color>', colours)[1]

        self.assertEqual(resource("settee_coral"), mark.CORAL)
        self.assertEqual(resource("settee_plum"), mark.PLUM)
        self.assertEqual(resource("settee_cream"), mark.CREAM)


if __name__ == "__main__":
    unittest.main()
