# TV App Shows Its Own Address Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SideSpeed on the TV shows the TV's own address ("This TV: 192.168.5.140"), so the companion's fallback copy "or type the address shown in SideSpeed on the TV" (`companion/.../discovery/Diagnosis.kt`) is true.

**Architecture:** A pure `TvAddress.pick(ipv4s)` keeps private LAN addresses first, then Tailscale-range addresses, and drops everything else; `TvAddress.label(...)` makes the line. `AppContainer.tvAddresses()` lists the IPv4 addresses of up, non-loopback interfaces with `java.net.NetworkInterface` (no permission). `AppsScreen` shows the label in its header row.

**Tech Stack:** Android TV app module `:app` (Jetpack Compose, JUnit 4 unit tests).

**Spec:** `docs/superpowers/specs/2026-11-15-sidespeed-companion-design.md` "Adding a TV: Enter the address: SideSpeed on the TV shows it."; `companion/src/commonMain/.../discovery/Diagnosis.kt` (`MANUAL_ADDRESS_COPY`).

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `app/` = `app/src/main/java/io/github/devjardins/sidespeed/`, `appt/` = `app/src/test/java/io/github/devjardins/sidespeed/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on a branch that already contains commit `fdf56fe` ("TV app: choose auto-optimize mode", on `feature/m2-companion-rename-network`, not yet in `feature/m2-discovery-muse`): it changed `AppsScreen.kt` and `MainActivity.kt`. Merge it first (see the release-checklist plan, Task 2) or expect a conflict. Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not plan or change the auto-optimize mode (another session owns it).
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only); `AddressPolicy.sweepable` stays RFC 1918 /16 to /30; overlay addresses are never swept. (Listed for consistency; this plan touches only `:app`.)
- Do not install on a TV without the owner's go-ahead in chat.

## Decisions assumed (owner may overrule)

- Shown on the Apps screen header only (the screen the user lands on after setup). The setup screen already shows the IP inside its PC commands (`pcStartCommands(..., vm.wifiIp(), ...)`).
- Copy: `"This TV: 192.168.5.140"`; with a Tailscale address too: `"This TV: 192.168.5.140 · 100.123.103.8"`; at most 3 addresses; nothing shown when there is none.
- Tailscale range rule copied from the companion's `AddressPolicy.isOverlay` (100.64.0.0/10 minus 100.100.0.0/24, 100.100.100.0/24, 100.115.92.0/23), duplicated in `:app` because `:app` does not depend on `:companion`.
- Read once when the Apps screen is composed (`remember`); a network change shows after the app is reopened.

## Review Focus

1. Loopback, link-local (169.254.x), public, IPv6 and Tailscale-reserved (100.100.100.100) addresses: never shown. Test in Task 1.
2. Duplicate addresses from two interfaces: shown once. Test in Task 1.
3. No network at all: no line, no crash (`NetworkInterface.getNetworkInterfaces()` can throw or return null). `tvAddresses()` returns empty on any exception. Task 2.
4. Ethernet and Wi-Fi both up: both private addresses shown, order as the system lists them. Test in Task 1 (order kept within a group).
5. More than 3 candidate addresses: first 3 after grouping. Test in Task 1.

---

### Task 1: `TvAddress`

**Files:**
- Create: `app/status/TvAddress.kt`
- Test: `appt/status/TvAddressTest.kt`

**Interfaces:**
- Produces: `object TvAddress { fun pick(ipv4s: List<String>): List<String>; fun label(addresses: List<String>): String? }`.

- [ ] **Step 1: Failing tests** (JUnit 4, `org.junit.Assert`):
  - `pick(listOf("100.123.103.8", "192.168.5.140", "127.0.0.1", "169.254.3.4", "8.8.8.8", "100.100.100.100", "fe80::1", "192.168.5.140", "10.0.0.7"))` == `listOf("192.168.5.140", "10.0.0.7", "100.123.103.8")`
  - `pick(listOf("172.16.0.2", "172.32.0.2", "100.63.255.255", "100.128.0.1"))` == `listOf("172.16.0.2")`
  - `pick(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"))`.size == 3
  - `pick(listOf("1.2.3", "a.b.c.d", "256.1.1.1", ""))` == empty
  - `label(listOf("192.168.5.140", "100.123.103.8"))` == `"This TV: 192.168.5.140 · 100.123.103.8"`; `label(emptyList())` == null
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*TvAddressTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement** with a strict dotted-quad parse (four decimal octets 0..255, 1 to 3 digits). Groups: RFC 1918 (10/8, 172.16/12, 192.168/16) first, then 100.64.0.0/10 minus the three reserved blocks; distinct; `take(3)`.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "TV app: pick the addresses to show for this TV"`.

### Task 2: Show it on the Apps screen

**Files:**
- Modify: `app/AppContainer.kt` (add `fun tvAddresses(): List<String>` next to `wifiIp()`)
- Modify: `app/ui/apps/AppsScreen.kt` (new parameter `tvAddress: String? = null`; header `Row`)
- Modify: `app/MainActivity.kt` (the `AppsScreen(...)` call)

- [ ] **Step 1:** `tvAddresses()`: `NetworkInterface.getNetworkInterfaces()` (null-safe), `isUp && !isLoopback`, `inetAddresses` filtered to `Inet4Address`, `hostAddress`; wrap in `try { … } catch (_: Exception) { emptyList() }`; return `TvAddress.pick(list)`.
- [ ] **Step 2:** In `AppsScreen`'s header `Row`, after the "Pick an app to compile" `Text` and before the `Spacer(Modifier.weight(1f))`, add `tvAddress?.let { Spacer(Modifier.width(16.dp)); Text(it, style = SsType.Caption, color = SsColors.TextSecondary) }`. In `MainActivity`, pass `tvAddress = remember { TvAddress.label(container.tvAddresses()) }` (use the same `(application as SideSpeedApp).container` access as the surrounding code).
- [ ] **Step 3:** Run `./gradlew :app:testDebugUnitTest --console=plain` then `./gradlew :app:assembleDebug --console=plain` — Expected: both BUILD SUCCESSFUL.
- [ ] **Step 4 (owner, with OK):** install on the Cube (`adb install -r app/build/outputs/apk/debug/<file>.apk`, confirm the file name with `ls`), open SideSpeed. Expected: "This TV: 192.168.5.140" in the header; the D-pad focus order is unchanged (the label is not focusable).
- [ ] **Step 5:** Commit `git commit -m "TV app: show this TV's address on the Apps screen"`.
