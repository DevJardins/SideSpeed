# Finish Overlay Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Phase 2 of the overlay design: say on each bare 100.x TV which Trusted Network keeps it alive, say on Home which kind of scope is active, add and keep alive the Onn over the tailnet, and mark T2.1 to T2.7 done.

**Architecture:** Two small view-model additions (`TvCardState.scopeNote`, `CompanionUiState.trustedTailnetName`) computed in `CompanionViewModel.refresh`, rendered by `HomeScreen`. Then an owner-attended device run with the packaged desktop app, and a docs update.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-coroutines-test, Kadb 2.1.4, Tailscale CLI.

**Spec:** `docs/design/overlay-networks.md` (section 6 checklist item "Kept alive while on <network>", section 7 Phase 2 T2.1 to T2.7, decision D1).

Path shorthands used below (all relative to `/Users/mdez/Developer/SideSpeed-muse`):
`cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`,
`ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse`. Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only): `Graph` in `companion-android/.../CompanionApplication.kt` never builds an `OverlayAccess`.
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses (100.64.0.0/10) are never swept.
- commonTest cannot use `runBlocking`: use `runTest(UnconfinedTestDispatcher())`.
- Do not push. Do not touch a TV without the owner's go-ahead in chat (HANDOFF: "Ask before touching the Cube again"); Tasks 4 and 5 need the owner at the Onn.

## Decisions assumed (owner may overrule)

- **D8** (design default): a bare 100.x add with no peer binding stays saved under the physical Trusted Network; the card says "Kept alive while on <network>". Already the code's behaviour (`CompanionViewModel.addTv`); this plan only adds the copy.
- **D7** is already implemented (`TailscaleStatus.peerAddresses` keeps `OS == "android"` or unreported OS).
- Home banner copy for an active tailnet with no Trusted Network: "Trusted tailnet: <name> ✓". When both match, the LAN wins and reads as today: "Trusted Network: <name> ✓".
- T2.3 is marked done as built: there is no `Gate` interface or `LanGate`; the LAN gate stays `Upkeep.gateOpen`, the overlay gate is `OverlayGate.addressToUse` / `bindingFor` (`cm/upkeep/OverlayGate.kt`).

## Review Focus

1. A bare 100.x TV saved under a network that was later removed: `removeTrustedNetwork` removes its TVs, so no card can point at a missing network; the note must be null rather than "Kept alive while on " if the network is not found. Test in Task 1.
2. Peer-bound TV (has `overlayPeerId`): must never show the "Kept alive while on" note. Test in Task 1.
3. Away from home with Tailscale up but the tailnet not trusted: banner must stay "Not on a Trusted Network". Test in Task 2.
4. Onn accepts the key but `ro.serialno` is blank (some Google TV builds): add fails with "That device didn't identify itself, so it can't be added". Recorded as an outcome in Task 4, not coded.
5. Onn's port 5555 closes after a reboot (Google TV): keep-alive then shows Offline after 3 checks; this is expected, record it, do not fix here.

---

### Task 1: "Kept alive while on <network>" note on bare 100.x TVs

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (`Copy`, `TvCardState`, `refresh`)
- Modify: `cm/ui/HomeScreen.kt` (TV card column, after the `tv.note` line)
- Test: `ct/ui/OverlayViewModelTest.kt`

**Interfaces:**
- Produces: `Copy.keptAliveWhileOn(network: String): String` returning `"Kept alive while on $network"`; `TvCardState.scopeNote: String?` (new last constructor parameter, default `null`).

- [ ] **Step 1: Write the failing tests** in `OverlayViewModelTest` (reuse its `rig()`; the default rig has the LAN network `homeNet` named "Home", the trusted overlay, and peer `nCUBE` at 100.101.1.1):

```kotlin
@Test fun aBareTailscaleAddressSaysWhichNetworkKeepsItAlive() = runTest(UnconfinedTestDispatcher()) {
    val r = rig()
    r.vm.addTvByAddress("100.101.1.9")   // no peer has it: saved under "home"
    assertEquals("Kept alive while on Home", r.vm.state.value.tvs.single().scopeNote)
}

@Test fun peerBoundAndLanTvsCarryNoScopeNote() = runTest(UnconfinedTestDispatcher()) {
    val r = rig()
    r.vm.addTvByAddress("100.101.1.1")   // peer-bound
    assertNull(r.vm.state.value.tvs.single().scopeNote)
    val lan = rig()
    lan.vm.addTvByAddress("192.168.5.20")
    assertNull(lan.vm.state.value.tvs.single().scopeNote)
}

@Test fun aScopeNoteNeedsTheNetworkToExist() = runTest(UnconfinedTestDispatcher()) {
    val r = rig()
    r.store.saveTv(SavedTv("x", "TV", "100.101.1.9", "SER-9", trustedNetworkId = "gone"))
    r.vm.refreshNow()
    assertNull(r.vm.state.value.tvs.single().scopeNote)
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew :companion:desktopTest --tests '*OverlayViewModelTest*' --console=plain`
Expected: compilation FAIL, "Unresolved reference 'scopeNote'".

