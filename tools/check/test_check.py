"""Tests for check.py: what it reads out of Gradle's results, and the result it reports."""

import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

import check

PASSING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="app.opentv.QuickTest" tests="2" skipped="0" failures="0" errors="0" time="0.4">
  <testcase name="adds" classname="app.opentv.QuickTest" time="0.1"/>
  <testcase name="subtracts" classname="app.opentv.QuickTest" time="0.3"/>
</testsuite>
"""

FAILING = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="app.opentv.RowTest" tests="2" skipped="0" failures="1" errors="0" time="61.5">
  <testcase name="the row is one stop" classname="app.opentv.RowTest" time="0.5"/>
  <testcase name="OK plays it" classname="app.opentv.RowTest" time="61.0">
    <failure message="expected: [a]" type="java.lang.AssertionError">expected: [a]
but was : []
	at com.google.common.truth.Subject.isEqualTo(Subject.java:1)
	at app.opentv.RowTest.OK plays it(RowTest.kt:42)
	at java.base/java.lang.reflect.Method.invoke(Method.java:569)
</failure>
  </testcase>
</testsuite>
"""


class ResultsTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def write(self, name, xml, mtime=None):
        path = self.dir / name
        path.write_text(xml)
        if mtime is not None:
            os.utime(path, (mtime, mtime))

    def test_counts_tests_and_names_each_failure_with_where_it_failed(self):
        self.write("TEST-app.opentv.QuickTest.xml", PASSING)
        self.write("TEST-app.opentv.RowTest.xml", FAILING)

        results = check.read_results(self.dir, since=0)

        self.assertEqual(results.tests, 4)
        self.assertEqual(len(results.failures), 1)
        failure = results.failures[0]
        self.assertEqual(failure.test, "RowTest > OK plays it")
        self.assertEqual(failure.message, "expected: [a]\nbut was : []")
        self.assertEqual(failure.where, "RowTest.kt:42")

    def test_results_left_by_an_earlier_run_are_not_this_runs(self):
        self.write("TEST-app.opentv.RowTest.xml", FAILING, mtime=1_000)

        results = check.read_results(self.dir, since=2_000)

        self.assertEqual(results.tests, 0)
        self.assertEqual(results.failures, [])

    def test_names_the_tests_slow_enough_to_be_worth_a_look(self):
        self.write("TEST-app.opentv.QuickTest.xml", PASSING)
        self.write("TEST-app.opentv.RowTest.xml", FAILING)

        results = check.read_results(self.dir, since=0)

        self.assertEqual(results.slow, [(61.0, "RowTest > OK plays it")])

    def test_a_missing_results_directory_is_no_results(self):
        results = check.read_results(self.dir / "never-ran", since=0)

        self.assertEqual(results.tests, 0)

    def test_a_result_file_cut_off_mid_write_is_skipped_rather_than_losing_the_report(self):
        self.write("TEST-app.opentv.QuickTest.xml", PASSING)
        self.write("TEST-app.opentv.RowTest.xml", FAILING[:200])

        results = check.read_results(self.dir, since=0)

        self.assertEqual(results.tests, 2)


class FindingsTest(unittest.TestCase):
    ROOT = "/work/settee"

    def test_compiler_errors_are_shown_from_the_repository_root(self):
        log = (
            "> Task :app:compileDebugKotlin FAILED\n"
            "e: file:///work/settee/app/src/main/java/app/opentv/MainActivity.kt:557:85 Unresolved reference 'Color'.\n"
            "w: file:///work/settee/app/src/main/java/app/opentv/Old.kt:1:1 Deprecated.\n"
        )

        self.assertEqual(
            check.findings(log, self.ROOT),
            ["app/src/main/java/app/opentv/MainActivity.kt:557:85 Unresolved reference 'Color'."],
        )

    def test_detekt_and_ktlint_findings_are_shown_once_each(self):
        line = (
            "/work/settee/app/src/main/java/app/opentv/ui/Row.kt:291:27: "
            "The function line appears to be too complex. [CyclomaticComplexMethod]"
        )
        log = f"{line}\n{line}\nBUILD FAILED in 49s\n"

        self.assertEqual(
            check.findings(log, self.ROOT),
            ["app/src/main/java/app/opentv/ui/Row.kt:291:27: The function line appears to be too complex. "
             "[CyclomaticComplexMethod]"],
        )

    def test_lint_errors_are_findings_and_its_warnings_are_not(self):
        report = (
            "/work/settee/app/src/main/res/values-de/strings.xml:12: Error: \"rec_retry\" is translated here "
            "but not found in default locale [ExtraTranslation]\n"
            "    <string name=\"rec_retry\">Nochmal</string>\n"
            "/work/settee/app/src/main/java/app/opentv/A.kt:3: Warning: Something minor [Minor]\n"
        )

        self.assertEqual(
            check.findings(report, self.ROOT),
            ["app/src/main/res/values-de/strings.xml:12: Error: \"rec_retry\" is translated here "
             "but not found in default locale [ExtraTranslation]"],
        )

    def test_names_the_tasks_that_failed(self):
        log = "> Task :app:detekt FAILED\n> Task :app:lintDebug\n> Task :app:testDebugUnitTest FAILED\n"

        self.assertEqual(check.failed_tasks(log), [":app:detekt", ":app:testDebugUnitTest"])


    def test_gradles_own_reason_is_kept_for_when_nothing_else_explains_a_failure(self):
        log = (
            "FAILURE: Build failed with an exception.\n\n"
            "* What went wrong:\n"
            "Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)\n\n"
            "* Try:\n> Run with --stacktrace option to get the stack trace.\n"
        )

        self.assertEqual(
            check.gradle_reasons(log),
            ["Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)"],
        )


