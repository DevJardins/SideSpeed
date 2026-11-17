# SideSpeed handoff (2026-11-17)

## Where things stand
- Branch `feature/m2-discovery-muse` (not pushed), HEAD `fe34b53`. `main` at `fce44f9`.
- Since the last update: Batch 5 (re-find stops at the first match and when the gate closes; a closed LAN gate no longer skips tailnet TVs; no extra desktop run on the first poll; the TV address line follows network changes), `464f7e2` (Android companion fingerprints the Wi-Fi under a VPN), `8fcd08e` (adding off a Trusted Network says add, not find), `fe34b53` (TV Wi-Fi address skips a VPN).
- Suites green: TV app 295, companion 298, desktop 24 (2 live tests skip), companion-android 15.
- Probe P1 (owner's phone, Tailscale on, no exit node): the companion missed the Trusted Network with Tailscale on, saw it with it off. Fixed by `464f7e2`; the device check (Plan 4 Task 4) is not done. No exit node exists on the tailnet.
- Owner's Mac: the desktop store has no trusted tailnet, and the Fire TV at 100.79.202.46 is saved under the Home Network (LAN) with no tailnet device ID, probably from an older build; kept alive only at home. To fix: Settings, Trust this tailnet, then remove and re-add 100.79.202.46.

## Not verified on a device
- The `464f7e2` fix and the TV Wi-Fi fix; probe P8; re-find; the mDNS/SSDP/ARP sources; the TV address line; Plan 1 Tasks 4 to 6.

## Waiting on the owner
- Plan 4 Task 4; probe P8; Plan 1 Tasks 4 to 6; Plan 5's meaning; D4 and D5 before Plan 2; every push and the PR shape (Plan 11).

## Gotchas
- `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; one Gradle run at a time; Gradle needs an unsandboxed shell here.
- Desktop app is only drivable as the packaged `.app` (`createDistributable`, then `open`).
- Stale adb server can say "No route to host" to LAN IPs while `nc` works; `adb kill-server` fixes it.
