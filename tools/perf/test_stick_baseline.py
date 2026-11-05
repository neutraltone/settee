"""Runs the measuring script against a fake adb, with replies in the format a Stick gives."""

import contextlib
import io
import json
import os
import pathlib
import stat
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import stick_baseline

PACKAGE = "uk.neutraltone.settee"
NO_INPUT = "9223372036854775807,0"
HEADER = (
    "Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,"
    "AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,"
    "SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,"
)


def frame(completed_ms, input_ms=None, flags=0):
    times = NO_INPUT if input_ms is None else f"{input_ms * 1_000_000},{input_ms * 1_000_000}"
    start = (completed_ms - 10) * 1_000_000
    between = ",".join(["0"] * 7)
    return f"{flags},{start},{start},{times},{between},0,{completed_ms * 1_000_000},0,0,"


def framestats(*rows):
    return "\n".join(["---PROFILEDATA---", HEADER, *rows, "---PROFILEDATA---"]) + "\n"


def press_reply(*rows, pressed_ms=0):
    return f"now at {pressed_ms * 1_000_000} nsecs\n{framestats(*rows)}"


SUMMARY = textwrap.dedent(
    """\
    ** Graphics info for pid 1 [uk.neutraltone.settee] **

    Stats since: 1533615777090ns
    Total frames rendered: 200
    Janky frames: 50 (25.00%)
    50th percentile: 14ms
    90th percentile: 32ms
    95th percentile: 48ms
    99th percentile: 97ms
    Number Missed Vsync: 11
    Number High input latency: 3
    Number Slow UI thread: 21
    Number Slow bitmap uploads: 2
    Number Slow issue draw commands: 8
    Number Frame deadline missed: 30
    HISTOGRAM: 5ms=0 14ms=150 97ms=49 300ms=1 650ms=0
    Profile data in ms:

    Window: uk.neutraltone.settee/app.opentv.MainActivity
    Stats since: 1533615775705ns
    Total frames rendered: 999
    Janky frames: 999 (99.00%)
    """
)

GETEVENT = textwrap.dedent(
    """\
    add device 1: /dev/input/event4
      name:     "Amazon Fire TV Remote"
    add device 2: /dev/input/event3
      name:     "kcmouse"
    """
)

PACKAGE_DUMP = textwrap.dedent(
    """\
    Packages:
      Package [uk.neutraltone.settee] (abc):
        versionName=0.4.0
        pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    Dexopt state:
      [uk.neutraltone.settee]
        path: /data/app/base.apk
          arm: [status=quicken] [reason=install]
    """
)

FAKE_ADB = """#!{python}
import json, sys
home = {home!r}
args = sys.argv[1:]
with open(home + "/calls.jsonl", "a") as log:
    log.write(json.dumps(args) + "\\n")
command = " ".join(args)
for needle, replies in json.load(open(home + "/rules.json")):
    if needle in command:
        sys.stdout.write(replies.pop(0) if len(replies) > 1 else replies[0])
        rules = json.load(open(home + "/rules.json"))
        for rule in rules:
            if rule[0] == needle:
                rule[1] = replies
        json.dump(rules, open(home + "/rules.json", "w"))
        break
"""


class FakeStick:
    """A fake adb: each rule is a command fragment and the replies it gives in turn."""

    def __init__(self, directory, rules):
        self.home = pathlib.Path(directory)
        all_rules = [
            ["getevent", [GETEVENT]],
            ["getprop ro.product.model", ["AFTSS\n"]],
            ["getprop ro.build.version.name", ["Fire OS 7.7.1.7\n"]],
            ["dumpsys package", [PACKAGE_DUMP]],
            *rules,
        ]
        (self.home / "rules.json").write_text(json.dumps(all_rules))
        self.adb = self.home / "adb"
        self.adb.write_text(FAKE_ADB.format(python=sys.executable, home=str(self.home)))
        self.adb.chmod(self.adb.stat().st_mode | stat.S_IEXEC)

    def calls(self):
        lines = (self.home / "calls.jsonl").read_text().splitlines()
        return [" ".join(json.loads(line)) for line in lines]


