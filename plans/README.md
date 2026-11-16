# Animation plans

Self-contained plans written by `improve-animations`. Each can be handed to any agent as is.

| # | Plan | Issue | Severity | Status |
| --- | --- | --- | --- | --- |
| 001 | [Replace the 700 ms screen crossfade with the 300 ms dip](001-screen-change-dip.md) | #424 | HIGH | TODO |
| 002 | [Side Panels slide in from the right edge](002-side-panel-slide.md) | #454 | MEDIUM | TODO |
| 003 | [The Next Episode card rises into the picture instead of popping in](003-next-episode-card-enter.md) | #455 | LOW | TODO |
| 004 | ["You're all set." rises in when Setup finishes](004-setup-finished-rise.md) | #456 | LOW | TODO |
| 005 | ["Still watching" fades the picture down instead of cutting to dark](005-still-watching-fade.md) | #457 | LOW | TODO |

## Order and dependencies

1. **001** first. It adds `ui/design/SetteeMotion.kt`, which later motion plans (Side Panel slide,
   Up Next card, Setup's finish) should reuse for `EaseOut` instead of defining their own curve.
2. **002** next. It adds `EaseDrawer` and `PANEL_SLIDE_MILLIS` to `SetteeMotion`. If 001 hasn't
   merged, 002 creates `SetteeMotion.kt` itself, and 001 then adds its values to that file
   instead of creating it. The two plans touch different files otherwise, so they can run in
   either order.
3. **003** any time. It only needs `SetteeMotion.EaseOut`, and adds it (or creates the file) if
   001 hasn't landed. It touches only `ui/vod/NextEpisodeOverlay.kt` and one line of `LOOK.md`.
4. **004** any time, on the same terms as 003. It touches only `ui/setup/SetupScreen.kt` and one
   line of `LOOK.md`.
5. **005** any time, on the same terms. It edits `ui/vod/NextEpisodeOverlay.kt` like 003, but a
   different function, so do the two one after the other, or merge carefully.

Each plan needs its own GitHub issue and branch off `main` (`feat(#issue): …`), and a measurement on
the Fire TV Stick before it merges (`docs/LOOK.md` → *Motion and cost*).
