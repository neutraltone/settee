#!/usr/bin/env python3
"""
Measures how Settee performs on a Fire TV Stick, over adb. The method and results are in
docs/PERFORMANCE.md. Prints JSON, and never prints raw adb output, so nothing a provider
sent can end up in a pasted result.

Presses go to the remote's own input device with sendevent, not through `adb shell input`,
which starts a JVM for every call: that took 0.77-1.18 s on the Stick, swamping what's being
measured and making a realistic pace or a held key impossible.

Run it with the app already on the screen being measured:

    tools/perf/stick_baseline.py highlight --label home-rows --keys 'RIGHT*10,LEFT*10'
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys

KEYS = {"UP": 103, "DOWN": 108, "LEFT": 105, "RIGHT": 106}
REMOTE = "Amazon Fire TV Remote"
NO_INPUT_TIME = 9223372036854775807
# CLOCK_MONOTONIC in ns, the clock framestats uses; `date` only reads the wall clock.
CLOCK = "head -3 /proc/timer_list | tail -1;"


def parse_keys(pattern):
    """'RIGHT*2,LEFT' -> ['RIGHT', 'RIGHT', 'LEFT']"""
    keys = []
    for part in pattern.split(","):
        name, _, count = part.strip().partition("*")
        if name not in KEYS or (count and not count.isdigit()):
            raise argparse.ArgumentTypeError(f"not a key pattern: {part!r}; keys are {', '.join(KEYS)}")
        keys += [name] * int(count or 1)
    return keys


def parse_frames(text):
    """Frames from every window in `gfxinfo framestats` that Android didn't mark as skipped."""
    frames = []
    for section in text.split("---PROFILEDATA---")[1::2]:
        lines = section.strip().splitlines()
        if not lines:
            continue
        columns = lines[0].split(",")
        for line in lines[1:]:
            row = dict(zip(columns, line.split(",")))
            if int(row["Flags"]) == 0:
                frames.append(
                    {
                        "oldest_input": int(row["OldestInputEvent"]),
                        "completed": int(row["FrameCompleted"]),
                    }
                )
    return frames


def input_response(frames, pressed):
    """
    (ms from the key press to the first frame that saw it finishing, timed from the clock?)

    OldestInputEvent is the time on the key event itself, so it needs no timing of the
    injection, which adds tens of ms. But a press that draws nothing leaves its time waiting
    for the next frame, so anything older than this press falls back to the clock read just
    before it, a slight overestimate. Either way it stops at the Stick: the remote's radio and
    the TV's own lag aren't in it.
    """
    for frame in frames:
        if 0 < frame["oldest_input"] < NO_INPUT_TIME:
            start = max(frame["oldest_input"], pressed)
            return (frame["completed"] - start) / 1_000_000, frame["oldest_input"] < pressed
    return None, False


def parse_summary(text):
    """The app-wide block of `gfxinfo`, which comes before the same stats for each window."""
    app_wide = text.split("Profile data in ms")[0]

    def number(label):
        found = re.search(rf"^{label}: ?(\d+)", app_wide, re.MULTILINE)
        return int(found.group(1)) if found else None

    janky = re.search(r"^Janky frames: \d+ \(([\d.]+)%\)", app_wide, re.MULTILINE)
    histogram = re.search(r"^HISTOGRAM: (.*)$", app_wide, re.MULTILINE)
    buckets = re.findall(r"(\d+)ms=(\d+)", histogram.group(1)) if histogram else []
    drawn = [int(ms) for ms, count in buckets if int(count) > 0]
    return {
        "frames": number("Total frames rendered"),
        "janky_percent": float(janky.group(1)) if janky else None,
        "p50_ms": number("50th percentile"),
        "p90_ms": number("90th percentile"),
        "p95_ms": number("95th percentile"),
        "p99_ms": number("99th percentile"),
        "worst_ms": max(drawn) if drawn else None,
        "missed_vsync": number("Number Missed Vsync"),
        "high_input_latency": number("Number High input latency"),
        "slow_ui_thread": number("Number Slow UI thread"),
        "slow_bitmap_uploads": number("Number Slow bitmap uploads"),
        "slow_draw_commands": number("Number Slow issue draw commands"),
        "deadline_missed": number("Number Frame deadline missed"),
    }


def parse_displayed_ms(text, package):
    """Android's own first-frame time, from a line like 'Displayed <package>/<activity>: +1s234ms'."""
    found = re.search(rf"Displayed {re.escape(package)}/\S+: \+(?:(\d+)s)?(?:(\d+)ms)?", text)
    if not found:
        return None
    return int(found.group(1) or 0) * 1000 + int(found.group(2) or 0)


def clock_readings(text):
    return [int(found) for found in re.findall(r"^now at (\d+) nsecs", text, re.MULTILINE)]


def median_of(values):
    present = [value for value in values if value is not None]
    return statistics.median(present) if present else None


def pause(ms):
    return f"sleep {ms / 1000:g};"


def press(device, code, hold_ms=0):
    held = pause(hold_ms) if hold_ms else ""
    return (
        f"sendevent {device} 1 {code} 1;sendevent {device} 0 0 0;{held}"
        f"sendevent {device} 1 {code} 0;sendevent {device} 0 0 0;"
    )


