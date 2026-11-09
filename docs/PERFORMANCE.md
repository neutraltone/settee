# Performance on the Fire TV Stick

The baseline every later visual decision is checked against (#84). It says how Settee performs today; what it can afford is the budget in [LOOK.md](LOOK.md#motion-and-cost), set in #156. Rerun the method after a change and compare it with the tables below.

## What was measured

| | |
|---|---|
| Stick | Model code `AFTSS` (`sheldon`). Amazon lists this code for both the Fire TV Stick Lite (2020) and the Stick HD (2024), so it doesn't say which one this is |
| Hardware | MT8695, PowerVR GE8300, 922 MB usable RAM, 1920x1080, 60 Hz |
| Software | Fire OS 7.7.1.7, API 28 |
| Build | Release, not debuggable, version 0.4.0, from `244a3f9`, compiled `speed-profile`. The APK's baseline profile comes only from its libraries: Settee has no profile of its own |
| Catalogue | 16,830 channels, with a guide for 3,034 of them. Movie and series counts weren't recorded |
| Date | 2026-11-05 |

**What's missing:** Home had four rows of new titles and no Continue Watching or Favourites rows, so those cards haven't been measured.

## Results

A frame has 16.7 ms at 60 Hz. Each table gives the median across runs, except the worst frame and response, which are the worst of any run.

### Frame timing

| Moving | Runs | Frames per run | Janky | p50 | p95 | p99 | Worst |
|---|---|---|---:|---:|---:|---:|---:|
| Home, along a row, 250 ms between presses | 5 | 353 | 1.7% | 9 ms | 14 ms | 17 ms | 21 ms |
| Home, between rows, 500 ms between presses | 5 | 76 | 43% | 13 ms | 44 ms | 57 ms | 61 ms |
| Home, holding Right for 2 s | 3 | 96 | 26% | 14 ms | 20 ms | 25 ms | 30 ms |
| Guide, between channels, 250 ms between presses | 5 | 560 | 79% | 20 ms | 40 ms | 61 ms | 113 ms |
| Guide, holding Down for 2 s | 3 | 33 | 63% | 19 ms | 85 ms | 101 ms | 800 ms |

The held runs varied the most: jank on Home ranged from 15% to 82%, and the guide's p95 from 73 ms to 550 ms.

### Highlight response

From the key press to the first frame drawn after it, one press at a time with 1 s between presses.

| Moving | Presses | Median | Worst |
|---|---:|---:|---:|
| Home, along a row | 20 | 27 ms | 57 ms |
| Home, between rows | 12 | 25 ms | 32 ms |
| Guide, between channels | 20 | 54 ms | 84 ms |

**Margin of error:** the key event's time is in whole milliseconds, and a finished frame can wait up to one more 16.7 ms frame to leave the Stick. The remote's radio and the TV's own lag come on top and weren't measured, so these figures are the Stick's part of the delay, not what a viewer sees in total. Nor do they prove the highlight had visibly moved by that frame, only that the frame came after the press. In a one-off press-by-press check, the movement between Home rows took about 320 ms to finish drawing.

### Cold start

Seven runs from a force-stop, on a device with the app's files already cached, opening on Home.

| | Median | Range |
|---|---:|---:|
| First frame (Android's `Displayed` time) | 651 ms | 643-1,254 ms |
| Home stops drawing | 2.85 s | 2.72-4.36 s |

The slowest of both was the first run. Memory at Home after a cold start was 86 MB (PSS), with 296 MB available on the device.

## What stands out

- **The guide is the weakest screen.** Most of its frames miss the deadline, and holding Down draws about a third as many frames as holding Right on Home.
- **Moving between Home rows costs far more than moving along one:** 43% janky against 1.7%.
- **The Stick stays busy after the guide finishes building.** `EpgRepository.runMatcher` writes each channel's match as its own update and Home re-runs its queries after them. The app used 1-1.3 cores for at least 3.5 minutes after the "Building the TV guide" bar had gone, with nothing on screen to say so. Scrolling wasn't measured during this.
- **A debug build is no guide.** Its first launch drew its first frame in 12.3 s, against 1.5 s for a release build's first launch, both with no provider added.

## How to measure

1. Build from `main` with `./gradlew assembleRelease` and install it. A local release build is a separate app from the debug build, with its own data, and both are called Settee on the launcher. `dumpsys activity activities | grep mResumedActivity` says which is in front.
2. Add a provider and fill Home, and record what's on it and the catalogue's size, along with the commit. This baseline's Home is described above, so note any difference. Wait until the app is idle, because the guide matcher runs on after its progress bar has gone: `/proc/<pid>/stat` fields 14 and 15 should stop rising. The last channel count is in `adb logcat -d -s EpgRepository:I`.
3. Stop the debug build so it isn't taking memory: `adb shell am force-stop uk.neutraltone.settee.debug`.
4. Put the highlight in place and check with a screenshot, before and after each run:

   ```bash
   adb exec-out screencap -p > position.png
   ```

   Every key pattern must end where it began. A held Left that runs past the first card lands on the rail, and the rest of the run then measures the rail.

5. Run the measurements, with cold start last because it restarts the app. These are for the bottom row of Home:

   ```bash
   export ANDROID_SERIAL=<stick-ip>:5555
   tools/perf/stick_baseline.py highlight --label home-along-row --keys 'RIGHT*10,LEFT*10'
   tools/perf/stick_baseline.py highlight --label home-between-rows --keys 'UP*3,DOWN*3,UP*3,DOWN*3'
   tools/perf/stick_baseline.py scroll --label home-along-row --keys 'RIGHT*12,LEFT*12' --gap-ms 250 --runs 5
   tools/perf/stick_baseline.py scroll --label home-between-rows --keys 'UP*3,DOWN*3' --gap-ms 500 --runs 5
   tools/perf/stick_baseline.py scroll --label home-held-right --keys RIGHT --hold-ms 2000 --gap-ms 800 --runs 1
   ```

   In the guide, with the highlight on the first channel of a long category and the Live Preview playing:

   ```bash
   tools/perf/stick_baseline.py highlight --label guide --keys 'DOWN*10,UP*10'
   tools/perf/stick_baseline.py scroll --label guide --keys 'DOWN*20,UP*20' --gap-ms 250 --runs 5
   tools/perf/stick_baseline.py scroll --label guide-held-down --keys DOWN --hold-ms 2000 --gap-ms 800 --runs 1
   tools/perf/stick_baseline.py cold-start --runs 7 --settle-s 20
   ```

   Repeat the held runs three times, moving back between them without measuring: hold the opposite key for slightly less time, so it can't overshoot.

6. Check that `dexopt` in each result matches the baseline's, as it affects cold start and early scrolling. The tables take the median of each column across a command's `runs`, which the script doesn't do for you except for jank and p95. The worst frame is the worst of any run.

The script doesn't produce the 320 ms row movement, the memory figures or the debug comparison, which were one-off checks: `dumpsys gfxinfo <package> framestats` after a press, `dumpsys meminfo <package>` and `/proc/meminfo`, and Android's `Displayed` log line.

## Reading the numbers

- **Frame timing** comes from the app-wide `dumpsys gfxinfo` summary, whose histogram has coarse buckets at the top, and "janky" is Android's own count. The summary also counts "high input latency", but that's left out here because it flagged most frames in every run, even the smooth ones.
- **Highlight response** uses the time on the key event itself. A press that draws nothing leaves its time on the next frame, which once read as a 27.7 s response, so anything older than the press is timed from a clock read just before it (`timed_from_clock`), a slight overestimate. One press in these results was timed that way.
- **A press with `no_frame`** drew nothing, which usually means the highlight had nowhere to go. There were none in these results.
- **Presses** are real key events on the remote's own input device, sent with `sendevent`. `adb shell input keyevent` took 0.77-1.18 s per call here, too slow for a realistic pace.
- **Screenshots** taken with `screencap` can show provider artwork and the phone setup code, so they stay out of the repo, and the script prints no raw adb output.
