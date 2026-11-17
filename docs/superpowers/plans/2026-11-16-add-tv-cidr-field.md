# CIDR Field on the Add TV Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the Add TV screen the user can type the range Find TVs sweeps (for example `192.168.4.0/22`) when the detected subnet is not where the TVs are; blank keeps today's automatic range.

**Architecture:** A pure function `scanRangeFor(text, trusted)` decides Default / Custom(Cidr) / Invalid. `CompanionViewModel` gains `findTvsIn(range)`; `collectFind` uses the custom block instead of `sweepScope(...)` when one is given, and Keep scanning reuses it. `Discovery` is unchanged and still refuses anything not `AddressPolicy.sweepable`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-coroutines-test, `FakeLan`.

**Spec:** `MUSE_TASK.md` ("Out of scope: … CIDR field"; this plan lifts it), `HANDOFF.md` rule "Sweep only a Trusted Network's own subnet; RFC1918 only; port 5555 only", `docs/design/overlay-networks.md` section 6 table (sweep row).

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `cm/` = `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/`, `ct/` = `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses (100.64.0.0/10) are never swept.
- commonTest cannot use `runBlocking`; never call `TvConnector.open` in discovery (`FakeTvSession.opened` stays empty in Find tests).

## Decisions assumed (owner may overrule)

- A typed range is accepted only while on a Trusted Network, only if `AddressPolicy.sweepable(range)` (RFC 1918, /16 to /30), and only if the whole range lies inside the /16 that contains the Trusted Network's gateway. This keeps "sweep only this network" meaningful without trusting the detected prefix.
- Blank means "automatic" (today's `sweepScope`). The field is not saved between visits.
- Copy: caption "Range to scan (optional)"; placeholder the current network's subnet (`state.currentNetwork?.subnet`, else `"192.168.1.0/24"`); error `Copy.BAD_RANGE = "Enter a range on this network, like 192.168.1.0/24"`.

## Review Focus

1. Host bits set (`192.168.5.7/24`): accepted as `192.168.5.0/24` (`Cidr.parse` clears them). Test in Task 1.
2. Range bigger than /16 (`10.0.0.0/8`) or smaller than /30 (`/31`, `/32`): Invalid, nothing probed. Test in Task 1.
3. A 100.64/10 or public range: Invalid. Test in Task 1.
4. Keep scanning after Stop on a custom range: resumes in the same range, never jumps to the automatic one. Test in Task 2.
5. Leading/trailing spaces and a pasted range with a newline: trimmed. Test in Task 1.

---

### Task 1: `scanRangeFor`

**Files:**
- Create: `cm/discovery/ScanRange.kt`
- Test: `ct/discovery/ScanRangeTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface RangeChoice { data object Default : RangeChoice; data class Custom(val cidr: Cidr) : RangeChoice; data object Invalid : RangeChoice }`
  - `fun scanRangeFor(text: String, trusted: TrustedNetwork): RangeChoice`

- [ ] **Step 1: Failing tests** (`trusted = TrustedNetwork("home", "Home", NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1")))`):
  - `""` and `"   "` → `Default`
  - `"192.168.4.0/22"` → `Custom(Cidr.parse("192.168.4.0/22")!!)`; `" 192.168.5.7/24\n"` → `Custom(192.168.5.0/24)`; `"192.168.9.0/24"` → `Custom` (same /16 as the gateway)
  - `Invalid` for: `"10.0.0.0/8"`, `"192.168.5.0/31"`, `"192.168.5.0/32"`, `"172.16.0.0/24"` (outside the gateway's /16), `"100.64.0.0/24"`, `"8.8.8.0/24"`, `"192.168.5.0"`, `"garbage"`, `"192.168.5.0/24/1"`
  - a trusted network whose gateway is not an IPv4 literal (`gatewayIp = ""`) → `Invalid` for any non-blank text.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*ScanRangeTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement:** blank → Default; `Cidr.parse(text.trim())`, `Ipv4.parse(trusted.fingerprint.gatewayIp)`, `Cidr.parse("$gateway/16")`; Custom when `AddressPolicy.sweepable(c) && c.network in g16 && c.broadcast in g16`; else Invalid.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Discovery: validate a typed scan range against the Trusted Network"`.

### Task 2: `findTvsIn` in the view model

**Files:**
- Modify: `cm/ui/CompanionViewModel.kt` (`Copy`, `findTvs`, `keepScanning`, `startFind`, `collectFind`)
- Test: `ct/ui/CompanionViewModelTest.kt`

**Interfaces:**
- Consumes: `scanRangeFor`, `RangeChoice`.
- Produces: `fun findTvsIn(range: String)`; `findTvs()` becomes `findTvsIn("")`; `keepScanning()` reuses the last range; `Copy.BAD_RANGE`.

- [ ] **Step 1: Failing tests** (existing `rig(trusted = true)` on `192.168.5.0/24`, gateway `192.168.5.1`):
  - `findTvsInACustomRangeSweepsOnlyThatRange`: `vm.findTvsIn("192.168.4.0/23")` → every `lan.calls` address is in `192.168.4.0/23` and some address starts with `192.168.4.`; `state.diagnosis != null`.
  - `aBadRangeErrorsAndDoesNotScan`: `vm.findTvsIn("10.0.0.0/8")` → `lan.calls` empty, `state.error == Copy.BAD_RANGE`, `state.scanning == false`, `state.diagnosis == null`.
  - `keepScanningStaysInTheCustomRange`: with a `FakeLan(delayMs = 10)`, `findTvsIn("192.168.4.0/23")`, advance a little, `cancelFind()`, `keepScanning()`, `advanceUntilIdle()` → every call address is in `192.168.4.0/23`.
  - `blankRangeIsTheAutomaticSweep`: two rigs; `a.vm.findTvs()` and `b.vm.findTvsIn("")` → `a.lan.calls.map { it.address } == b.lan.calls.map { it.address }` and the list is non-empty.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*CompanionViewModelTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3: Implement.** Store `private var findRange = ""`. `findTvsIn(range)` sets it and calls `startFind(clearFound = true)`; `keepScanning()` leaves it. In `collectFind`, after `trusted`/`subnet` are known: `when (val c = scanRangeFor(findRange, trusted))` — `Invalid` → `refresh(error = Copy.BAD_RANGE, settleBusy = false); return false`; `Custom` → plan subnet `c.cidr`; `Default` → today's `sweepScope(subnet, own, gateway)`.
- [ ] **Step 4:** Re-run — Expected: PASS, and all older Find tests still pass.
- [ ] **Step 5:** Commit `git commit -m "Companion: Find TVs can sweep a typed range on the Trusted Network"`.

### Task 3: The field on the Add screen

**Files:**
- Modify: `cm/ui/AddTvScreen.kt` (parameter `onFind`, new field)
- Modify: `cm/ui/Navigation.kt` (the `AddTvScreen(...)` call, line 77 today)

**Interfaces:**
- `AddTvScreen(state, onFind: (String) -> Unit, onAddAddress, onStop, onKeepScanning, onBack)`; Navigation passes `vm::findTvsIn`.

- [ ] **Step 1:** Add `var range by remember { mutableStateOf("") }`; below the Add/Find row add `SsText("Range to scan (optional)", Ss.caption)` and `SsTextField(range, { range = it }, state.currentNetwork?.subnet ?: "192.168.1.0/24")`; the Find TVs button calls `onFind(range)`. Show `state.error` as today (BAD_RANGE uses the same line).
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest :companion-desktop:test --console=plain` and `./gradlew :companion-android:assembleDebug --console=plain` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 3 (owner, with OK):** `./gradlew :companion-desktop:createDistributable`, open the app, type the owner's /22 (from `HANDOFF.md`: the owner's network is a /22; ask for the exact block), Find TVs. Expected: "Scanned N of M" with M about 1021 (a /22 has 1022 hosts, minus this computer's own address) and the Cube found.
- [ ] **Step 4:** Commit `git commit -m "Add TV: optional range to scan"`.
