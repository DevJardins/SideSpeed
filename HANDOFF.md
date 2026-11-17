# SideSpeed handoff (2026-11-17)

## Where things stand
- Branch `feature/m2-discovery-muse` (not pushed), HEAD: see `git log`. `main` at `fce44f9`.
- Since the last update: Batch 5 (re-find stops at the first match and when the gate closes; a closed LAN gate no longer skips tailnet TVs; no extra desktop run on the first poll; the TV address line follows network changes), `464f7e2` (Android companion fingerprints the Wi-Fi under a VPN), `8fcd08e` (adding off a Trusted Network says add, not find), `fe34b53` (TV Wi-Fi address skips a VPN).
- Suites green: TV app 295, companion 298, desktop 24 (2 live tests skip), companion-android 15.
- Probe P1 (owner's phone, Tailscale on, no exit node): the companion missed the Trusted Network with Tailscale on, saw it with it off. Fixed by `464f7e2`; the device check (Plan 4 Task 4) is not done. No exit node exists on the tailnet.
- Owner's Mac: the desktop store has no trusted tailnet, and the Fire TV Cube at 100.79.202.46 is saved under the Home Network (LAN) with no tailnet device ID, probably from an older build; kept alive only at home. To fix: remove the 100.79.202.46 card and re-add it; adding a tailnet address now trusts the tailnet automatically.

## Not verified on a device
- The `464f7e2` fix and the TV Wi-Fi fix; probe P8; re-find; the mDNS/SSDP/ARP sources; the TV address line; Plan 1 Tasks 4 to 6.

## Waiting on the owner
- Plan 4 Task 4; probe P8; Plan 1 Tasks 4 to 6; Plan 5's meaning; D4 and D5 before Plan 2; every push and the PR shape (Plan 11).

## Gotchas
- `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`; one Gradle run at a time; Gradle needs an unsandboxed shell here.
- Desktop app is only drivable as the packaged `.app` (`createDistributable`, then `open`).
- Stale adb server can say "No route to host" to LAN IPs while `nc` works; `adb kill-server` fixes it.

## Update 2026-11-16 (late): Batches 7-13 and device results
- **Fact correction:** the Onn is 192.168.4.26 on the LAN and 100.123.103.8 on the tailnet (node nag7cVgr4a11CNTRL, serial JUSA2606000772). The Fire TV Cube is 100.79.202.46.
- **Commits by batch:**
  - Batch 7: 5ef2704, 25b7931.
  - Batch 8 logo: 44a5ed0, d420b30, 9638aaa.
  - Batch 9 banner: e372bcc, e6206a9.
  - Batch 10 Finder Tailscale (SHLVL): 0d7026e.
  - Batch 11 auto-trust and reach borders: 5aeb35f, d54fa2b, 9c606c9, f5979a8, fdf08a5, fb7a32f.
  - Batch 12 store v2 guard: a846302.
  - Batch 12b restart messages: 32c2047.
  - Batch 13: this commit.
- **Verified on a device:**
  - P1 (464f7e2): phone, Tailscale on, fingerprint OK.
  - P8: Mac Local Network prompt granted, scan finds the TVs.
  - Re-find on the Fire TV.
  - Batch 7 grey last-seen.
  - Logo on all targets.
  - TV banner on the Onn.
  - Plan 1 Tasks 4-5 except away-from-home.
  - Red/blue/green borders.
- **Lesson:** an old build in the main checkout (/Users/mdez/Developer/SideSpeed, fdf56fe) shared the store and erased tailnet data. Batch 12 prevents this from now on, but builds made before it can't be fixed. Delete stale builds.
- **Still waiting on the owner:** Plan 1 away-from-home; Plan 11 (push and PR shape, GitHub signing secrets); whether the Android off-Wi-Fi add should get option 2.
- Declined 2026-11-16: Plan 5 (per-network trust), D4 (subnet-router TVs), D5 (tailnet names in Add).