class VerdictTest(unittest.TestCase):
    def test_gradle_failing_fails_the_check_even_with_nothing_to_show_for_it(self):
        report, code = check.verdict(gradle_exit=1, results=check.Results(), found=[], tasks=[], log_path="x.log")

        self.assertEqual(code, 1)
        self.assertIn("FAILED", report)
        self.assertIn("x.log", report)

    def test_a_failure_with_no_test_or_finding_to_blame_gives_gradles_reason(self):
        report, code = check.verdict(
            gradle_exit=1,
            results=check.Results(),
            found=[],
            tasks=[],
            log_path="x.log",
            reasons=["Gradle build daemon disappeared unexpectedly"],
        )

        self.assertEqual(code, 1)
        self.assertIn("GRADLE  Gradle build daemon disappeared unexpectedly", report)

    def test_gradles_reason_is_left_out_when_a_failed_test_already_explains_it(self):
        results = check.Results(tests=1, failures=[check.Failure("RowTest > OK plays it", "expected: [a]", "")])

        report, _ = check.verdict(
            gradle_exit=1,
            results=results,
            found=[],
            tasks=[":app:testDebugUnitTest"],
            log_path="x.log",
            reasons=["Execution failed for task ':app:testDebugUnitTest'."],
        )

        self.assertNotIn("GRADLE", report)

    def test_a_run_cut_off_fails_and_names_the_last_test_that_finished(self):
        report, code = check.verdict(
            gradle_exit=None,
            results=check.Results(),
            found=[],
            tasks=[],
            log_path="x.log",
            cut_off=check.CutOff.TIMED_OUT,
            last="LiveBrowseTest > while the rows load, it stays put",
        )

        self.assertEqual(code, 1)
        self.assertIn("TIMED OUT", report)
        self.assertIn("last test to finish was LiveBrowseTest > while the rows load, it stays put", report)

    def test_a_frozen_run_fails_and_says_the_machine_not_the_test_is_to_blame(self):
        report, code = check.verdict(
            gradle_exit=None,
            results=check.Results(),
            found=[],
            tasks=[],
            log_path="x.log",
            cut_off=check.CutOff.FROZEN,
            last="LauncherArtworkTest > keeps the adaptive icon",
        )

        self.assertEqual(code, 1)
        self.assertIn("FROZEN", report)
        self.assertIn("not a test failure", report)
        self.assertIn("LauncherArtworkTest > keeps the adaptive icon", report)

    def test_says_which_leftover_test_processes_it_stopped(self):
        report, _ = check.verdict(
            gradle_exit=0,
            results=check.Results(tests=1),
            found=[],
            tasks=[],
            log_path="x.log",
            stopped=[check.Worker(54904, 1560.0, "/work/settee/app")],
        )

        self.assertIn("STOPPED  test process 54904, left running for 26h in /work/settee/app", report)

    def test_a_passing_run_says_how_many_tests_ran(self):
        results = check.Results(tests=12, seconds=3.0)

        report, code = check.verdict(gradle_exit=0, results=results, found=[], tasks=[], log_path="x.log")

        self.assertEqual(code, 0)
        self.assertIn("PASSED", report)
        self.assertIn("12 tests", report)

    def test_a_passing_run_that_ran_nothing_new_says_the_tests_were_up_to_date(self):
        report, code = check.verdict(gradle_exit=0, results=check.Results(), found=[], tasks=[], log_path="x.log")

        self.assertEqual(code, 0)
        self.assertIn("up to date", report)

    def test_a_failing_run_leads_with_each_failed_test_and_finding(self):
        results = check.Results(
            tests=2,
            failures=[check.Failure("RowTest > OK plays it", "expected: [a]", "RowTest.kt:42")],
        )

        report, code = check.verdict(
            gradle_exit=1,
            results=results,
            found=["app/A.kt:1:1: Too complex. [X]"],
            tasks=[":app:detekt", ":app:testDebugUnitTest"],
            log_path="x.log",
        )

        self.assertEqual(code, 1)
        self.assertIn("RowTest > OK plays it (RowTest.kt:42)", report)
        self.assertIn("expected: [a]", report)
        self.assertIn("app/A.kt:1:1: Too complex. [X]", report)
        self.assertIn(":app:detekt", report)


