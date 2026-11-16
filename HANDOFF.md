# SideSpeed handoff (2026-11-15)

## Where things stand
- Branch `feature/m2-companion` (not pushed, not merged; `main` is at `fce44f9`). HEAD `5c24d41`.
- **M2 Companion is built and device-verified** (Mac + Fire TV Cube, phone first run, reboot, stale helper). All 11 tasks done.
- **UI polish done**: `Ss*` components (press/hover/focus feedback, transitions, status dots, spinner, empty state), app icons for mac/win/linux, owner confirmed phone APK and Dock icon look great. Full suite green. Self-reviewed; 2 minors deferred (a11y roles on switch/segmented; "Restarted ✓" can show without a real restart).
- **CI signing step added** (`.github/workflows/build.yml`): decodes `SIDESPEED_KEYSTORE_B64` + 3 other secrets (owner added them) when present. Never run; branch isn't pushed.
- **Auto-recompile: built, not yet device-verified.** Design approved; plan `docs/superpowers/plans/2026-11-15-auto-recompile.md`; Tasks 1-6 done and unit-tested (engine, store, service/boot/package triggers, Apps "Auto-optimize" tab, Compile-screen keep toggle). Task 7 device checks pending (a TV at 192.168.5.140:5555 was connected; nothing was installed on it).

## Auto-recompile decisions (owner's)
- Engine lives in the TV app (`:app`), not the companion. Opt-in: user ticks apps, engine keeps them compiled after updates.
- One switch, off by default: "Also optimize apps not from a store" (uses `AppEntry.isSideloaded`). No "All apps" mode.
- Serial queue, one `pm compile` at a time, duplicates ignored; waits while helper is down.
- Triggers: boot/open, package added/replaced, set changed, 6-hourly. Retry 30 min x3.
- Background survival of the service on onn and Cube is untested: test on devices first.

## Gotchas
- Python: system pip blocked (PEP 668). Use `uv tool install <x>`, `uv run --with <pkg> python ...`, or `~/.venvs/tools/bin/python` (has PyYAML).
- Desktop app is only drivable via computer-use as the packaged `.app` (`createDistributable`, then `open`).
- `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; run Gradle in the foreground, one at a time.
- Ledger (git-ignored): `.superpowers/sdd/2026-11-15-sidespeed-companion/progress.md`.

## Exact next step
With the owner's OK, install the debug build on the onn and the Cube and run plan Task 7 step 3 (tick an app, `adb install -r` an update, watch Waiting > Optimizing… > Optimized; reboot; 15-minute idle; kill the helper mid-queue). Record results here.

## Still open
- 15-minute background-test result from the owner; hotspot test (no data); onn TLS pairing path; unidentified onn "mdez@unknown" prompt.
- Verify signed APKs with `apksigner` after the first CI run; `.msi/.deb/.rpm` untested.
- Merge/push `feature/m2-companion` (owner said leave as is for now).
- Android TV support for the companion: owner's idea, parked; the TV app does background work itself.
- Optional: desktop re-check when the window is reshown (owner chose the timer only).
