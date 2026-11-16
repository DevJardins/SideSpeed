# SideSpeed handoff (2026-11-16)

## State
- `feature/m2-companion` (unpushed, `main` untouched): auto-recompile built and device-verified on the Fire TV Cube (192.168.5.140:5555); switch is "Optimize all my apps". Helper false-stale fix `7dd08d4`.
- `feature/m2-companion-rename-network` (unpushed, off the above, HEAD `26a7a82`): Trusted Network rename, add-TV feedback, Find TVs spinner and "No TVs found", robot desktop icon (`docs/brand/sidespeed-logo.webp`), /22 to /24 scan, then discovery tasks 1-2 (below). Desktop passed feature checks; the Android APK is built but not yet run by the owner.
- Uncommitted: `CompanionViewModelTest.kt` (task 3 in progress by the rename session). Stop that session before another agent takes over.

## Task for Muse Code: Find TVs discovery, Phases 1-2 (test-first, small commits)
Work in a separate git worktree off `feature/m2-companion-rename-network`. Test: `./gradlew :companion:desktopTest` (`export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`, one Gradle run at a time). No attribution lines in commits. Do not push.
- Done: (1) `Ipv4/Cidr/AddressPolicy` `49f9891`; (2) `ProbeResult` + `SocketAdbProbe` `26a7a82`; (3) manual add of private addresses `8422806`.
- Next, in order: (4) accept /16../30 prefixes with a 4,096-probe cap (update `ScannerTest` deliberately); (5) `Discovery(sources, probe).run(plan): Flow<DiscoveryEvent>` with sweep source, ordering, two passes (400 ms, then 1500 ms on silent hosts), dedupe, cancellation, virtual-time tests with a `FakeLan`; (6) ViewModel: discovery in its own Job outside the `work` mutex, `cancelFind()`, cancel on screen dispose, "Scanned N of M", Stop / Keep scanning, streaming results; (7) `Diagnosis` (FOUND, NO_ADB_ON_LIVE_HOSTS, NOTHING_ANSWERED, OS_BLOCKED, VPN_ACTIVE, PARTIAL) and help copy ending "or type the address shown in SideSpeed on the TV".
- Keep working: add feedback, spinner, "No TVs found".

## Rules (from the design review)
- Verifier sends one raw ADB CNXN, reads 24 bytes, closes. Never call `TvConnector.open` in discovery (full handshake prompts "Allow USB debugging?" on every ADB device). `FakeTvSession.opened` stays empty.
- Sweep only a Trusted Network's own subnet; RFC1918 only; port 5555 only; never own/network/broadcast. Order: own /24, gateway /24, then outward. `Dispatchers.IO.limitedParallelism(128)`.
- Out of scope: SSDP/mDNS/ARP sources, platform network sources, macOS local-network plist, CIDR field, re-find (Upkeep is /24-only, so dead on the owner's /22).

## Still open
- Cube checks: reboot, 15-minute idle then update, kill helper mid-queue.
- Android companion icon still the old ">>"; owner may want the robot.
- Later device probes: Fire TV mDNS/SSDP advertising, probe behaviour, VPN and macOS privacy.
- Muse: provider default `meta`, contributor mode left at its default (owner said do not turn it off).
