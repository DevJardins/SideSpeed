# Per-Network Trust Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Scope warning:** "Per-network trust" is not defined in any file in this repository (searched `docs/`, `SPEC.md`, `MUSE_TASK.md`, `HANDOFF.md` in both checkouts). This plan implements interpretation A below. **Task 0 asks the owner to confirm it; do not start Task 1 before the answer.** If the owner means B or something else, this plan is void.

**Goal (interpretation A):** For each Trusted Network the user chooses what the companion may do there: scan for TVs ("Find TVs" and automatic re-find), and check TVs in the background. Both stay on by default, so nothing changes for existing users.

**Architecture:** `TrustedNetwork` gains two booleans with default `true`, stored by `JsonFileCompanionStore` (absent key = `true`). `CompanionViewModel.collectFind` refuses to sweep when `scan` is off; `Upkeep` skips re-find when `scan` is off and skips background-triggered visits when `background` is off (user actions still visit). Settings shows two switches under each network.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-coroutines-test, `MiniJson`.

**Spec:** `docs/superpowers/specs/2026-11-15-sidespeed-companion-design.md` section "Trusted Networks" (rules and Settings), `docs/design/overlay-networks.md` section 2 (current gates). No spec text defines this feature.

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`, `ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`, `dt/` = `companion/src/desktopTest/kotlin/io/github/devjardins/sidespeed/companion/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses are never swept.
- commonTest cannot use `runBlocking` (desktopTest store tests already use it and may).

## Decisions assumed (owner may overrule)

- **Interpretation A** (this plan): per-network switches "Find TVs here" (`scan`) and "Check in the background" (`background`), both default on.
- Interpretation B (not planned): one TV kept alive on several Trusted Networks (today a TV belongs to exactly one `trustedNetworkId`; design 5.2 says add it twice instead).
- Interpretation C (not planned): per-network fingerprint strictness (for example requiring the gateway MAC on some networks).
- `background = false` skips `Trigger.BACKGROUND` and `Trigger.NETWORK_JOINED`; `Trigger.APP_OPEN` and `Trigger.RESTART_NOW` still visit.
- `scan = false` also disables automatic re-find on that network; manual add by address still works.
- Copy: `Copy.SCAN_OFF = "Scanning is off for this network"`; switch labels "Find TVs here", "Check in the background".
- Trusted tailnets get no switches (they are never scanned already).

## Review Focus

1. Store written by this version, read by an older build: unknown keys are ignored by `decodeNetwork` today (it reads named keys only), so a downgrade keeps working. Asserted by reading the code in Task 2; no test (old code is not in the build).
2. A store file with `"scan": "yes"` (wrong type): treated as default `true`. Test in Task 2.
3. Turning `scan` off while a Find TVs run is in flight: the running sweep finishes (the switch applies to the next run). Not tested; stated in the KDoc.
4. Both a LAN scope and a tailnet scope match and the LAN has `background = false`: overlay TVs are still checked in the background. Test in Task 3.
5. Renaming a network must keep its switches (`renameTrustedNetwork` copies the whole object). Test in Task 4.

---

### Task 0: Owner confirms the meaning

- [ ] **Step 1:** Ask the owner in chat: "Per-network trust: (A) per network, switches for Find TVs and background checks; (B) one TV kept alive on several networks; (C) something else?" Stop until answered. Record the answer at the top of this file and commit it with Task 1.

### Task 1: `TrustedNetwork.scan` and `.background`

**Files:**
- Modify: `cm/net/NetworkFingerprint.kt` (`TrustedNetwork`)
- Modify: `cm/store/JsonFileCompanionStore.kt` (`encode`, `decodeNetwork`)
- Test: `dt/store/JsonFileCompanionStoreTest.kt`

**Interfaces:**
- Produces: `data class TrustedNetwork(val id: String, val name: String, val fingerprint: NetworkFingerprint, val scan: Boolean = true, val background: Boolean = true)`; JSON keys `"scan"`, `"background"` on each `trustedNetworks` entry.

