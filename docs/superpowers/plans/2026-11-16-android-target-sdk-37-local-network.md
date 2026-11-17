# Android Companion targetSdk 37 and ACCESS_LOCAL_NETWORK Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android companion targets SDK 37 and asks for the new local-network runtime permission at the right moment, so connections to TVs keep working on Android 17.

**Architecture:** Manifest gains `android.permission.ACCESS_LOCAL_NETWORK`; `targetSdk` goes 36 to 37 (`compileSdk` is already 37). A pure rule `LocalNetworkAsk.shouldAsk(...)` (JVM-tested) decides when `MainActivity` launches the request, mirroring how it asks for `POST_NOTIFICATIONS`. Probe P7 informs the copy, it does not block the build.

**Tech Stack:** Android SDK 37 (`/opt/homebrew/share/android-commandlinetools/platforms/android-37.0`), ActivityResult `RequestPermission`, JUnit 4.

**Spec:** `docs/design/overlay-networks.md` section 6 "Permissions and prompts" (Android bullet) and probe P7; Android "Local network permission" (design source 14).

Verified in this repository: `android-37.0/android.jar` contains `Manifest.permission.ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"`, permission group `NEARBY_DEVICES`, and `Build.VERSION_CODES.CINNAMON_BUN = 37`. `companion-android/build.gradle.kts` has `compileSdk = 37`, `targetSdk = 36`. Build-tools present: 34.0.0, 36.0.0.

Path shorthands (relative to `/Users/mdez/Developer/SideSpeed-muse`): `and/` = `companion-android/src/main/kotlin/io/github/devjardins/sidespeed/companion/android/`, `andt/` = `companion-android/src/test/kotlin/io/github/devjardins/sidespeed/companion/android/`.

## Global Constraints

- Work in `/Users/mdez/Developer/SideSpeed-muse` on `feature/m2-discovery-muse` (or a branch cut from it). Never edit `/Users/mdez/Developer/SideSpeed`.
- Do not add attribution lines to git commit messages.
- Concise copy in UI strings.
- One Gradle run at a time; run `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` first.
- Android companion passes no overlay access (overlay is desktop only).
- `AddressPolicy.sweepable` stays RFC 1918 /16 to /30.
- Overlay addresses are never swept.
- No location permission.
- Do not install on a device without the owner's go-ahead in chat.

## Decisions assumed (owner may overrule)

- Ask once per app process, on the first `onStart` after a Trusted Network exists (`!state.firstRun`), on SDK 37+, when not granted. Never during first run (nothing touches the network before a Trusted Network is chosen, per the companion spec). After a denial, do not re-ask in that process; the system decides later re-asks.
- If P7 shows 100.x via Tailscale works without the permission, nothing changes in code (Android has no overlay trust); record it only.
- No new in-app copy in this plan: the system dialog explains itself. A denied permission shows up as TVs going Offline (blocked connects look like timeouts, design section 6). A Home hint for that is a follow-up, not planned here.
- `testDebugUnitTest` runs JVM tests only; there is no Robolectric/instrumented test for the dialog.

## Review Focus

1. Device below Android 17: never asks (rule returns false for `sdkInt < 37`). Test in Task 1.
2. Already granted (upgrade from a build that already had it): never asks. Test in Task 1.
3. First run (no Trusted Network yet): never asks. Test in Task 1.
4. Background `UpkeepWorker` runs on a device where the user denied: no crash; connects fail and TVs go Offline. Device check in Task 3.
5. Rotation recreates the Activity: no second dialog in the same process. Test in Task 1 (`alreadyAsked`), wired with a process-level flag in Task 2.

---

### Task 1: `LocalNetworkAsk.shouldAsk`

**Files:**
- Create: `and/LocalNetworkAsk.kt`
- Test: `andt/LocalNetworkAskTest.kt`