- [ ] **Step 3: Implement**

In `Copy` add `fun keptAliveWhileOn(network: String) = "Kept alive while on $network"`. Add `val scopeNote: String? = null` as the last field of `TvCardState`. In `refresh`, set it for a TV when `tv.overlayPeerId == null` and `Ipv4.parse(tv.address)?.let(AddressPolicy::isOverlay) == true`, using `networks.firstOrNull { it.id == tv.trustedNetworkId }?.displayName?.let(Copy::keptAliveWhileOn)`; otherwise null. In `HomeScreen`, after `tv.note?.let { ... }`, add `tv.scopeNote?.let { SsText(it, Ss.caption) }`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :companion:desktopTest --tests '*OverlayViewModelTest*' --console=plain`
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/CompanionViewModel.kt companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/HomeScreen.kt companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/ui/OverlayViewModelTest.kt
git commit -m "Companion: say which Trusted Network keeps a bare Tailscale-address TV alive"
```

### Task 2: Home banner reports which scope is active (T2.7 "report which one")

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (`Copy`, `CompanionUiState`, `refresh`)
- Modify: `cm/ui/HomeScreen.kt` (banner `Row`, lines 63-75 today)
- Test: `ct/ui/OverlayViewModelTest.kt`

**Interfaces:**
- Produces: `CompanionUiState.trustedTailnetName: String? = null` (display name of the trusted overlay Tailscale matches now); `Copy.scopeBanner(lan: String?, tailnet: String?): String`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun homeSaysWhenOnlyTheTailnetIsTrusted() = runTest(UnconfinedTestDispatcher()) {
    val r = rig(fp = hotel)
    r.vm.refreshNow()
    assertNull(r.vm.state.value.trustedNetworkName)
    assertEquals("Tailnet", r.vm.state.value.trustedTailnetName)
}

@Test fun anUntrustedTailnetIsNotReportedAsTrusted() = runTest(UnconfinedTestDispatcher()) {
    val r = rig(fp = hotel, overlay = false)
    r.vm.refreshNow()
    assertNull(r.vm.state.value.trustedTailnetName)
}

