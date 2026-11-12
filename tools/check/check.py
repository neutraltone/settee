#!/usr/bin/env python3
"""Runs Gradle's tests and checks, and says what failed and why in a few lines.

Gradle's own output buries a failed test's reason in an HTML report, and stops at the first task
that fails, so finding everything wrong with a change takes a run per problem. This runs one
Gradle command, keeps its full output in a log, and prints only the result: each failed test with
its message and the line it failed on, each compiler, ktlint, detekt and lint finding, and the
tests slow enough to be worth a look. It exits with Gradle's own result, so nothing reading the
exit code can take a failed run for a passed one.

It also never leaves a test process behind. On a Mac short of memory, a test JVM can freeze: one
thread gets stuck in the kernel re-faulting a page of compiled code, the rest of the JVM waits on
it for ever, and it ignores every signal but SIGKILL, Gradle's request to stop included. Left
alone it spins for hours, taking a core and its memory, which makes the next freeze likelier. So
a run in which no test finishes for two minutes is reported as FROZEN and killed, as is one that
outlasts its time, and test processes left in this checkout by an abandoned earlier run are killed
before the next starts. A freeze is the machine, not the test it happened to stop in: run it again.

Usage, from anywhere in a checkout (a worktree included):
  tools/check/check.py test RecordingsContentTest [OtherTest ...]   the named test classes
  tools/check/check.py quick      ktlintFormat and detekt: after each change, before the long checks
  tools/check/check.py all        what a PR's checks run, in one pass that doesn't stop at the first failure
  tools/check/check.py sweep      kill test processes left running for over half an hour, in any checkout

--timeout MINUTES changes how long a run may take (test and quick: 8, all: 25).
--rerun, with test, runs the tests even if nothing has changed since they last passed.
`all` runs the debug unit tests only; CI's `./gradlew test` runs the release variant's as well.
Run one Gradle command per checkout at a time: a run treats any other test process there as left over.
"""

import os
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

RESULTS = "app/build/test-results/testDebugUnitTest"
IN_PROGRESS = f"{RESULTS}/binary/in-progress-results-generic.bin"
LINT_REPORT = "app/build/reports/lint-results-debug.txt"
PACKAGE = "app.opentv"
SLOW_SECONDS = 30.0
MESSAGE_LINES = 6

# Longer than any real run, so a test process this old was abandoned rather than still in use.
STALE_MINUTES = 30

# With no test finishing for this long, the test JVM has frozen: the slowest real test takes under 30s.
FROZEN_SECONDS = 120
POLL_SECONDS = 2


class CutOff:
    """Why a run was stopped before Gradle finished."""

    TIMED_OUT = "TIMED OUT"
    FROZEN = "FROZEN"

MODES = {
    "test": ["testDebugUnitTest"],
    "quick": ["ktlintFormat", "detekt"],
    "all": ["testDebugUnitTest", "ktlintCheck", "detekt", "lintDebug", "--continue"],
    "sweep": [],
}
TIMEOUT_MINUTES = {"test": 8, "quick": 8, "all": 25, "sweep": 1}


@dataclass
class Failure:
    test: str
    message: str
    where: str


@dataclass
class Results:
    tests: int = 0
    seconds: float = 0.0
    failures: list = field(default_factory=list)
    slow: list = field(default_factory=list)


@dataclass
class Worker:
    """A Gradle test process: how many minutes it has run, and the directory it runs in."""

    pid: int
    minutes: float
    cwd: str


def read_results(directory, since):
    """This run's unit test results: Gradle leaves an earlier run's files behind when it fails
    before the tests start, so anything written before `since` isn't counted."""
    results = Results()
    directory = Path(directory)
    if not directory.is_dir():
        return results
    for path in sorted(directory.glob("TEST-*.xml")):
        if path.stat().st_mtime < since:
            continue
        suite = ET.parse(path).getroot()
        short = suite.get("name", "").rsplit(".", 1)[-1]
        for case in suite.iter("testcase"):
            name = f"{short} > {case.get('name')}"
            seconds = float(case.get("time") or 0)
            results.tests += 1
            results.seconds += seconds
            if seconds >= SLOW_SECONDS:
                results.slow.append((seconds, name))
            for problem in case:
                if problem.tag in ("failure", "error"):
                    results.failures.append(_failure(name, problem.text or problem.get("message") or ""))
    results.slow.sort(reverse=True)
    return results


def _failure(name, text):
    lines = text.strip().splitlines()
    message = [line for line in lines if not line.startswith("\tat ")][:MESSAGE_LINES]
    frame = next((line for line in lines if line.startswith(f"\tat {PACKAGE}.")), "")
    where = re.search(r"\(([^()]+)\)\s*$", frame)
    return Failure(name, "\n".join(message), where.group(1) if where else "")


