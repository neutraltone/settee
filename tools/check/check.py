#!/usr/bin/env python3
"""Runs Gradle's tests and checks, and says what failed and why in a few lines.

Gradle's own output buries a failed test's reason in an HTML report, and stops at the first task
that fails, so finding everything wrong with a change takes a run per problem. This runs one
Gradle command, keeps its full output in a log, and prints only the result: each failed test with
its message and the line it failed on, each compiler, ktlint, detekt and lint finding, and the
tests slow enough to be worth a look. It exits with Gradle's own result, so nothing reading the
exit code can take a failed run for a passed one.

It also leaves no test process behind. On a Mac short of memory, a test JVM can freeze: one
thread gets stuck in the kernel re-faulting a page of compiled code, the rest of the JVM waits on
it for ever, and it ignores every signal but SIGKILL, Gradle's request to stop included. Left
alone it spins for hours, taking a core and its memory, which makes the next freeze likelier. So
a run in which no test finishes for two minutes is killed, as is one that outlasts its time, and
test processes left in this checkout by an abandoned earlier run are killed before the next starts.
Before killing a run it cuts off, it asks the test JVM for a thread dump. A frozen JVM can't answer,
and is reported as FROZEN: that is the machine, not the test it happened to stop in, so run it again.
One that answers was waiting for ever in a test, and is reported as HUNG with where it waits: fix it.
If the script itself is terminated, it stops the Gradle run it started first. It can't when it is
killed outright, so give whatever runs it longer than its own time, or run it in the background.

Usage, from anywhere in a checkout (a worktree included):
  tools/check/check.py test RecordingsContentTest [OtherTest ...]   the named test classes
  tools/check/check.py quick      ktlintFormat and detekt: after each change, before the long checks
  tools/check/check.py all        what a PR's checks run, in one pass that doesn't stop at the first failure
  tools/check/check.py ci         CI's `./gradlew test`, both variants, with Gradle's whole output printed
  tools/check/check.py sweep      kill test processes left running for over half an hour, in any checkout

--timeout MINUTES changes how long a run may take (test and quick: 8, all: 25, ci: 22); at least 1.
--rerun, with test, runs the tests even if nothing has changed since they last passed.
`all` runs the debug unit tests only; CI's `./gradlew test` runs the release variant's as well.
Run one Gradle command per checkout at a time: a run treats any other test process there as left over.
"""

import os
import re
import signal
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

RESULTS = "app/build/test-results/testDebugUnitTest"
RELEASE_RESULTS = "app/build/test-results/testReleaseUnitTest"
IN_PROGRESS = "binary/in-progress-results-generic.bin"
LINT_REPORT = "app/build/reports/lint-results-debug.txt"
PACKAGE = "app.opentv"
SLOW_SECONDS = 30.0
MESSAGE_LINES = 6

# Longer than any real run, so a test process this old was abandoned rather than still in use.
STALE_MINUTES = 30

# With no test finishing for this long, the test JVM has frozen: the slowest real test takes under 30s.
FROZEN_SECONDS = 120
# A CI runner starts with no Robolectric SDK jars, so the first test on each SDK waits on a download.
CI_FROZEN_SECONDS = 300
POLL_SECONDS = 2
# Long enough for a JVM that is merely busy to answer; one frozen in the kernel never does.
DUMP_SECONDS = 30
TOP_FRAMES = 12


class CutOff:
    """Why a run was stopped before Gradle finished."""

    TIMED_OUT = "TIMED OUT"
    FROZEN = "FROZEN"


@dataclass
class CutOffRun:
    """A run stopped before Gradle finished: why, the last test to finish, the thread dumps taken
    before killing it, and how long without a finished test counted as stopped."""

    reason: str
    last: str = ""
    dumps: list = field(default_factory=list)
    frozen_after: int = FROZEN_SECONDS



@dataclass(frozen=True)
class Mode:
    """What a mode runs and how it watches the run: the Gradle tasks, the minutes it may take, where
    its tests write results, how long without a finished test means it has stopped, and whether
    Gradle's output is printed as it runs rather than kept to the log."""

    tasks: tuple
    minutes: int
    results: tuple = (RESULTS,)
    frozen_seconds: int = FROZEN_SECONDS
    streams: bool = False