PS = """\
  102 03:26:31 /usr/bin/java -Dx=1 worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 20'
  731    12:05 /usr/bin/java -Dx=1 worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 15'
54904 1-02:00:00 /usr/bin/java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Test Executor 14'
96717 03:44:09 /usr/bin/java org.gradle.launcher.daemon.bootstrap.GradleDaemon 9.9.0
  900    00:10 /usr/bin/java worker.org.gradle.process.internal.worker.GradleWorkerMain 'Gradle Worker Daemon 3'
"""

CWDS = {102: "/work/settee-168/app", 731: "/work/settee/app", 54904: "/work/settee/app"}


class WorkersTest(unittest.TestCase):
    def workers(self):
        return check.test_workers(PS, CWDS.get)

    def test_finds_test_executors_with_how_long_they_have_run_and_where(self):
        self.assertEqual(
            self.workers(),
            [
                check.Worker(102, 206.5, "/work/settee-168/app"),
                check.Worker(731, 12.1, "/work/settee/app"),
                check.Worker(54904, 1560.0, "/work/settee/app"),
            ],
        )

    def test_a_checkouts_own_leftovers_are_the_ones_running_in_it(self):
        mine = check.leftover(self.workers(), root="/work/settee", minutes=0)

        self.assertEqual([worker.pid for worker in mine], [731, 54904])

    def test_before_a_run_only_ones_too_old_to_be_someone_elses_run_count(self):
        stale = check.leftover(self.workers(), root="/work/settee", minutes=30)

        self.assertEqual([worker.pid for worker in stale], [54904])

    def test_a_sweep_takes_stale_ones_from_every_checkout(self):
        stale = check.leftover(self.workers(), root=None, minutes=30)

        self.assertEqual([worker.pid for worker in stale], [102, 54904])

    def test_a_worker_whose_directory_cant_be_read_is_never_taken_for_this_checkouts(self):
        workers = check.test_workers(PS, lambda pid: None)

        self.assertEqual(check.leftover(workers, root="/work/settee", minutes=0), [])

    def test_a_sweep_leaves_other_projects_test_processes_alone(self):
        workers = self.workers() + [check.Worker(900, 300.0, "/work/other/app")]

        stale = check.leftover(workers, root=None, minutes=30, ours=lambda cwd: "/settee" in cwd)

        self.assertEqual([worker.pid for worker in stale], [102, 54904])

    def test_this_projects_checkouts_are_known_by_their_sources(self):
        work = Path(tempfile.mkdtemp())
        (work / "settee-168/app/src/main/java/app/opentv").mkdir(parents=True)
        (work / "other/app/src/main/java/com/example").mkdir(parents=True)

        self.assertTrue(check.is_ours(str(work / "settee-168/app")))
        self.assertFalse(check.is_ours(str(work / "other/app")))
        self.assertFalse(check.is_ours(""))


class RecordTest(unittest.TestCase):
    def setUp(self):
        self.record = Path(tempfile.mkdtemp()) / "in-progress-results-generic.bin"

    def test_says_when_gradle_last_recorded_a_test(self):
        self.record.write_bytes(b"app.opentv.QuickTest")
        os.utime(self.record, (1_234, 1_234))

        self.assertEqual(check.modified(self.record), 1_234)
        self.assertEqual(check.read_record(self.record), b"app.opentv.QuickTest")

    def test_a_record_gradle_has_moved_away_is_no_progress_rather_than_a_crash(self):
        self.assertIsNone(check.modified(self.record))
        self.assertEqual(check.read_record(self.record), b"")


def _kill(pid):
    try:
        os.kill(pid, 9)
    except OSError:
        pass


