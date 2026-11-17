# Android Companion VPN Fingerprint Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With Tailscale (or any VPN) up on the phone, the Android companion still recognises the home Trusted Network, because it fingerprints the Wi-Fi or Ethernet network instead of whatever `activeNetwork` is.

**Architecture:** Probe P1 runs first and decides which tasks apply. The fix (design T1.1) is a pure selection function over network candidates (VPN flag, transport, `LinkData`) tested on the JVM, used by `AndroidFingerprintSource`; the network callback also listens to Wi-Fi/Ethernet so a join under a VPN still triggers a check. Socket binding (design T1.2) is not implemented here: see Task 5.

**Tech Stack:** Android `ConnectivityManager` / `NetworkCapabilities`, JUnit 4 (`companion-android/src/test`).

**Spec:** `docs/design/overlay-networks.md` section 1 item 6, section 7 "Phase 1" (T1.1, T1.2), section 9 probe P1.

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `and/` = `companion-android/src/main/kotlin/io/github/devjardins/sidespeed/companion/android/`, `andt/` = `companion-android/src/test/kotlin/io/github/devjardins/sidespeed/companion/android/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses are never swept.
- No location permission (owner decision 1 = b in the companion spec).
- Do not install on or touch the owner's phone without the owner's go-ahead in chat.

## Decisions assumed (owner may overrule)

- If P1 shows the bug (banner says "Not on a Trusted Network" with Tailscale on at home), do Tasks 2 to 4. If P1 shows the banner correctly, do only Task 2's tests as a regression guard (they pass against a `pickLan` that returns the active network when it is not a VPN) and skip Task 3's code change: write that in the commit message.
- The active network wins when it is not a VPN; otherwise the first non-VPN network with Wi-Fi or Ethernet transport whose `LinkData` maps to a fingerprint.
- **T1.2 (bind LAN sockets to the Wi-Fi network) is not built** without a further owner decision: Kadb 2.1.4 exposes no socket factory (`javap` on `kadb-jvm-2.1.4.jar`: `Kadb.Companion.create(String, int, int, int, KadbOptions)`, and `KadbOptions` holds only `DelayedAckMode`; the Android AAR was not inspected), so the only lever is process-wide `ConnectivityManager.bindProcessToNetwork`, which would also stop bare 100.x adds on Android (D8) from reaching the tailnet.

## Review Focus

1. VPN with a full tunnel (exit node) whose LinkProperties carry a default route: must still be skipped because it is a VPN, not because of its routes. Test in Task 2.
2. Two non-VPN candidates (Wi-Fi and Ethernet both up): the active one wins if non-VPN; else list order. Test in Task 2.
3. Mobile data only, VPN up: no fingerprint (null), as today with mobile data. Test in Task 2.
4. VPN toggled while the Wi-Fi stays the same: the fingerprint must not change, so no spurious NETWORK_JOINED run. Covered by Task 2 (same input gives same output) and the device check in Task 4.
5. A candidate whose capabilities are null (network vanished between calls): skipped, no crash. Test in Task 2 (`NetworkCandidate` built only when capabilities exist; null-safe building in Task 3).

---

### Task 1: Probe P1 (owner) and the decision

- [ ] **Step 1:** Ask the owner in chat to run, at home with the phone on the home Wi-Fi and Tailscale **on, no exit node**: open the companion; does Home show "Trusted Network: <name> ✓"? Then from the Mac with the phone on USB or Wi-Fi debugging: `adb shell dumpsys connectivity | grep -iE "VPN|tun0|default"`.
- [ ] **Step 2:** Same with Tailscale **on with an exit node** (only if the owner has one).
- [ ] **Step 3:** Record in `HANDOFF.md` (this worktree) under "Still open": banner yes/no for each case and whether `dumpsys` shows the default network as the VPN (`tun0`). Decide per "Decisions assumed".

### Task 2: `FingerprintMapping.pickLan`

**Files:**
- Modify: `and/AndroidFingerprintSource.kt` (add `NetworkCandidate` and `FingerprintMapping.pickLan`)
- Test: `andt/AndroidFingerprintSourceTest.kt`

**Interfaces:**
- Produces:
  - `data class NetworkCandidate(val isVpn: Boolean, val isWifiOrEthernet: Boolean, val link: LinkData?)`
  - `fun FingerprintMapping.pickLan(active: NetworkCandidate?, all: List<NetworkCandidate>): LinkData?`

- [ ] **Step 1: Failing tests** (fixtures: `wifi = LinkData(listOf("192.168.5.1"), listOf("192.168.5.23/24"), listOf("192.168.5.1"))`; `vpn = LinkData(emptyList(), listOf("100.101.2.3/32"), listOf("100.100.100.100"))`; `exitVpn = LinkData(listOf(null), listOf("100.101.2.3/32"), listOf("100.100.100.100"))`; `cell = LinkData(listOf("10.20.30.1"), listOf("10.20.30.2/30"), emptyList())`):
  - `vpnActiveFallsBackToTheWifiUnderneath`: active `NetworkCandidate(true, false, vpn)`, all `[that, NetworkCandidate(false, true, wifi)]` → `map(pickLan(...))` equals the existing Wi-Fi fingerprint `NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"), ownIp = "192.168.5.23")`.
  - `exitNodeVpnIsSkippedToo`: same with `exitVpn`.
  - `activeWifiWins`: active Wi-Fi, all also contains an Ethernet candidate with another link → the active link.
  - `vpnOverMobileDataGivesNothing`: active VPN, all `[vpn, NetworkCandidate(false, false, cell)]` → `null`.
  - `nullLinksAreSkipped`: active VPN, all `[NetworkCandidate(false, true, null), NetworkCandidate(false, true, wifi)]` → wifi.