**Interfaces:**
- Produces: `object LocalNetworkAsk { const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"; fun shouldAsk(sdkInt: Int, granted: Boolean, firstRun: Boolean, alreadyAsked: Boolean): Boolean }` = `sdkInt >= 37 && !granted && !firstRun && !alreadyAsked`.

- [ ] **Step 1: Failing tests**: `asksOnAndroid17AfterATrustedNetworkExists` (`shouldAsk(37, false, false, false) == true`); `neverBelowAndroid17` (`shouldAsk(36, false, false, false) == false`); `neverWhenGranted`; `neverDuringFirstRun`; `onlyOncePerProcess` (`alreadyAsked = true` → false).
- [ ] **Step 2:** Run `./gradlew :companion-android:testDebugUnitTest --tests '*LocalNetworkAskTest*' --console=plain` — Expected: compile FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Re-run — Expected: PASS.
- [ ] **Step 5:** Commit `git commit -m "Android companion: rule for when to ask for local network access"`.

### Task 2: Manifest, targetSdk 37 and the request

**Files:**
- Modify: `companion-android/src/main/AndroidManifest.xml` (add the permission next to `INTERNET`)
- Modify: `companion-android/build.gradle.kts` (`targetSdk = 37`, update the comment on `compileSdk`)
- Modify: `and/MainActivity.kt` (inside `setContent`, next to `askNotifications`)
- Modify: `and/CompanionApplication.kt` (`var askedLocalNetwork = false` on `CompanionApplication`)

- [ ] **Step 1 (failing check):** Run `./gradlew :companion-android:assembleDebug --console=plain`, then `$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions companion-android/build/outputs/apk/debug/*.apk | grep -c ACCESS_LOCAL_NETWORK` — Expected: `0`.
- [ ] **Step 2:** Add `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />`; set `targetSdk = 37`. In `MainActivity.setContent`: `val askLocal = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}`; `LaunchedEffect(state.loaded, state.firstRun)` → if loaded and `LocalNetworkAsk.shouldAsk(Build.VERSION.SDK_INT, checkSelfPermission(LocalNetworkAsk.PERMISSION) == PackageManager.PERMISSION_GRANTED, state.firstRun, app.askedLocalNetwork)` then set `app.askedLocalNetwork = true` and `askLocal.launch(LocalNetworkAsk.PERMISSION)`.
- [ ] **Step 3:** Run `./gradlew :companion-android:testDebugUnitTest :companion-android:assembleDebug --console=plain` — Expected: BUILD SUCCESSFUL (fix any new targetSdk 37 lint errors the build reports; do not suppress them without saying so in the commit). Then the `aapt2 dump permissions` command — Expected: a line containing `android.permission.ACCESS_LOCAL_NETWORK`; and `$ANDROID_HOME/build-tools/36.0.0/aapt2 dump badging companion-android/build/outputs/apk/debug/*.apk | grep targetSdkVersion` — Expected: `targetSdkVersion:'37'`.
- [ ] **Step 4:** Commit `git commit -m "Android companion: target SDK 37 and ask for local network access"`.

### Task 3: Device checks and probe P7 (owner)

- [ ] **Step 1:** Ask the owner whether an Android 17 device is available. If none, record "P7 and the Android 17 dialog not device-tested" in `HANDOFF.md` and stop here.
- [ ] **Step 2:** With OK: install the debug APK, open the app on the home Wi-Fi (Trusted Network already saved). Expected: the system's nearby-devices/local-network dialog once. Allow: "Restart now" on the Cube works.
- [ ] **Step 3 (P7):** Uninstall/reinstall, deny the dialog, Tailscale on, add the Cube by its 100.x if it has one (else any tailnet TV). Record whether the connect succeeds (decides whether overlays need the prompt). Then "Restart now" on the LAN TV: Expected: fails like a timeout, card goes toward Offline; no crash.
- [ ] **Step 4:** Record P7 in `HANDOFF.md` and `docs/design/overlay-networks.md` section 9; commit `git commit -m "Record probe P7 (Android 17 local network permission)"`.
