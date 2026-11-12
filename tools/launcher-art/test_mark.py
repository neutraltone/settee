"""Every committed copy of Settee's mark matches what mark.py writes, so none drifts on its own."""
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

    def test_the_colour_resources_are_the_mark_s_colours(self):
        colours = (ROOT / "app/src/main/res/values/colors.xml").read_text()

        def resource(name):
            return "#" + re.search(rf'<color name="{name}">#FF([0-9A-F]{{6}})</color>', colours)[1]

        self.assertEqual(resource("settee_coral"), mark.CORAL)
        self.assertEqual(resource("settee_plum"), mark.PLUM)
        self.assertEqual(resource("settee_cream"), mark.CREAM)


if __name__ == "__main__":
    unittest.main()