- [ ] **Step 1: Failing tests** (desktopTest, `runBlocking` allowed as in the file):
  - `networkSwitchesRoundTrip`: save `TrustedNetwork("n", "Home", fp, scan = false, background = false)`, new store instance on the same dir → same values.
  - `networksWithoutSwitchesLoadAsOn`: write a store file by hand whose network entry has no `scan`/`background` keys → both `true`.
  - `wronglyTypedSwitchesLoadAsOn`: `"scan": "yes"` → `true`.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*JsonFileCompanionStoreTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3:** Implement: encode both keys; decode `m["scan"] as? Boolean ?: true`, same for `background`.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Store: per-network scan and background switches, on by default"`.

### Task 2: Find TVs respects `scan`

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (`Copy`, `collectFind`)
- Test: `ct/ui/CompanionViewModelTest.kt`

- [ ] **Step 1: Failing test** `findTvsOnANetworkWithScanOffDoesNotScan`: rig with `homeNet.copy(scan = false)`; `vm.findTvs()` → `lan.calls` empty, `state.error == Copy.SCAN_OFF`, `state.diagnosis == null`, `state.scanning == false`.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*CompanionViewModelTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3:** In `collectFind`, right after `trusted` is known: `if (!trusted.scan) { refresh(error = Copy.SCAN_OFF, settleBusy = false); return false }`.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Companion: Find TVs stays off on networks where scanning is off"`.

### Task 3: Upkeep respects `background` and `scan`

**Files:**
- Modify: `cm/upkeep/Upkeep.kt` (`runOnce`, `check`)
- Test: `ct/upkeep/UpkeepTest.kt`, `ct/upkeep/OverlayUpkeepTest.kt`

- [ ] **Step 1: Failing tests**
  - `backgroundOffSkipsBackgroundRunsOnly`: `homeNet.copy(background = false)`, one TV; `runOnce(Trigger.BACKGROUND)` and `runOnce(Trigger.NETWORK_JOINED)` → `net.opened` empty, `report.gated == false`, `report.visited` empty; `runOnce(Trigger.APP_OPEN)` → the TV is visited.
  - `scanOffNeverRefinds`: `homeNet.copy(scan = false)`, TV with `offlineCount = 2` unreachable → after one run `scanner.calls` empty and `lastRefindEpochMs == null`.
  - In `OverlayUpkeepTest`: `aLanWithBackgroundOffStillLetsOverlayTvsRun`: LAN `background = false` and a trusted overlay both match; `Trigger.BACKGROUND` visits the overlay TV only.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*UpkeepTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3:** In `runOnce`, when building `scopeIds`, drop `lan.id` if `!lan.background && !userAction` (compute `userAction` before it). In `check`, return `Checked(afterFirst)` before any re-find when `!trusted.scan`.
- [ ] **Step 4:** Re-run (both classes) — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Upkeep: per-network background checks and re-find switches"`.

### Task 4: Settings switches

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (new `setNetworkSwitches`), `cm/ui/SettingsScreen.kt`, `cm/ui/Navigation.kt`
- Test: `ct/ui/CompanionViewModelTest.kt`

**Interfaces:**
- Produces: `fun CompanionViewModel.setNetworkSwitches(id: String, scan: Boolean, background: Boolean)`; `SettingsScreen` gains parameter `onNetworkSwitches: (String, Boolean, Boolean) -> Unit` after `onRemoveNetwork`.

- [ ] **Step 1: Failing tests** `networkSwitchesAreSaved` (call with `false, true` → store has `scan == false`, `background == true`, state's `trustedNetworks` reflects it) and `renameKeepsSwitches` (switches off, then `renameTrustedNetwork("home", "Den")` → switches still off).
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*CompanionViewModelTest*' --console=plain` — Expected: FAIL.
- [ ] **Step 3:** Implement `setNetworkSwitches` like `renameTrustedNetwork` (locked, copy, save, refresh). In `SettingsScreen`, under each non-editing network row add `SsSwitchRow("Find TVs here", n.scan) { onNetworkSwitches(n.id, it, n.background) }` and `SsSwitchRow("Check in the background", n.background) { onNetworkSwitches(n.id, n.scan, it) }`. Pass `vm::setNetworkSwitches` from `Navigation.kt`.
- [ ] **Step 4:** Re-run, then `./gradlew :companion:desktopTest :companion-desktop:test --console=plain` and `./gradlew :companion-android:assembleDebug --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `git commit -m "Settings: per-network Find TVs and background check switches"`.