MODES = {
    "test": Mode(("testDebugUnitTest",), 8),
    "quick": Mode(("ktlintFormat", "detekt"), 8),
    "all": Mode(("testDebugUnitTest", "ktlintCheck", "detekt", "lintDebug", "--continue"), 25),
    # Both variants, like `./gradlew test`. Stops before the Unit tests step's own 25 minutes, so
    # the report and dumps still get uploaded; a CI log is read after the fact, so it streams.
    "ci": Mode(("test", "--stacktrace"), 22, (RESULTS, RELEASE_RESULTS), CI_FROZEN_SECONDS, streams=True),
    "sweep": Mode((), 1),
}


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
class Dump:
    """A thread dump taken of a test process before killing it: where it was saved, whether the
    process answered at all, and the threads that were in a test when it did."""

    pid: int
    path: str | None = None
    stuck: str = ""

    @property
    def answered(self):
        return self.path is not None


@dataclass
class Worker:
    """A Gradle test process: how many minutes it has run, the directory it runs in, and its
    command line (which names the java it runs)."""

    pid: int
    minutes: float
    cwd: str
    command: str = field(default="", compare=False)


def read_results(directories, since):
    """This run's unit test results, from each of its results directories: Gradle leaves an earlier
    run's files behind when it fails before the tests start, so anything written before `since`
    isn't counted."""
    results = Results()
    for path in [path for directory in directories for path in sorted(Path(directory).glob("TEST-*.xml"))]:
        if path.stat().st_mtime < since:
            continue
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            # Cut off as Gradle wrote it. The rest of the report matters more than this file.
            continue
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


def modified(record):
    """When Gradle last recorded a test in `record`, or None if it isn't there: Gradle renames the
    record away as the tests end, which can happen between looking for it and reading it."""
    try:
        return record.stat().st_mtime
    except OSError:
        return None


def read_record(record):
    try:
        return record.read_bytes()
    except OSError:
        return b""


def frozen(last_progress, started, now, limit=FROZEN_SECONDS):
    """Whether a run has frozen: its tests have started (`last_progress` is when one last finished,
    None before any has) and none has finished for `limit` seconds. Progress from before `started`
    is an earlier run's, left behind while this one compiles."""
    return last_progress is not None and last_progress >= started and now - last_progress > limit


def jcmd_for(command):
    """The jcmd beside the java a test process runs, so it matches that JVM and needs no PATH."""
    return str(Path(command.split()[0]).with_name("jcmd"))


def stuck_in(dump):
    """The threads of a thread dump that are in one of this project's tests, at most a screenful
    of each: its top frames, which on a hang are what it waits on, and the test's own frame."""
    test_frame = re.compile(rf"^\tat {re.escape(PACKAGE)}\.[\w.$]*Test\b", flags=re.MULTILINE)
    threads = []
    for block in re.split(r"\n\s*\n", dump):
        lines = block.strip("\n").splitlines()
        if not lines or not lines[0].startswith('"') or not test_frame.search(block):
            continue
        frames = [line for line in lines[1:] if line.startswith("\tat ")]
        last = next(at for at, line in enumerate(frames) if test_frame.match(line))
        shown = frames[:TOP_FRAMES] if last < TOP_FRAMES else [*frames[:TOP_FRAMES], "\t...", frames[last]]
        threads.append("\n".join([lines[0], *shown]))
    return "\n\n".join(threads)


def dump_threads(root, out_dir):
    """A thread dump of each test process in `root`'s checkout, saved in `out_dir`. A JVM stuck in
    a test answers with where it waits; one frozen in the kernel can't reach the point where it
    would, so no answer is the machine and an answer is the test."""
    listed = subprocess.run(["ps", "-axo", "pid=,etime=,command="], capture_output=True, text=True).stdout
    return [_dump(worker, out_dir) for worker in leftover(test_workers(listed, _cwd_of), root, minutes=0)]