class Stick:
    def __init__(self, adb, serial, package):
        self.adb = [adb, *(["-s", serial] if serial else [])]
        self.package = package

    def shell(self, script):
        command = [*self.adb, "shell", script]
        return subprocess.run(command, check=True, capture_output=True, text=True).stdout

    def remote(self):
        """Its event number changes, so it's looked up by name."""
        found = re.search(rf'add device \d+: (\S+)\n\s+name:\s+"{REMOTE}"', self.shell("getevent -pl"))
        if not found:
            sys.exit(f'No input device named "{REMOTE}" on this device.')
        return found.group(1)

    def describe(self):
        package = self.shell(f"dumpsys package {self.package}")
        version = re.search(r"versionName=(\S+)", package)
        dexopt = re.search(r"\[status=([^\]]+)\]", package)
        return {
            "model": self.shell("getprop ro.product.model").strip(),
            "fire_os": self.shell("getprop ro.build.version.name").strip(),
            "package": self.package,
            "version": version.group(1) if version else None,
            # A debuggable build runs far slower, so a result must say which it came from.
            "debuggable": "DEBUGGABLE" in package,
            "dexopt": dexopt.group(1) if dexopt else None,
        }


def measure_highlight(stick, args):
    device = stick.remote()
    settle = pause(args.gap_ms)
    response = []
    no_frame = without_input_time = timed_from_clock = 0
    keys = args.keys
    for key in keys:
        # Each press is reset and read on its own: framestats only holds the last 120 frames.
        output = stick.shell(
            f"dumpsys gfxinfo {stick.package} reset >/dev/null;{CLOCK}"
            f"{press(device, KEYS[key])}{settle}"
            f"dumpsys gfxinfo {stick.package} framestats"
        )
        frames = parse_frames(output)
        answer, from_clock = input_response(frames, clock_readings(output)[0])
        if answer is not None:
            response.append(answer)
            timed_from_clock += from_clock
        elif frames:
            without_input_time += 1
        else:
            no_frame += 1
    return {
        "label": args.label,
        "device": stick.describe(),
        "presses": len(keys),
        "response_ms": response,
        "median_ms": statistics.median(response) if response else None,
        "worst_ms": max(response) if response else None,
        "no_frame": no_frame,
        "frames_without_input_time": without_input_time,
        "timed_from_clock": timed_from_clock,
    }


def measure_scroll(stick, args):
    device = stick.remote()
    settle = pause(args.gap_ms)
    presses = "".join(press(device, KEYS[key], args.hold_ms) + settle for key in args.keys)
    # The summary has no 120-frame limit, and reading framestats during a run would disturb it.
    script = f"dumpsys gfxinfo {stick.package} reset >/dev/null;{presses}dumpsys gfxinfo {stick.package}"
    runs = [parse_summary(stick.shell(script)) for _ in range(args.runs)]
    return {
        "label": args.label,
        "device": stick.describe(),
        "keys": len(args.keys),
        "gap_ms": args.gap_ms,
        "hold_ms": args.hold_ms,
        "runs": runs,
        "median_janky_percent": median_of(run["janky_percent"] for run in runs),
        "median_p95_ms": median_of(run["p95_ms"] for run in runs),
    }


def measure_cold_start(stick, args):
    # `am start -W` gives up after 10 s, which a slow build passes, so the log is read instead.
    script = (
        f"am force-stop {stick.package};sleep 2;logcat -c;{CLOCK}"
        f"am start -n {stick.package}/app.opentv.MainActivity >/dev/null;sleep {args.settle_s};"
        f"logcat -d -s ActivityManager:I | grep Displayed;{CLOCK}"
        f"dumpsys gfxinfo {stick.package} framestats"
    )
    runs = []
    for _ in range(args.runs):
        output = stick.shell(script)
        clocks = clock_readings(output)
        frames = parse_frames(output)
        last_frame = max((frame["completed"] for frame in frames), default=None)
        runs.append(
            {
                "first_frame_ms": parse_displayed_ms(output, stick.package),
                "drawing_stopped_ms": (last_frame - clocks[0]) / 1_000_000 if last_frame else None,
                # Then the time above is only the end of the wait, not when the screen settled.
                "still_drawing": bool(last_frame) and clocks[-1] - last_frame < 1_000_000_000,
            }
        )
    return {
        "device": stick.describe(),
        "settle_s": args.settle_s,
        "runs": runs,
        "median_first_frame_ms": median_of(run["first_frame_ms"] for run in runs),
        "median_drawing_stopped_ms": median_of(run["drawing_stopped_ms"] for run in runs),
    }


def parser():
    top = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    top.add_argument("--adb", default="adb")
    top.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    top.add_argument("--package", default="uk.neutraltone.settee")
    commands = top.add_subparsers(dest="command", required=True)

    highlight = commands.add_parser("highlight", help="how quickly single presses draw")
    highlight.add_argument("--label", required=True)
    highlight.add_argument("--keys", required=True, type=parse_keys, help="for example 'RIGHT*10,LEFT*10'")
    highlight.add_argument("--gap-ms", type=int, default=1000, help="settling time after each press")
    highlight.set_defaults(measure=measure_highlight)

    scroll = commands.add_parser("scroll", help="frame timing across a run of presses")
    scroll.add_argument("--label", required=True)
    scroll.add_argument("--keys", required=True, type=parse_keys, help="end where it began, for example 'DOWN*10,UP*10'")
    scroll.add_argument("--gap-ms", type=int, default=250, help="pause after each press")
    scroll.add_argument("--hold-ms", type=int, default=0, help="keep each key down, so Android repeats it")
    scroll.add_argument("--runs", type=int, default=5)
    scroll.set_defaults(measure=measure_scroll)

    cold_start = commands.add_parser("cold-start", help="force-stops the app and starts it again")
    cold_start.add_argument("--runs", type=int, default=7)
    cold_start.add_argument("--settle-s", type=int, default=20, help="how long to wait for drawing to stop")
    cold_start.set_defaults(measure=measure_cold_start)
    return top


def main(argv=None):
    args = parser().parse_args(argv)
    stick = Stick(args.adb, args.serial, args.package)
    # The screensaver would swallow the first press.
    stick.shell("input keyevent KEYCODE_WAKEUP")
    print(json.dumps(args.measure(stick, args), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