@Test fun scopeBannerCopy() {
    assertEquals("Trusted Network: Home ✓", Copy.scopeBanner("Home", "Tailnet"))
    assertEquals("Trusted tailnet: Tailnet ✓", Copy.scopeBanner(null, "Tailnet"))
    assertEquals("Not on a Trusted Network", Copy.scopeBanner(null, null))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :companion:desktopTest --tests '*OverlayViewModelTest*' --console=plain`
Expected: compilation FAIL, "Unresolved reference 'trustedTailnetName'".

- [ ] **Step 3: Implement**

In `refresh`, read the snapshot once (it already does: `overlay?.source?.snapshot()`), keep it in a local, and set `trustedTailnetName = OverlayTrustMatcher.currentTrusted(snap, overlays)?.displayName`. `Copy.scopeBanner`: LAN name wins (`"Trusted Network: $lan ✓"`), else `"Trusted tailnet: $tailnet ✓"`, else `"Not on a Trusted Network"`. In `HomeScreen`, `val trusted = state.trustedNetworkName != null || state.trustedTailnetName != null` and the banner text becomes `Copy.scopeBanner(state.trustedNetworkName, state.trustedTailnetName)`; colours keyed on `trusted` as today.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :companion:desktopTest --tests '*OverlayViewModelTest*' --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/CompanionViewModel.kt companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/HomeScreen.kt companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/ui/OverlayViewModelTest.kt
git commit -m "Companion: Home banner says when a trusted tailnet is what is active"
```

### Task 3: Full suites and a fresh desktop build

- [ ] **Step 1:** Run: `./gradlew :companion:desktopTest :companion-desktop:test --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 2:** Run: `./gradlew :companion-android:assembleDebug --console=plain` — Expected: BUILD SUCCESSFUL (common UI still compiles for Android).
- [ ] **Step 3:** Run: `./gradlew :companion-desktop:createDistributable --console=plain` — Expected: BUILD SUCCESSFUL and `companion-desktop/build/compose/binaries/main/app/SideSpeed Companion.app` exists (`ls -d` it).

### Task 4: Add the Onn over the tailnet (owner at the Onn)

Facts used: the Onn is Tailscale peer 100.123.103.8 with ADB on 5555 (from the request and HANDOFF.md "Update 2026-11-16 (overlay ...)"); this Mac's own adb key (`~/.android/adbkey`) is unauthorized on the Onn. The companion uses a **different key**: `CompanionKeys.KEY_FILE_NAME` = `adbkey.pem` under `DesktopDirs.keyDir()` = `~/Library/Application Support/SideSpeed Companion/adb/` (`companion/src/desktopMain/.../tv/DesktopDirs.kt`). The Onn accepts that key only through its own "Allow USB debugging?" prompt with "Always allow" ticked; nothing on the Mac can pre-authorise it (no root on the TV). HANDOFF lists an unidentified "mdez@unknown" prompt seen on the Onn: that is probably an earlier companion key prompt (unverified).

- [ ] **Step 1: Ask the owner in chat** to be at the Onn with the remote, Tailscale running on the Onn and the Mac, and confirm the Mac is at home for this task.
- [ ] **Step 2: Reachability.** Run: `nc -vz -G 3 100.123.103.8 5555` — Expected: "succeeded". If refused: ADB on 5555 is closed on the Onn (Google TV closes it on reboot); stop and tell the owner.
- [ ] **Step 3: Peer and egress.** Run: `./gradlew :companion-desktop:test --tests '*LiveOverlayTest*' -Psidespeed.live=1 -Psidespeed.live.ips=100.123.103.8 --console=plain` — Expected: `SNAPSHOT:` line containing an `OverlayPeer(` with `ips=[100.123.103.8]`, `online=true`, `shared=false` (note its `id=`, the Onn's node ID), and `EGRESS 100.123.103.8: EgressRoute(interfaceName=utun…, addresses=[100.117.83.31])` (HANDOFF recorded `utun9`; the number may differ, the address must be this Mac's tailnet address).
- [ ] **Step 4: Key prompt with the companion key.** Confirm the key exists: `ls -l "$HOME/Library/Application Support/SideSpeed Companion/adb/adbkey.pem"` (mode `-rw-------`). Then run: `./gradlew :companion:desktopTest --tests '*ManualFireTvCheck*' -Psidespeed.tv=100.123.103.8:5555 --console=plain`. It prints `ManualFireTvCheck: key dir …/SideSpeed Companion/adb`; the Onn shows "Allow USB debugging?". Owner ticks "Always allow from this computer" and taps Allow within 20 s. Expected: `ManualFireTvCheck: ro.serialno = <serial>`. If it fails with `TvAuthPending` because the 20 s passed, the owner accepts anyway and the step is re-run: the second run must print the serial with no prompt.
- [ ] **Step 5: Trust the tailnet if not yet trusted.** Open the app: `open "companion-desktop/build/compose/binaries/main/app/SideSpeed Companion.app"`. Settings: "Trusted tailnets" lists it, or press "Trust this tailnet (<name>)".
- [ ] **Step 6: Add.** Home, "Add a TV", type `100.123.103.8`, "Add". Expected: returns Home; the new card shows address 100.123.103.8, the "Not a Fire TV…" note (Onn is not Amazon), **no** "Kept alive while on" note, and after the add's upkeep run either "Helper running" or "SideSpeed isn't installed" (record which).
- [ ] **Step 7: Store check.** Run:
  `python3 -c 'import json,os; d=json.load(open(os.path.expanduser("~/Library/Application Support/SideSpeed Companion/store/companion.json"))); print([(t["address"],t["trustedNetworkId"],t.get("overlayPeerId")) for t in d["tvs"]])'`
  Expected: an entry `('100.123.103.8', 'overlay-…', '<the node ID from Step 3>')`.

### Task 5: Keep-alive away from home (owner present)

- [ ] **Step 1:** Owner moves the Mac to a phone hotspot with Tailscale still connected. Expected Home banner: "Trusted tailnet: <name> ✓".
- [ ] **Step 2:** Note the Cube card's "Checked N min ago"; press "Restart now" on the Onn card. Expected: Onn card "Checked 0 min ago" with a live status; the Cube card's time does not reset (its LAN gate is closed).
- [ ] **Step 3:** Owner quits Tailscale on the Mac; press "Restart now" on the Onn card. Expected: nothing is contacted; the Onn card's time does not reset; banner "Not on a Trusted Network".
- [ ] **Step 4:** Owner restarts Tailscale and returns home; leave the app 15 minutes. Expected: both cards checked by the timer.
- [ ] **Step 5:** Record every outcome (including failures) in `HANDOFF.md` of this worktree under a new heading `## Update 2026-11-16 (Onn over the tailnet)`.

### Task 6: Mark Phase 2 done in the design doc

**Files:** Modify `docs/design/overlay-networks.md` (section 7 "Phase 2" bullets and the section 6 checklist).

- [ ] **Step 1:** Append to each Phase 2 bullet: T2.1 "**Done** (`d6528a1`)", T2.2 "**Done** (`d6528a1`)", T2.3 "**Done** (`d358b4c`, `f1a0c37`; built as `OverlayGate.addressToUse`/`bindingFor`, the LAN gate stays `Upkeep.gateOpen`, no `Gate` interface)", T2.4 "**Done** (`d358b4c`)", T2.5 "**Done** (`f1a0c37`)", T2.6 "**Done** (`7ee79e4`)", T2.7 "**Done** (`b79bb73`; banner: <Task 2 commit>)". Change the section 6 checklist item "Until D1 lands…" to end with "**Done**: <Task 1 commit>". Add one line under Phase 2: "Device check: <Task 4/5 outcome, one line>".
- [ ] **Step 2:** Commit:

```bash
git add docs/design/overlay-networks.md HANDOFF.md
git commit -m "Overlay design: mark Phase 2 done; record the Onn tailnet check"
```