def run(stick, *args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = stick_baseline.main(["--adb", str(stick.adb), "--serial", "stick", *args])
    assert code == 0
    return json.loads(out.getvalue())


class StickTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.directory = directory.name

    def stick(self, *rules):
        return FakeStick(self.directory, list(rules))


class HighlightTest(StickTest):
    def test_response_runs_from_the_key_press_to_the_first_frame_that_saw_it(self):
        stick = self.stick(
            [
                "framestats",
                [
                    # An idle frame finishing first isn't the answer to the press.
                    press_reply(frame(1000), frame(1120, input_ms=1000), frame(1200)),
                    press_reply(frame(2100, input_ms=2000), frame(2150, input_ms=2010)),
                    press_reply(frame(3300, input_ms=3000)),
                ],
            ]
        )

        result = run(stick, "highlight", "--label", "home", "--keys", "RIGHT*2,LEFT", "--gap-ms", "1000")

        self.assertEqual(result["label"], "home")
        self.assertEqual(result["response_ms"], [120.0, 100.0, 300.0])
        self.assertEqual(result["median_ms"], 120.0)
        self.assertEqual(result["worst_ms"], 300.0)
        self.assertEqual(result["presses"], 3)

    def test_a_time_left_over_from_an_earlier_press_is_not_counted_against_this_one(self):
        # Seen on the Stick: a press that draws nothing leaves its time on the next frame,
        # which read as a 27.7 s response.
        stick = self.stick(["framestats", [press_reply(frame(30090, input_ms=2300), pressed_ms=30000)]])

        result = run(stick, "highlight", "--label", "home", "--keys", "UP", "--gap-ms", "1000")

        self.assertEqual(result["response_ms"], [90.0])
        self.assertEqual(result["timed_from_clock"], 1)

    def test_the_clock_is_read_before_the_press(self):
        stick = self.stick(["framestats", [press_reply()]])

        run(stick, "highlight", "--label", "home", "--keys", "DOWN", "--gap-ms", "1000")

        press = next(call for call in stick.calls() if "sendevent" in call)
        self.assertLess(press.index("/proc/timer_list"), press.index("sendevent"))

    def test_a_press_that_draws_nothing_is_counted_not_dropped(self):
        stick = self.stick(
            ["framestats", [press_reply(), press_reply(frame(1200)), press_reply(frame(3100, input_ms=3000))]]
        )

        result = run(stick, "highlight", "--label", "home", "--keys", "DOWN*3", "--gap-ms", "1000")

        self.assertEqual(result["response_ms"], [100.0])
        self.assertEqual(result["no_frame"], 1)
        self.assertEqual(result["frames_without_input_time"], 1)

    def test_frames_android_marked_as_skipped_do_not_count(self):
        stick = self.stick(["framestats", [press_reply(frame(1050, input_ms=1000, flags=1), frame(1100, input_ms=1000))]])

        result = run(stick, "highlight", "--label", "home", "--keys", "DOWN", "--gap-ms", "1000")

        self.assertEqual(result["response_ms"], [100.0])

    def test_each_press_is_a_real_key_down_and_up_on_the_remote_then_settles_before_reading(self):
        stick = self.stick(["framestats", [press_reply()]])

        run(stick, "highlight", "--label", "home", "--keys", "DOWN", "--gap-ms", "750")

        press = next(call for call in stick.calls() if "sendevent" in call)
        self.assertIn(
            "sendevent /dev/input/event4 1 108 1;sendevent /dev/input/event4 0 0 0;"
            "sendevent /dev/input/event4 1 108 0;sendevent /dev/input/event4 0 0 0;sleep 0.75;",
            press,
        )
        self.assertLess(press.index(f"gfxinfo {PACKAGE} reset"), press.index("sendevent"))
        self.assertLess(press.rindex("sendevent"), press.index(f"gfxinfo {PACKAGE} framestats"))

    def test_the_result_says_what_was_measured(self):
        stick = self.stick(["framestats", [press_reply()]])

        result = run(stick, "highlight", "--label", "home", "--keys", "DOWN", "--gap-ms", "1000")

        self.assertEqual(
            result["device"],
            {
                "model": "AFTSS",
                "fire_os": "Fire OS 7.7.1.7",
                "package": PACKAGE,
                "version": "0.4.0",
                "debuggable": False,
                "dexopt": "quicken",
            },
        )

    def test_a_key_the_script_does_not_know_is_refused(self):
        stick = self.stick()

        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            run(stick, "highlight", "--label", "home", "--keys", "SIDEWAYS", "--gap-ms", "1000")


class ScrollTest(StickTest):
    def test_each_run_reports_the_stats_for_the_whole_app_not_a_window(self):
        stick = self.stick(["sendevent", [SUMMARY]])

        result = run(stick, "scroll", "--label", "guide", "--keys", "DOWN*2", "--gap-ms", "250", "--runs", "1")

        self.assertEqual(result["label"], "guide")
        self.assertEqual(
            result["runs"],
            [
                {
                    "frames": 200,
                    "janky_percent": 25.0,
                    "p50_ms": 14,
                    "p90_ms": 32,
                    "p95_ms": 48,
                    "p99_ms": 97,
                    "worst_ms": 300,
                    "missed_vsync": 11,
                    "high_input_latency": 3,
                    "slow_ui_thread": 21,
                    "slow_bitmap_uploads": 2,
                    "slow_draw_commands": 8,
                    "deadline_missed": 30,
                }
            ],
        )

    def test_it_summarises_across_runs(self):
        slower = SUMMARY.replace("Janky frames: 50 (25.00%)", "Janky frames: 90 (45.00%)").replace(
            "95th percentile: 48ms", "95th percentile: 80ms"
        )
        fastest = SUMMARY.replace("Janky frames: 50 (25.00%)", "Janky frames: 10 (5.00%)").replace(
            "95th percentile: 48ms", "95th percentile: 20ms"
        )
        stick = self.stick(["sendevent", [SUMMARY, slower, fastest]])

        result = run(stick, "scroll", "--label", "guide", "--keys", "DOWN", "--gap-ms", "250", "--runs", "3")

        self.assertEqual(len(result["runs"]), 3)
        self.assertEqual(result["median_janky_percent"], 25.0)
        self.assertEqual(result["median_p95_ms"], 48)

    def test_a_run_presses_at_the_given_pace_between_a_reset_and_the_reading(self):
        stick = self.stick(["sendevent", [SUMMARY]])

        run(stick, "scroll", "--label", "guide", "--keys", "DOWN,UP", "--gap-ms", "250", "--runs", "1")

        script = next(call for call in stick.calls() if "sendevent" in call)
        self.assertLess(script.index(f"gfxinfo {PACKAGE} reset"), script.index("1 108 1"))
        self.assertLess(script.index("1 108 0"), script.index("sleep 0.25;"))
        self.assertLess(script.index("sleep 0.25;"), script.index("1 103 1"))
        self.assertTrue(script.rstrip().endswith(f"dumpsys gfxinfo {PACKAGE}"))

    def test_holding_a_key_keeps_it_down_so_android_repeats_it(self):
        stick = self.stick(["sendevent", [SUMMARY]])

        run(stick, "scroll", "--label", "guide", "--keys", "DOWN", "--hold-ms", "3000", "--gap-ms", "500", "--runs", "1")

        script = next(call for call in stick.calls() if "sendevent" in call)
        self.assertLess(script.index("1 108 1"), script.index("sleep 3;"))
        self.assertLess(script.index("sleep 3;"), script.index("1 108 0"))


class ColdStartTest(StickTest):
    def launch(self, displayed, started_ms=5000, finished_ms=25000, *frames):
        return (
            f"now at {started_ms}000000 nsecs\n"
            f"{displayed}\n"
            f"now at {finished_ms}000000 nsecs\n"
            f"{framestats(*frames)}"
        )

    def test_it_reports_androids_first_frame_time_and_when_drawing_stopped(self):
        line = f"I ActivityManager: Displayed {PACKAGE}/app.opentv.MainActivity: +1s234ms"
        stick = self.stick(
            ["am start", [self.launch(line, 5000, 25000, frame(7000), frame(9500))]],
        )

        result = run(stick, "cold-start", "--runs", "1", "--settle-s", "20")

        self.assertEqual(result["runs"], [{"first_frame_ms": 1234, "drawing_stopped_ms": 4500.0, "still_drawing": False}])

    def test_it_takes_the_median_of_its_runs(self):
        def line(time):
            return f"I ActivityManager: Displayed {PACKAGE}/app.opentv.MainActivity: {time} (total +9s)"

        stick = self.stick(
            [
                "am start",
                [
                    self.launch(line("+12s302ms"), 5000, 25000, frame(9000)),
                    self.launch(line("+900ms"), 5000, 25000, frame(7000)),
                    self.launch(line("+2s"), 5000, 25000, frame(8000)),
                ],
            ],
        )

        result = run(stick, "cold-start", "--runs", "3", "--settle-s", "20")

        self.assertEqual([r["first_frame_ms"] for r in result["runs"]], [12302, 900, 2000])
        self.assertEqual(result["median_first_frame_ms"], 2000)
        self.assertEqual(result["median_drawing_stopped_ms"], 3000.0)

    def test_a_screen_still_drawing_when_time_is_up_says_so(self):
        line = f"I ActivityManager: Displayed {PACKAGE}/app.opentv.MainActivity: +1s"
        stick = self.stick(["am start", [self.launch(line, 5000, 25000, frame(24900))]])

        result = run(stick, "cold-start", "--runs", "1", "--settle-s", "20")

        self.assertTrue(result["runs"][0]["still_drawing"])

    def test_another_apps_start_time_is_not_taken_for_ours(self):
        line = "I ActivityManager: Displayed com.amazon.tv.launcher/.Home: +800ms"
        stick = self.stick(["am start", [self.launch(line, 5000, 25000)]])

        result = run(stick, "cold-start", "--runs", "1", "--settle-s", "20")

        self.assertIsNone(result["runs"][0]["first_frame_ms"])

    def test_each_run_starts_from_a_stopped_app_with_a_clean_log(self):
        stick = self.stick(["am start", [self.launch("", 5000, 25000)]])

        run(stick, "cold-start", "--runs", "1", "--settle-s", "20")

        script = next(call for call in stick.calls() if "am start" in call)
        order = [f"am force-stop {PACKAGE}", "logcat -c", "/proc/timer_list", "am start", "sleep 20", "logcat -d"]
        positions = [script.index(step) for step in order]
        self.assertEqual(positions, sorted(positions))


if __name__ == "__main__":
    unittest.main()