class InterruptedTest(unittest.TestCase):
    def test_a_check_that_is_terminated_stops_the_gradle_run_it_started(self):
        root = Path(tempfile.mkdtemp())
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        gradlew = root / "gradlew"
        gradlew.write_text("#!/bin/sh\necho $$ > started\nexec sleep 60\n")
        gradlew.chmod(0o755)
        script = Path(check.__file__).resolve()
        run = subprocess.Popen(
            [sys.executable, str(script), "quick"], cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        started = root / "started"
        deadline = time.time() + 10
        while not (started.is_file() and started.read_text().strip()) and time.time() < deadline:
            time.sleep(0.05)
        gradle = int(started.read_text())
        self.addCleanup(_kill, gradle)

        run.terminate()
        run.wait(timeout=10)

        with self.assertRaises(ProcessLookupError):
            os.kill(gradle, 0)


class LastFinishedTest(unittest.TestCase):
    def test_reads_the_last_test_gradle_recorded_finishing(self):
        recorded = (
            b"\x00\x15app.opentv.QuickTest\x00\x04adds\x01\x02"
            b"\x00\x18app.opentv.LiveBrowseTest\x00\x05while the rows load, it stays put\x07\x00"
        )

        self.assertEqual(check.last_finished(recorded), "LiveBrowseTest > while the rows load, it stays put")

    def test_nothing_recorded_is_nothing_to_name(self):
        self.assertEqual(check.last_finished(b""), "")


class FrozenTest(unittest.TestCase):
    """A run is frozen when its tests have started and none has finished for too long."""

    def test_a_run_whose_last_test_finished_long_ago_is_frozen(self):
        self.assertTrue(check.frozen(last_progress=1_000, started=900, now=1_000 + check.FROZEN_SECONDS + 1))

    def test_a_run_still_finishing_tests_is_not(self):
        self.assertFalse(check.frozen(last_progress=1_000, started=900, now=1_030))

    def test_a_run_that_hasnt_reached_its_tests_is_not_however_long_it_compiles(self):
        self.assertFalse(check.frozen(last_progress=None, started=900, now=9_000))

    def test_progress_left_by_an_earlier_run_doesnt_count_against_this_one(self):
        self.assertFalse(check.frozen(last_progress=500, started=900, now=9_000))


class CommandTest(unittest.TestCase):
    def test_test_mode_runs_only_the_named_tests(self):
        self.assertEqual(
            check.gradle_tasks("test", ["RecordingsContentTest", "app.opentv.M3uParserTest"]),
            ["testDebugUnitTest", "--tests", "app.opentv.RecordingsContentTest", "--tests", "app.opentv.M3uParserTest"],
        )

    def test_quick_mode_formats_then_runs_the_static_check_that_fails_soonest(self):
        self.assertEqual(check.gradle_tasks("quick", []), ["ktlintFormat", "detekt"])

    def test_a_run_gets_long_enough_to_finish_and_no_longer(self):
        self.assertEqual(check.parse_args(["test", "QuickTest"]), ("test", ["QuickTest"], 8, False))
        self.assertEqual(check.parse_args(["all"]), ("all", [], 25, False))
        self.assertEqual(check.parse_args(["all", "--timeout", "40"]), ("all", [], 40, False))

    def test_rerun_runs_tests_gradle_would_call_up_to_date(self):
        self.assertEqual(check.parse_args(["test", "--rerun", "QuickTest"]), ("test", ["QuickTest"], 8, True))
        self.assertEqual(
            check.gradle_tasks("test", ["QuickTest"], rerun=True),
            ["testDebugUnitTest", "--rerun", "--tests", "app.opentv.QuickTest"],
        )

    def test_rerun_is_only_for_tests(self):
        self.assertIsNone(check.parse_args(["quick", "--rerun"]))

    def test_a_run_allowed_no_time_at_all_is_refused(self):
        self.assertIsNone(check.parse_args(["quick", "--timeout", "0"]))

    def test_a_mode_it_doesnt_know_or_a_test_run_with_no_tests_is_refused(self):
        self.assertIsNone(check.parse_args(["everything"]))
        self.assertIsNone(check.parse_args(["test"]))
        self.assertIsNone(check.parse_args([]))

    def test_all_mode_runs_every_pr_check_without_stopping_at_the_first_failure(self):
        self.assertEqual(
            check.gradle_tasks("all", []),
            ["testDebugUnitTest", "ktlintCheck", "detekt", "lintDebug", "--continue"],
        )


if __name__ == "__main__":
    unittest.main()
