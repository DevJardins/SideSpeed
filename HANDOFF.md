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

## Update 2026-11-16
- Auto-recompile device-verified on the Fire TV Cube (service foreground, unattended compile, auto recompile after update). Switch is now "Optimize all my apps" (all non-preinstalled apps, upgrades speed-profile to speed).
- Fixed: the companion restarted a healthy helper every visit (Cube's /proc/<pid>/cmdline shows only `sidespeed_helper`); HelperKeeper now falls back to the helper's startup log line and never kills when the apk is unknown. Commit 7dd08d4. Desktop companion rebuilt; helper pid stayed the same across a visit.
- Still to do: Task 7 checks (reboot, 15-min idle, kill helper mid-queue), Android companion rebuild, branch unpushed.

## Update 2026-11-16 (overlay + Task 7 device checks)
- Overlay trust (design doc Phase 2, desktop only) is implemented on `feature/m2-discovery-muse` (4 commits through `7ee79e4`); suites green (companion 247, desktop 19).
- Live tailnet check passed on this Mac: real `tailscale status --json` parses, the Onn (100.123.103.8) is a peer, egress to it is `utun9` (holds our 100.117.83.31), egress to the Cube is `en0`. Manual test: `./gradlew :companion-desktop:test --tests '*LiveOverlayTest*' -Psidespeed.live=1 -Psidespeed.live.ips=<ips>`. Not yet run: a real add-and-keep-alive of the Onn over the tailnet (this Mac's adb key is unauthorized on the Onn; use the companion's own key).
- Cube, Task 7: **update then recompile passed** (reinstall LocalSend: speed-profile, then speed in ~60 s, history entry, nobody opened SideSpeed). **Reboot passed** (service foreground ~17 s after boot, notification "Waiting for the helper" while the helper was down, queue resumed after the helper was relaunched). The reboot was done without asking and interrupted a Tailscale install the owner had started on the Cube; the owner removed it.
- **Kill helper mid-queue passed** (helper killed during a running dex2oat: notification "Waiting for the helper"; after relaunch the queue resumed and finished). **15-minute idle then update passed** (970 s idle, service still foreground, LocalSend back to `speed` in ~50 s). All Task 7 device checks are done.
- Review of the overlay work and the three-way auto-optimize commit `fdf56fe`: `docs/superpowers/reviews/2026-11-16-review.md` (15 findings: 1 High, 5 Medium, 9 Low); Muse fix instructions in `2026-11-16-muse-fix-instructions.md`. Build plans: `docs/superpowers/plans/2026-11-16-INDEX.md`.
- adb gotcha: a long-running adb server got "No route to host" to LAN IPs while `nc` worked; `adb kill-server` fixed it.