- [ ] **Step 2:** Run `./gradlew :companion-android:testDebugUnitTest --tests '*AndroidFingerprintSourceTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement:** if `active != null && !active.isVpn` return `active.link`; else `all.firstOrNull { !it.isVpn && it.isWifiOrEthernet && map(it.link) != null }?.link`.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Android companion: pick the Wi-Fi or Ethernet network under a VPN for the fingerprint"`.

### Task 3: Use `pickLan` in `AndroidFingerprintSource` and listen to Wi-Fi joins (only if P1 showed the bug)

**Files:**
- Modify: `and/AndroidFingerprintSource.kt` (`current()`)
- Modify: `and/CompanionApplication.kt` (`watchNetwork`)

- [ ] **Step 1:** `current()` builds `NetworkCandidate`s from `cm.activeNetwork` and `@Suppress("DEPRECATION") cm.allNetworks`: `caps = cm.getNetworkCapabilities(n) ?: skip`, `isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)`, `isWifiOrEthernet = caps.hasTransport(TRANSPORT_WIFI) || caps.hasTransport(TRANSPORT_ETHERNET)`, `link = linkData(cm.getLinkProperties(n))`; returns `FingerprintMapping.map(FingerprintMapping.pickLan(active, all))`.
- [ ] **Step 2:** In `watchNetwork`, keep `registerDefaultNetworkCallback` and also register the same callback object type (a second instance) with `cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(), …)`, emitting into the same `events` flow (the debounce already merges them).
- [ ] **Step 3:** Run `./gradlew :companion-android:testDebugUnitTest :companion-android:assembleDebug --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 4:** Commit `git commit -m "Android companion: fingerprint the LAN under a VPN and notice Wi-Fi joins"`.

### Task 4: Device check (owner)

- [ ] **Step 1:** With the owner's OK: `adb install -r companion-android/build/outputs/apk/debug/companion-android-debug.apk` (confirm the file name with `ls companion-android/build/outputs/apk/debug/` first).
- [ ] **Step 2:** Repeat Task 1 Step 1 and 2. Expected: "Trusted Network: <name> ✓" in both cases; "Restart now" on the Cube card updates "Checked 0 min ago".
- [ ] **Step 3:** Toggle Tailscale off and on with the app in the background; Expected: no "SideSpeed restarted" notification storm (at most the usual one per real restart).
- [ ] **Step 4:** Record results in `HANDOFF.md`; mark T1.1 done in `docs/design/overlay-networks.md` section 7 with the commit; commit `git commit -m "Record P1 and the Android VPN fingerprint device check"`.

### Task 5: T1.2 socket binding: stop and ask

- [ ] **Step 1:** Only if Task 4 shows LAN TVs unreachable with the VPN up (exit node without "Allow LAN access"), report to the owner: binding needs `bindProcessToNetwork` (Kadb has no socket factory, see Decisions), which also blocks bare 100.x adds on Android. Do not implement without an answer.