def _dump(worker, out_dir):
    try:
        answer = subprocess.run(
            [jcmd_for(worker.command), str(worker.pid), "Thread.print", "-l"],
            capture_output=True,
            text=True,
            timeout=DUMP_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return Dump(worker.pid)
    if answer.returncode != 0 or "Full thread dump" not in answer.stdout:
        return Dump(worker.pid)
    path = Path(out_dir) / f"threads-{worker.pid}.txt"
    path.write_text(answer.stdout)
    return Dump(worker.pid, str(path), stuck_in(answer.stdout))


def test_workers(ps_output, cwd_of):
    """Gradle's test processes in `ps -axo pid=,etime=,command=` output; `cwd_of` gives a pid's directory."""
    workers = []
    for line in ps_output.splitlines():
        match = re.match(r"\s*(\d+)\s+(\S+)\s+(.*)", line)
        if not match or "Gradle Test Executor" not in match.group(3):
            continue
        pid = int(match.group(1))
        workers.append(Worker(pid, _minutes(match.group(2)), cwd_of(pid) or "", match.group(3)))
    return workers


def _minutes(elapsed):
    """ps's elapsed time, [[days-]hours:]minutes:seconds, in minutes."""
    days, _, clock = elapsed.rpartition("-")
    parts = [int(part) for part in clock.split(":")]
    hours, minutes, seconds = [0] * (3 - len(parts)) + parts
    return round(int(days or 0) * 1440 + hours * 60 + minutes + seconds / 60, 1)


def leftover(workers, root, minutes, ours=lambda cwd: True):
    """The test processes to kill: those in `root`'s checkout that have run for at least `minutes`.
    With no `root`, those in any checkout `ours` says is this project's, so a sweep never kills
    another project's tests. One whose directory can't be read is never taken for a checkout's."""
    if root:
        return [w for w in workers if w.minutes >= minutes and w.cwd == f"{root}/app"]
    return [w for w in workers if w.minutes >= minutes and ours(w.cwd)]


def is_ours(cwd):
    """Whether a test process's directory is the `app` module of a checkout of this project."""
    return bool(cwd) and Path(cwd, "src", "main", "java", *PACKAGE.split(".")).is_dir()


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
    for worker in leftover(test_workers(listed, _cwd_of), root, minutes, is_ours):
        try:
            os.kill(worker.pid, signal.SIGKILL)
            stopped.append(worker)
        except OSError:
            pass
    return stopped


def gradle_tasks(mode, names, rerun=False):
    tasks = list(MODES[mode].tasks)
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
        if at + 1 >= len(args) or not args[at + 1].isdigit() or int(args[at + 1]) == 0:
            return None
        minutes = int(args[at + 1])
        del args[at : at + 2]
    if not args or args[0] not in MODES or (args[0] == "test") != (len(args) > 1):
        return None
    if rerun and args[0] != "test":
        return None
    return args[0], args[1:], minutes or MODES[args[0]].minutes, rerun


def _age(minutes):
    return f"{minutes / 60:.0f}h" if minutes >= 120 else f"{minutes:.0f}m"


def verdict(
    gradle_exit,
    results,
    found,
    tasks,
    log_path,
    reasons=(),
    stopped=(),
    cut_off=None,
):
    """What to print, and the exit code: Gradle's, whatever else was or wasn't found. A run that was
    cut off has no exit code and has failed; the thread dumps taken before killing it say whether a
    test was waiting for ever (it answered) or the JVM had frozen (it couldn't)."""
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
        out += _cut_off(cut_off, log_path)
    elif gradle_exit == 0:
        out.append(f"PASSED  {ran}")
    else:
        failed = f"{len(results.failures)} failed of {ran}" if results.tests else ran
        where = ", ".join(tasks) if tasks else "see the log"
        out.append(f"FAILED  {where}  ({failed})  full output: {log_path}")
    return "\n".join(out), 0 if gradle_exit == 0 and not cut_off else 1


def _cut_off(run, log_path):
    """Why a cut-off run was stopped. A JVM that answered a thread dump was waiting in a test for
    ever, so it is the test; one that couldn't had frozen, which is the machine."""
    after = f"; the last test to finish was {run.last}" if run.last else ""
    answered = [dump for dump in run.dumps if dump.answered]
    stalled = f"no test finished for {run.frozen_after}s"
    if answered:
        stalled = stalled if run.reason == CutOff.FROZEN else "the run outlasted its time"
        out = [
            f"HUNG  {stalled}, and the test JVM answered a thread dump, so a test is waiting for ever: "
            f"it is the test, not the machine{after}  full output: {log_path}"
        ]
        for dump in answered:
            out.append(f"    thread dump of test process {dump.pid}: {dump.path}")
            out += [f"    {line}" for line in (dump.stuck or "no thread was in a test; see the dump").splitlines()]
        return out
    silent = " (it didn't answer a thread dump)" if run.dumps else ""
    why = (
        f"{stalled}, so the test JVM had frozen and was killed{silent}. "
        "That is the machine running short of memory, not a test failure: run it again"
        if run.reason == CutOff.FROZEN
        else "the run outlasted its time and was stopped"
    )
    return [f"{run.reason}  {why}{after}  full output: {log_path}"]


def _sweep():
    stopped = stop_leftover(root=None, minutes=STALE_MINUTES)
    for w in stopped:
        print(f"STOPPED  test process {w.pid}, left running for {_age(w.minutes)} in {w.cwd}")
    print(f"{len(stopped)} leftover test processes stopped")
    return 0


def _copy_output(source, log):
    """Copies Gradle's output to the log and to this script's own output as it comes."""

    def copy():
        for line in iter(source.readline, b""):
            log.write(line.decode(errors="replace"))
            log.flush()
            sys.stdout.write(line.decode(errors="replace"))
            sys.stdout.flush()

    copying = threading.Thread(target=copy, daemon=True)
    copying.start()
    return copying


def _terminated(signum, _frame):
    raise SystemExit(128 + signum)


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
    settings = MODES[mode]
    cut_off, gradle_exit = None, None
    results = [Path(root) / directory for directory in settings.results]
    in_progress = [directory / IN_PROGRESS for directory in results]
    for stop in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(stop, _terminated)
    with open(log_path, "w") as log:
        gradle = subprocess.Popen(
            command, cwd=root, stdout=subprocess.PIPE if settings.streams else log, stderr=subprocess.STDOUT
        )
        copying = _copy_output(gradle.stdout, log) if settings.streams else None
        try:
            while cut_off is None:
                try:
                    gradle_exit = gradle.wait(timeout=POLL_SECONDS)
                    break
                except subprocess.TimeoutExpired:
                    now = time.time()
                    progress = [at for at in map(modified, in_progress) if at is not None]
                    if frozen(max(progress, default=None), started, now, settings.frozen_seconds):
                        cut_off = CutOffRun(CutOff.FROZEN, frozen_after=settings.frozen_seconds)
                    elif now - started > minutes * 60:
                        cut_off = CutOffRun(CutOff.TIMED_OUT, frozen_after=settings.frozen_seconds)
        except BaseException:
            # Terminated or interrupted: after this nothing is left to stop what this started.
            gradle.kill()
            gradle.wait()
            stop_leftover(root, minutes=0)
            raise
        if cut_off:
            cut_off.last = next(filter(None, (last_finished(read_record(record)) for record in in_progress)), "")
            cut_off.dumps = dump_threads(root, log_path.parent)
            gradle.kill()
            gradle.wait()
        if copying:
            # The Gradle daemon can hold the pipe open after the client has gone, so don't wait on it for ever.
            copying.join(timeout=10)
    # The run is over, so any test process still in this checkout is one that didn't stop.
    stopped += stop_leftover(root, minutes=0)
    log_text = log_path.read_text(errors="replace")
    lint = Path(root) / LINT_REPORT
    if "lintDebug" in command and lint.is_file() and lint.stat().st_mtime >= started:
        log_text += "\n" + lint.read_text(errors="replace")
    report, code = verdict(
        gradle_exit,
        read_results(results, since=started),
        findings(log_text, root),
        failed_tasks(log_text),
        log_path,
        gradle_reasons(log_text),
        stopped,
        cut_off,
    )
    print(report)
    print(f"({time.time() - started:.0f}s: {' '.join(command)})")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
