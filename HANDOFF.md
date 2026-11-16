# SideSpeed handoff (2026-11-16)

## Where things stand
- Branch `feature/m2-discovery-muse` (not pushed), HEAD `2758b70`. `main` at `fce44f9`.
- On the branch: review fixes A1–A10, B1, B2; merge of `fdf56fe` (auto-optimize mode); Plans 1 (Tasks 1, 3), 6 (Task 1), 7, 3, 8, 10.
- Suites green: TV app 290, companion 290, desktop 24 (2 live tests skip). `:companion-android:assembleDebug` and `:companion-desktop:createDistributable` build.
- CI signing step present but never run (branch unpushed). The Onn adb situation is unchanged: this Mac's key is unauthorized on it; use the companion's own key.

## Not verified on a device
- Plan 1 Tasks 4–6 at the Onn (add over the tailnet, keep-alive away from home); needs the owner at the Onn.
- Re-find on a /22; mDNS/SSDP/ARP hints finding the Cube first; macOS Local Network prompt (probe P8, needs the packaged app opened once); the "This TV: …" line on the Apps screen.

## Waiting on the owner
- Probe P1 before Plan 4, then Plan 9 (P7 needs an Android 17 device).
- What "per-network trust" means (Plan 5, Task 0).
- D4 (subnet-router TVs) and D5 (tailnet names in manual add) before Plan 2.
- Every push, PR, merge and artifact download (Plan 11).

## Gotchas
- `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; one Gradle run at a time; Gradle needs an unsandboxed shell here.
- Desktop app is only drivable as the packaged `.app` (`createDistributable`, then `open`).
- Python: system pip blocked (PEP 668); use `uv` or `~/.venvs/tools/bin/python`.
- Stale adb server can say "No route to host" to LAN IPs while `nc` works; `adb kill-server` fixes it.