_COMPILER = re.compile(r"^e: (?:file://)?(.+)$")
_LOCATED = re.compile(r"^(/\S+\.(?:kts?|xml|java)):(\d+)(.*)$")


def findings(text, root):
    """Compiler errors, and ktlint, detekt and lint findings, as paths from the repository's root."""
    found = []
    for line in text.splitlines():
        compiler = _COMPILER.match(line)
        located = _LOCATED.match(line)
        if compiler:
            finding = compiler.group(1)
        elif located and ": Warning:" not in line:
            finding = line
        else:
            continue
        finding = finding.replace(f"{root}/", "")
        if finding not in found:
            found.append(finding)
    return found


def failed_tasks(log):
    return re.findall(r"^> Task (\S+) FAILED", log, flags=re.MULTILINE)


def gradle_reasons(log):
    """What Gradle says went wrong: the only explanation when it fails before any task reports."""
    reasons = []
    for block in re.findall(r"^\* What went wrong:\n(.*?)(?=^\* Try:|\Z)", log, flags=re.MULTILINE | re.DOTALL):
        reason = " ".join(line.strip() for line in block.strip().splitlines() if line.strip())
        if reason and reason not in reasons:
            reasons.append(reason)
    return reasons


def last_finished(recorded):
    """The last test in Gradle's record of a run in progress. It records a test when it finishes, so
    a cut-off run stopped in the one after. The record is binary, so this reads the names out of it
    rather than its format."""
    runs = [run.decode() for run in re.findall(rb"[\x20-\x7e]{4,}", recorded)]
    for at in range(len(runs) - 2, -1, -1):
        if re.fullmatch(rf"{re.escape(PACKAGE)}\.\w+", runs[at]) and not runs[at + 1].startswith(PACKAGE):
            return f"{runs[at].rsplit('.', 1)[-1]} > {runs[at + 1]}"
    return ""


def frozen(last_progress, started, now):
    """Whether a run has frozen: its tests have started (`last_progress` is when one last finished,
    None before any has) and none has finished since. Progress from before `started` is an earlier
    run's, left behind while this one compiles."""
    return last_progress is not None and last_progress >= started and now - last_progress > FROZEN_SECONDS


def test_workers(ps_output, cwd_of):
    """Gradle's test processes in `ps -axo pid=,etime=,command=` output; `cwd_of` gives a pid's directory."""
    workers = []
    for line in ps_output.splitlines():
        match = re.match(r"\s*(\d+)\s+(\S+)\s+(.*)", line)
        if not match or "Gradle Test Executor" not in match.group(3):
            continue
        pid = int(match.group(1))
        workers.append(Worker(pid, _minutes(match.group(2)), cwd_of(pid) or ""))
    return workers


def _minutes(elapsed):
    """ps's elapsed time, [[days-]hours:]minutes:seconds, in minutes."""
    days, _, clock = elapsed.rpartition("-")
    parts = [int(part) for part in clock.split(":")]
    hours, minutes, seconds = [0] * (3 - len(parts)) + parts
    return round(int(days or 0) * 1440 + hours * 60 + minutes + seconds / 60, 1)


def leftover(workers, root, minutes):
    """The test processes to kill: those in `root`'s checkout (any checkout, if None) that have run
    for at least `minutes`. One whose directory can't be read is never taken for this checkout's."""
    app = f"{root}/app" if root else None
    return [w for w in workers if w.minutes >= minutes and (app is None or w.cwd == app)]


def _cwd_of(pid):
    try:
        return os.readlink(f"/proc/{pid}/cwd")
    except OSError:
        pass
    listed = subprocess.run(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"], capture_output=True, text=True)
    return next((line[1:] for line in listed.stdout.splitlines() if line.startswith("n")), None)


def stop_leftover(root, minutes):
    """Kills the leftover test processes, and returns them. SIGKILL, because Gradle has already
    asked a hung one to stop and it didn't."""
    listed = subprocess.run(["ps", "-axo", "pid=,etime=,command="], capture_output=True, text=True).stdout
    stopped = []
    for worker in leftover(test_workers(listed, _cwd_of), root, minutes):
        try:
            os.kill(worker.pid, signal.SIGKILL)
            stopped.append(worker)
        except OSError:
            pass
    return stopped


def gradle_tasks(mode, names, rerun=False):
    tasks = list(MODES[mode])
    if rerun:
        tasks.append("--rerun")
    for name in names:
        tasks += ["--tests", name if name.startswith(f"{PACKAGE}.") else f"{PACKAGE}.{name}"]
    return tasks


def parse_args(args):
    """(mode, test names, minutes allowed, whether to rerun), or None if the arguments make no sense."""
    args = list(args)
    minutes = None
    rerun = "--rerun" in args
    if rerun:
        args.remove("--rerun")
    if "--timeout" in args:
        at = args.index("--timeout")
        if at + 1 >= len(args) or not args[at + 1].isdigit():
            return None
        minutes = int(args[at + 1])
        del args[at : at + 2]
    if not args or args[0] not in MODES or (args[0] == "test") != (len(args) > 1):
        return None
    if rerun and args[0] != "test":
        return None
    return args[0], args[1:], minutes or TIMEOUT_MINUTES[args[0]], rerun


def _age(minutes):
    return f"{minutes / 60:.0f}h" if minutes >= 120 else f"{minutes:.0f}m"


def verdict(gradle_exit, results, found, tasks, log_path, reasons=(), stopped=(), cut_off="", last=""):
    """What to print, and the exit code: Gradle's, whatever else was or wasn't found. A run that was
    cut off has no exit code and has failed."""
    out = [f"STOPPED  test process {w.pid}, left running for {_age(w.minutes)} in {w.cwd}" for w in stopped]
    for failure in results.failures:
        where = f" ({failure.where})" if failure.where else ""
        out.append(f"FAILED TEST  {failure.test}{where}")
        out += [f"    {line}" for line in failure.message.splitlines()]
    out += [f"FINDING  {finding}" for finding in found]
    if gradle_exit not in (0, None) and not results.failures and not found:
        out += [f"GRADLE  {reason}" for reason in reasons]
    for seconds, name in results.slow:
        out.append(f"SLOW  {seconds:.0f}s  {name}")
    ran = f"{results.tests} tests in {results.seconds:.0f}s" if results.tests else "tests up to date or not run"
    if cut_off:
        after = f"; the last test to finish was {last}" if last else ""
        why = (
            f"no test finished for {FROZEN_SECONDS}s, so the test JVM had frozen and was killed. "
            "That is the machine running short of memory, not a test failure: run it again"
            if cut_off == CutOff.FROZEN
            else "the run outlasted its time and was stopped"
        )
        out.append(f"{cut_off}  {why}{after}  full output: {log_path}")
    elif gradle_exit == 0:
        out.append(f"PASSED  {ran}")
    else:
        failed = f"{len(results.failures)} failed of {ran}" if results.tests else ran
        where = ", ".join(tasks) if tasks else "see the log"
        out.append(f"FAILED  {where}  ({failed})  full output: {log_path}")
    return "\n".join(out), 0 if gradle_exit == 0 and not cut_off else 1


def _sweep():
    stopped = stop_leftover(root=None, minutes=STALE_MINUTES)
    for w in stopped:
        print(f"STOPPED  test process {w.pid}, left running for {_age(w.minutes)} in {w.cwd}")
    print(f"{len(stopped)} leftover test processes stopped")
    return 0


def main(argv):
    parsed = parse_args(argv[1:])
    if parsed is None:
        print(__doc__, file=sys.stderr)
        return 2
    mode, names, minutes, rerun = parsed
    if mode == "sweep":
        return _sweep()
    root = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True, check=True
    ).stdout.strip()
    log_path = Path(root) / "app" / "build" / f"check-{mode}.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    command = ["./gradlew", "--console=plain", *gradle_tasks(mode, names, rerun)]
    stopped = stop_leftover(root, STALE_MINUTES)
    started = time.time()
    cut_off, last, gradle_exit = "", "", None
    in_progress = Path(root) / IN_PROGRESS
    with open(log_path, "w") as log:
        gradle = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        while cut_off == "":
            try:
                gradle_exit = gradle.wait(timeout=POLL_SECONDS)
                break
            except subprocess.TimeoutExpired:
                now = time.time()
                progress = in_progress.stat().st_mtime if in_progress.is_file() else None
                if frozen(progress, started, now):
                    cut_off = CutOff.FROZEN
                elif now - started > minutes * 60:
                    cut_off = CutOff.TIMED_OUT
        if cut_off:
            last = last_finished(in_progress.read_bytes()) if in_progress.is_file() else ""
            gradle.kill()
            gradle.wait()
    # The run is over, so any test process still in this checkout is one that didn't stop.
    stopped += stop_leftover(root, minutes=0)
    log_text = log_path.read_text(errors="replace")
    lint = Path(root) / LINT_REPORT
    if "lintDebug" in command and lint.is_file() and lint.stat().st_mtime >= started:
        log_text += "\n" + lint.read_text(errors="replace")
    report, code = verdict(
        gradle_exit,
        read_results(Path(root) / RESULTS, since=started),
        findings(log_text, root),
        failed_tasks(log_text),
        log_path,
        gradle_reasons(log_text),
        stopped,
        cut_off,
        last,
    )
    print(report)
    print(f"({time.time() - started:.0f}s: {' '.join(command)})")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
