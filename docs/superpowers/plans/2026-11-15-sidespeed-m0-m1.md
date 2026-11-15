# SideSpeed M0 + M1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideloadable Android TV app that connects to its own ADB, over classic 5555 or Wireless Debugging, and lets the user pick an app, compile it with `pm compile`, and see the result and the compiler status. Before the setup UI is built, a throwaway spike checks the risky ADB behaviours on the owner's two boxes.

**Architecture:**
- Single `:app` module.
- **ADB layer:** an `AdbBackend` interface, implemented on libadb-android, with key storage in app-private files.
- **Pure logic:** compile commands, the dexopt status parser and the app-picker sort are pure Kotlin, unit-tested on the JVM.
- **Wiring:** a manual `AppContainer` connects the pieces to Compose-for-TV screens.

**Tech Stack:**
- Kotlin.
- AGP and Gradle Kotlin DSL with a version catalog.
- Jetpack Compose for TV (`androidx.tv:tv-material`) and Navigation Compose.
- DataStore Preferences.
- libadb-android 3.1.1 (GPL-3.0-or-later OR Apache-2.0), Conscrypt-android 2.5.3, BouncyCastle (via libadb).
- JUnit4 and kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-11-15-sidespeed-design.md`, which builds on `SPEC.md`. Read both.

**Scope:** this plan covers milestones M0 (spike) and M1 from the design. M2 and M3 get their own plans after the M0 results are in:
- M2: tracked apps, auto-recompile, "ADB only while working", "Survive reboots", Popular presets, full Settings.
- M3: polish, README device table, CI.

## Global Constraints

- Application ID / package root: `io.github.devjardins.sidespeed`.
- minSdk 24, targetSdk 36, compileSdk 36. Kotlin JVM target 17.
- License GPLv3. Every dependency must be GPLv3-compatible. Take libadb-android under its GPL-3.0-or-later option.
- Permissions: `INTERNET`, `QUERY_ALL_PACKAGES`. Set `android:allowBackup="false"` so the ADB key never leaves the device.
- Leanback: `android.software.leanback` uses-feature with `required="false"`, `android.hardware.touchscreen` with `required="false"`, a `LEANBACK_LAUNCHER` intent filter, and a 320×180 `android:banner`.
- Every screen is fully usable with a D-pad: every interactive element is focusable and has a visible focus state. Nothing requires touch or the soft keyboard; the pairing code uses a D-pad number grid.
- SideSpeed only runs `pm compile` / `cmd package compile`, `dumpsys package ...` and read-only queries over ADB. The spike also runs `tcpip:5555` and `pm grant <self> WRITE_SECURE_SETTINGS`. Errors from `pm compile` are shown word for word, never swallowed.
- The LAN-exposure warning must be acknowledged before the first ADB connection.
- Signing secrets come from `keystore.properties` or environment variables and are never committed (both are already in `.gitignore`).
- Commit messages: imperative, no attribution trailers. Commit locally; do not push unless the owner asks.

## Review Focus

1. **Manual package entry with shell metacharacters** (e.g. `com.foo; reboot`, spaces, quotes). It must be rejected before any shell command is built. Test in Task 6.
2. **A package uninstalled between picking and compiling.** Both compile attempts fail; show Failure with the verbatim `pm` output. Never crash, never show a false Success. Test in Task 6.
3. **ADB connection drops mid-compile** (adbd restarts, or the user toggles debugging). The run ends in Failure with "Connection lost". It must not hang. Test in Task 6.
4. **dumpsys output where the package is missing, or has several ABIs or secondary dex entries.** Show "Unknown", never a false "speed ✓". Test in Task 5.
5. **The "Allow debugging" prompt is ignored or denied.** Connecting gives up after a timeout with a clear message instead of spinning forever. Test in Task 8.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew*, gradle/wrapper/*
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/banner.xml (+ mipmap icon)            # 320x180 banner
app/src/main/java/io/github/devjardins/sidespeed/
  SideSpeedApp.kt            # Application; builds AppContainer
  AppContainer.kt            # manual DI: backend, prefs, repositories
  MainActivity.kt            # Compose host + nav graph
  adb/AdbBackend.kt          # interface + AdbTarget + ShellEvent
  adb/ShellExit.kt           # exit-code marker wrap/parse (pure)
  adb/AdbIdentity.kt         # generate/load RSA key + X509 cert in filesDir
  adb/LibAdbBackend.kt       # libadb-android implementation
  adb/ConnectionStrategy.kt  # classic → TLS order, timeouts
  compile/CompileModels.kt   # CompileMode, CompileEvent, PackageName validation
  compile/CompileRunner.kt   # pm compile → cmd package compile fallback
  status/DexoptParser.kt     # dumpsys → CompilerStatus (pure)
  status/StatusChecker.kt    # runs dumpsys over AdbBackend
  apps/AppEntry.kt           # model + isSideloaded + sortForPicker (pure)
  apps/InstalledApps.kt      # PackageManager queries
  data/Prefs.kt              # DataStore: lanWarningAcked, pairedTls, lastCompile
  ui/theme/Theme.kt
  ui/setup/SetupScreen.kt, SetupViewModel.kt, PairingCodeGrid.kt
  ui/apps/AppsScreen.kt, AppsViewModel.kt
  ui/compile/CompileScreen.kt, CompileViewModel.kt
app/src/debug/java/io/github/devjardins/sidespeed/spike/SpikeActivity.kt   # M0 only, deleted in Task 10
app/src/debug/AndroidManifest.xml
app/src/test/java/io/github/devjardins/sidespeed/...       # JVM tests
app/src/test/resources/dumpsys/*.txt                       # captured samples
docs/spike/M0-runbook.md, docs/spike/M0-results.md
```

---

### Task 1: Toolchain and project scaffold

**Files:**
- Create: the Gradle files above, `app/build.gradle.kts`, `AndroidManifest.xml`, `SideSpeedApp.kt`, `MainActivity.kt` (shows a placeholder "SideSpeed" text), `ui/theme/Theme.kt`, `res/drawable/banner.xml`, a launcher icon.
- Test: `app/src/test/java/io/github/devjardins/sidespeed/SmokeTest.kt`

**Interfaces:**
- Produces: a buildable `:app`, plus the `SideSpeedApp` Application class that later tasks hang `AppContainer` on.

- [ ] **Step 1: Install the toolchain.** Ask the owner before installing anything.
  ```bash
  brew install --cask temurin@21 android-commandlinetools
  brew install gradle
  sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
  ```
  Then set `ANDROID_HOME` (`/opt/homebrew/share/android-commandlinetools`) and write `local.properties` with `sdk.dir`.
  Expected: `java -version` shows 21, and `adb version` prints a version.
- [ ] **Step 2: Scaffold the project.**
  - Use the latest stable AGP, Kotlin and Compose versions in `libs.versions.toml`.
  - Add the JitPack repository (`https://jitpack.io`) for libadb-android.
  - Dependencies:
    - `com.github.MuntashirAkon:libadb-android:3.1.1`
    - `org.conscrypt:conscrypt-android:2.5.3`
    - `com.github.MuntashirAkon:sun-security-android:1.1`
    - `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`
    - `androidx.tv:tv-material`, `androidx.navigation:navigation-compose`, `androidx.datastore:datastore-preferences`
    - test: `junit:junit:4.13.2`, `org.jetbrains.kotlinx:kotlinx-coroutines-test`
  - Generate the wrapper with `gradle wrapper`.
  - The manifest carries everything in Global Constraints.
- [ ] **Step 3: Write `SmokeTest.smoke()`** asserting `true`. It exists so the test task runs.
- [ ] **Step 4: Build.**
  Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL, and `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 5: Commit** with message `Scaffold Android TV project`.

### Task 2: ADB identity and shell exit codes

**Files:**
- Create: `adb/AdbIdentity.kt`, `adb/ShellExit.kt`
- Test: `app/src/test/.../adb/ShellExitTest.kt`, `app/src/test/.../adb/AdbIdentityTest.kt`

**Interfaces:**
- Produces:
  - `class AdbIdentity(dir: File)` with `fun loadOrCreate(): Pair<PrivateKey, Certificate>`. It stores `adb_key.pk8` and `adb_cert.der` in `dir`. It generates only if the files are missing, and returns the same key on every later call.
  - `object ShellExit`, with `MARKER = "__SIDESPEED_EXIT__:"`:
    - `fun wrap(cmd: String): String`, which returns `"$cmd; echo $MARKER\$?"`.
    - `fun parse(line: String): Int?`, which returns the code if `line.trim()` starts with `MARKER`, else null.

  libadb uses the shell v1 protocol, which reports no exit code; this marker supplies one.

- [ ] **Step 1: Write the failing tests.**
  - `ShellExitTest`:
    - `wrap("pm compile -m speed -f a.b") == "pm compile -m speed -f a.b; echo __SIDESPEED_EXIT__:\$?"`
    - `parse("__SIDESPEED_EXIT__:0") == 0`
    - `parse("  __SIDESPEED_EXIT__:255\r") == 255`
    - `parse("Success") == null`
  - `AdbIdentityTest`:
    - In a `TemporaryFolder`, calling `loadOrCreate()` twice returns keys with equal `encoded` bytes.
    - The algorithm is `"RSA"`.
    - The modulus is 2048 bits.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest`.** Expected: FAIL (the classes don't exist).
- [ ] **Step 3: Implement both.**
  - Key: `KeyPairGenerator("RSA", 2048)`, persisted as PKCS#8.
  - Certificate: self-signed X509, built with `sun-security-android` as in the libadb-android README sample, CN `SideSpeed`, valid for 10 years.
  - If `AdbIdentityTest` can't run the cert code on the JVM, keep the cert assertion out of the JVM test and cover it in Task 4 on device.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** with message `Add persistent ADB identity and shell exit marker`.

### Task 3: AdbBackend on libadb-android

**Files:**
- Create: `adb/AdbBackend.kt`, `adb/LibAdbBackend.kt`, `AppContainer.kt`
- Test: `app/src/test/.../adb/FakeAdbBackend.kt` (a test helper that later tasks reuse)

**Interfaces:**
- Consumes: `AdbIdentity`, `ShellExit` (Task 2).
- Produces:
  ```kotlin
  sealed interface AdbTarget {
      data class Classic(val host: String = "127.0.0.1", val port: Int = 5555) : AdbTarget
      data object WirelessTls : AdbTarget            // mDNS discovery via libadb connectTls
  }
  sealed interface ShellEvent {
      data class Line(val text: String) : ShellEvent
      data class Exit(val code: Int) : ShellEvent
  }
  class AdbConnectionLost(cause: Throwable?) : Exception(cause)
  interface AdbBackend {
      val isConnected: Boolean
      suspend fun connect(target: AdbTarget): Result<Unit>
      suspend fun pair(host: String, port: Int, code: String): Result<Unit>
      fun shell(command: String): Flow<ShellEvent>   // emits Lines then exactly one Exit; throws AdbConnectionLost if the stream dies
      suspend fun openService(service: String): Result<String>   // e.g. "tcpip:5555"; returns service output
      fun close()
  }
  ```
  - `FakeAdbBackend` scripts responses for each command: `on(command) { lines, exit }`, `failConnect`, `dropAfterLines(n)`. It records `commands: List<String>`.
  - `AppContainer(context)` exposes `val adb: AdbBackend`.

- [ ] **Step 1: Implement `LibAdbBackend`.**
  - **Connection manager:** an inner `AbsAdbConnectionManager` subclass that returns the `AdbIdentity` key and cert, `getDeviceName() = "SideSpeed"`, and calls `setApi(Build.VERSION.SDK_INT)`.
  - **Conscrypt:** call `Security.insertProviderAt(Conscrypt.newProvider(), 1)` once in `SideSpeedApp.onCreate`.
  - **Calls:**
    - `connect(Classic)` calls `connect(host, port)`.
    - `connect(WirelessTls)` calls `connectTls(context, 10_000)`.
    - `pair` calls `pair(host, port, code)`.
    - `shell` opens `openStream("shell:" + ShellExit.wrap(cmd))` and reads lines on `Dispatchers.IO`. A marker line becomes `Exit` and is not emitted as a `Line`. If the stream closes before the marker, it throws `AdbConnectionLost`.
  - All blocking calls run on `Dispatchers.IO`, wrapped in `runCatching`.
- [ ] **Step 2: Write `FakeAdbBackend`** in the test sources, implementing the interface above.
- [ ] **Step 3: Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`.** Expected: BUILD SUCCESSFUL. The real backend is verified on device in Task 4.
- [ ] **Step 4: Commit** with message `Add AdbBackend interface and libadb-android implementation`.

### Task 4: M0 spike on the owner's devices (throwaway)

**Files:**
- Create: `app/src/debug/.../spike/SpikeActivity.kt`, `app/src/debug/AndroidManifest.xml` (registers the activity with its own `LEANBACK_LAUNCHER` entry, label "SideSpeed Spike"), `docs/spike/M0-runbook.md`, `docs/spike/M0-results.md` (template)

**Interfaces:**
- Consumes: `AppContainer.adb` (Task 3).

- [ ] **Step 1: Build `SpikeActivity`.** It's a plain Compose-for-TV column of focusable buttons, plus a scrolling log. Every log line is also written to `Log.i("SideSpeedSpike", ...)`. Buttons:
  1. "Connect 127.0.0.1:5555".
  2. "Pair". Uses a `PairingCodeGrid`-style D-pad entry for the 6-digit code and port. Host `127.0.0.1`; if that fails, try the device's Wi-Fi IP and log both.
  3. "Connect TLS (mDNS)".
  4. "Run `id`" (expected `uid=2000(shell)`).
  5. "Run `dumpsys package dexopt`". Writes the output to `filesDir/dexopt.txt` and logs the path.
  6. "Compile com.nuvio.tv (speed)" with the fallback.
  7. "`pm grant` self WRITE_SECURE_SETTINGS".
  8. "Toggle ADB off→on". Uses `Settings.Global.putInt` on `adb_enabled`, plus `adb_wifi_enabled` on API 30+. It waits up to 20 s, then reconnects with the last good target and logs how long it took.
  9. "Send tcpip:5555".
- [ ] **Step 2: Write `docs/spike/M0-runbook.md`.**
  - Per-device steps for the owner: enable developer options and debugging, then install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
  - The button order to try, plus a reboot test for the Onn: after a reboot, does Wireless Debugging stay on, and does toggle 8 bring it back?
  - The PC capture commands:
    - `adb shell dumpsys package dexopt > <device>-dexopt.txt`
    - `adb shell dumpsys package com.nuvio.tv > <device>-pkg.txt`
    - `adb logcat -d -s SideSpeedSpike > <device>-spike.log`
  - `M0-results.md` has one table row per question from the design's M0 list, with yes/no/notes columns for each device.
- [ ] **Step 3: Build and check locally.**
  Run: `./gradlew :app:assembleDebug`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** with message `Add M0 spike activity and runbook`.
- [ ] **Step 5: HAND-OFF. Stop and wait for the owner.**
  - The owner runs the runbook on the Fire TV Cube Gen 3 and the Onn 4K Pro. The Onn has to be set up again first.
  - The owner returns the logs and dumpsys files. Save them to `app/src/test/resources/dumpsys/{firetv-cube3,onn4kpro}-{dexopt,pkg}.txt` and fill in `M0-results.md`.
  - Tell the owner which setup route each device needs, then carry on. If the results contradict the design (for example, pairing over loopback is impossible, or the key isn't accepted over TLS), stop and bring it to the owner before Task 8.

  Tasks 5–7 don't depend on the results and may proceed while waiting. Task 8 must not start until the hand-off is done.

### Task 4b: M0b helper spike (throwaway, added by design revision 2)

Design ref: "Built-in helper" section and milestone M0b. Everything lives in `app/src/debug/` and `docs/spike/`. Task 10 deletes it with the rest of the spike.

**Files:**
- Create: `app/src/debug/java/io/github/devjardins/sidespeed/spike/HelperSpikeMain.kt` (an `object` with `@JvmStatic fun main(args: Array<String>)`)
- Modify: `SpikeActivity.kt` (add buttons 10–12 below), `docs/spike/M0-runbook.md` (add a "Part C: helper" section), `docs/spike/M0-results.md` (add M0b rows)

**What `HelperSpikeMain` does when started with** `CLASSPATH=<apk> app_process /system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.spike.HelperSpikeMain`:
1. **Startup.** Log its uid, pid and APK path to stdout and to `/data/local/tmp/sidespeed_helper.log` (append, one timestamped line per event).
2. **Socket.** Listen on abstract socket `sidespeed_helper` (`android.net.LocalServerSocket`). For each connection:
   - Read the peer uid with `LocalSocket.peerCredentials`.
   - Accept only SideSpeed's uid (looked up at start via `pm list packages -U io.github.devjardins.sidespeed` or the equivalent) or uid 2000 (spike only, so the controller can test from `adb shell`). Refuse others: log the refusal and close.
   - Requests, one text line each:
     - `ping` → `pong uid=<helperUid> pid=<pid> apk=<path>`
     - `id` → the output of `id`
     - `compile <pkg>` → streams `pm compile -m speed -f <pkg>` output, validating `<pkg>` with the same regex as Task 6
     - `adboff <seconds>` → writes `settings put global adb_enabled 0`, sleeps `<seconds>` (max 120), then writes 1 again, logging each step
   - Every response ends with a line `__END__:<exit>`.
3. **Heartbeat.** Append a heartbeat line to the log every 5 s.
4. **Self-exit.** Exit if its APK file disappears or changes (check every 5 s).
5. **Detaching.** It must keep running after the launching `adb shell` exits. Launch it with `nohup ... &`, `setsid`, or both, whichever works; record which in the report.

**SpikeActivity additions:**
- **10 "Start helper via own ADB":** over the existing `AdbBackend` shell (needs button 1 or 3 connected first), runs the launch command with the APK path from `applicationInfo.sourceDir`, backgrounded.
- **11 "Ping helper":** connects to `@sidespeed_helper` with `LocalSocket` and logs the reply.
- **12 "Compile com.nuvio.tv via helper"**, logging the duration.

**Runbook Part C** (for the owner's Onn, and for the controller on the Fire TV):
1. Start the helper.
   - Fire TV: from a PC, run `adb shell 'CLASSPATH=$(pm path io.github.devjardins.sidespeed | cut -d: -f2) nohup app_process /system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.spike.HelperSpikeMain >/dev/null 2>&1 &'`. Use the exact working form you find, and put it in the runbook.
   - Onn: press 10.
2. Press 11: does it answer, and with uid=2000?
3. Press 12: does it compile?
4. Close the PC's adb session (`adb kill-server`), wait 30 s, and press 11. Is it still alive?
5. ADB-off test. From `adb shell`, send `adboff 60` to the helper; `toybox nc` can't reach abstract sockets, so add a tiny client mode `HelperSpikeMain --client "<request>"` launched with the same app_process command. Then:
   - Press 11 while ADB is off. Does it answer?
   - After the helper re-enables ADB, check the heartbeat log for gaps.
6. Update test: `adb install -r` the same APK. Does the helper exit?
7. Reboot test: after a reboot, the helper should be gone. Confirm with 11.

- [ ] **Step 1:** Implement `HelperSpikeMain`, buttons 10–12 and the runbook Part C. Make sure the helper's `main` can run outside an Activity context: no Application, no `Context`, plain JVM plus the Android framework classes that are usable from `app_process`.
- [ ] **Step 2:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit with message `Add M0b helper spike`.
- [ ] **Step 4 (controller):** Run Part C on the Fire TV Cube, which is reachable from the Mac over adb at 192.168.5.140:5555. Record the results in `M0-results.md`.

### Task 5: Dexopt status parser

**Files:**
- Create: `status/DexoptParser.kt`, `status/StatusChecker.kt`
- Test: `app/src/test/.../status/DexoptParserTest.kt`, `app/src/test/.../status/StatusCheckerTest.kt`
- Test resources: `dumpsys/aosp-n-dexopt.txt`, `dumpsys/aosp-p-dexopt.txt`, `dumpsys/aosp-s-dexopt.txt`. Write these by hand from the AOSP `PackageDexOptimizer`/`ArtManagerLocal` dump formats. Add the device captures from Task 4 when they arrive.

**Interfaces:**
- Consumes: `AdbBackend`, `ShellEvent` (Task 3).
- Produces:
  ```kotlin
  data class CompilerStatus(val filter: String?)        // null = package/filter not found
  enum class StatusBadge { SPEED_OK, SPEED_PROFILE, NEEDS_RECOMPILE, UNKNOWN }
  object DexoptParser {
      fun parse(dump: String, pkg: String): CompilerStatus
      fun badge(status: CompilerStatus): StatusBadge
  }
  class StatusChecker(adb: AdbBackend) { suspend fun check(pkg: String): CompilerStatus }
  ```
  Badge mapping: `"speed"` → `SPEED_OK`; `"speed-profile"` → `SPEED_PROFILE`; `null` → `UNKNOWN`; anything else (`verify`, `quicken`, `run-from-apk`, `interpret-only`, ...) → `NEEDS_RECOMPILE`. UI labels: "speed ✓", "speed-profile", "Needs recompile", "Unknown".

**Parser rules:**
1. Find the line that is exactly `[<pkg>]` after trimming. Using exact bracket matching means `com.nuvio.tv` doesn't match `com.nuvio.tv.beta`.
2. The block runs until the next line that starts with `[` at the same indentation.
3. Inside the block, ignore everything after a line containing `known secondary dex files` (secondary dex isn't the app's main code).
4. Take the first `status=([a-z-]+)` match, or for Android 7/8 the first `compilation_filter=([a-z-]+)` match. Status tokens beginning with `k` (like `kOatUpToDate`) aren't filters, so skip them.
5. If the block has several ABI lines with different filters, return the lowest one, using the order `speed > speed-profile > everything else`. That way a half-compiled app never shows "speed ✓".

- [ ] **Step 1: Write the failing tests.**
  - Each AOSP sample parses to the filter it contains: `parse(sampleN, "com.nuvio.tv").filter == "speed"`. Also check one `speed-profile` and one `verify` case.
  - `parse(sample, "com.nuvio.tv.beta").filter == null` when only `com.nuvio.tv` is present, and vice versa.
  - A block whose arm64 line is `speed` and arm line is `verify` gives badge `NEEDS_RECOMPILE`.
  - A secondary-dex line with `status=speed` under a primary `verify` gives `NEEDS_RECOMPILE`.
  - `parse("", "x.y").filter == null`, and the badge is `UNKNOWN`.
  - `StatusCheckerTest`, with `FakeAdbBackend`:
    - `dumpsys package dexopt` exits 0 but has no block for the package, so it falls back to `dumpsys package com.nuvio.tv` and parses that.
    - The recorded commands are exactly those two, in that order.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*Dexopt*' --tests '*StatusChecker*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `DexoptParser` (using the rules above) and `StatusChecker`.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: When the Task 4 captures arrive,** add one test per device file asserting the filter seen on that device. Run, PASS.
- [ ] **Step 6: Commit** with message `Add dexopt status parser and checker`.

### Task 6: Compile runner

**Files:**
- Create: `compile/CompileModels.kt`, `compile/CompileRunner.kt`
- Test: `app/src/test/.../compile/PackageNameTest.kt`, `app/src/test/.../compile/CompileRunnerTest.kt`

**Interfaces:**
- Consumes: `AdbBackend`, `ShellEvent`, `AdbConnectionLost` (Task 3).
- Produces:
  ```kotlin
  enum class CompileMode(val arg: String) { SPEED("speed"), SPEED_PROFILE("speed-profile") }
  @JvmInline value class PackageName private constructor(val value: String) {
      companion object { fun parse(raw: String): PackageName? }   // regex below, after trim
  }
  sealed interface CompileEvent {
      data class Attempt(val command: String) : CompileEvent
      data class Output(val line: String) : CompileEvent
      data class Finished(val success: Boolean, val usedFallback: Boolean, val message: String) : CompileEvent
  }
  class CompileRunner(adb: AdbBackend) {
      fun run(pkg: PackageName, mode: CompileMode): Flow<CompileEvent>
  }
  ```
  - **Package name regex:** `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`. Only a `PackageName` can reach a shell command.
  - **Commands:**
    - Primary: `pm compile -m <arg> -f <pkg>`.
    - Fallback: `cmd package compile -m <arg> -f <pkg>`.
  - **Success rule:** exit code 0 and some output line equal to `Success` after trimming. Otherwise, run the fallback.
  - **Finished message:**
    - Success: `"Success"`.
    - Both attempts fail: `"Failure"`, and the verbatim `Output` lines have already been emitted.
    - Connection lost (`AdbConnectionLost`): `"Connection lost"`, with no fallback attempt.

- [ ] **Step 1: Write the failing tests.**
  - `PackageName.parse` accepts `com.nuvio.tv` and `org.xbmc.kodi`.
  - It rejects `nuvio`, `com.foo; reboot`, `com.foo bar`, `com.foo'`, `com.foo$(id)`, `""`, and `1com.foo`.
  - `CompileRunnerTest`, with `FakeAdbBackend`:
    - **Primary succeeds:** the events are `[Attempt("pm compile -m speed -f com.nuvio.tv"), Output("Success"), Finished(true, false, "Success")]`.
    - **Primary fails, fallback succeeds:** primary exits 1 with `Error: unknown command`; fallback prints `Success` and exits 0. Expect `Finished(true, true, "Success")`, with two Attempts in order.
    - **Both fail (uninstalled package):** both print `Failure: package com.gone.app not found` and exit 1. Expect `Finished(false, true, "Failure")`, and that output line appears in the `Output` events.
    - **Exit 0 without a `Success` line:** this counts as a failure, so the fallback runs.
    - **Connection lost:** `dropAfterLines(1)` on the primary gives `Finished(false, false, "Connection lost")`, and only one command is recorded.
    - **`SPEED_PROFILE`:** the command uses `-m speed-profile`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*PackageName*' --tests '*CompileRunner*'`. Expected: FAIL.
- [ ] **Step 3: Implement** both files.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** with message `Add compile runner with pm/cmd fallback`.

### Task 7: Installed-app listing

**Files:**
- Create: `apps/AppEntry.kt`, `apps/InstalledApps.kt`
- Test: `app/src/test/.../apps/AppEntryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AppEntry(val packageName: String, val label: String, val installer: String?, val lastUpdateTime: Long) {
      val isSideloaded: Boolean get() = installer !in STORE_INSTALLERS
  }
  val STORE_INSTALLERS = setOf("com.android.vending", "com.amazon.venezia")
  fun sortForPicker(apps: List<AppEntry>): List<AppEntry>   // sideloaded first, then label case-insensitive
  class InstalledApps(context: Context) {
      fun list(): List<AppEntry>                     // launchable apps (LEANBACK_LAUNCHER or LAUNCHER), excluding SideSpeed itself
      fun find(pkg: String): AppEntry?               // null if not installed
  }
  ```
  - **Installer:** `getInstallSourceInfo(pkg).installingPackageName` on API 30+, `getInstallerPackageName(pkg)` on older versions. A null installer counts as sideloaded.

- [ ] **Step 1: Write the failing tests.**
  - `isSideloaded` is false for each store installer, and true for `null` and for `com.google.android.packageinstaller`.
  - `sortForPicker` on `[Store "Alpha", Side "zeta", Side "Beta", Store "aardvark"]` gives `[Beta, zeta, aardvark, Alpha]`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*AppEntry*'`. Expected: FAIL.
- [ ] **Step 3: Implement** both files. `InstalledApps` queries both launcher categories and de-duplicates by package.
- [ ] **Step 4: Run the tests.** Expected: PASS.
- [ ] **Step 5: Commit** with message `Add installed-app listing with sideload-first sort`.

## Revised Tasks after M0/M0b (design revisions 2 and 3)

The original Tasks 8–10 are replaced by Tasks 5b, 8, 8b, 9, 10 and 11 below. Device facts they rely on are in `docs/spike/M0-results.md`:
- On-box ADB is blocked on Fire OS but works on the Onn.
- The abstract Unix socket is SELinux-denied for apps on both boxes, while loopback TCP works and the shell can read `/proc/net/tcp*` with a uid column.
- Compiles take 51 s on the Onn and 3 min 09 s on the Fire TV.
- Port 5555 and Wireless Debugging are both gone after an Onn reboot.
- The helper dies when ADB is switched off, and exits when SideSpeed is updated.

No task switches ADB or Wireless Debugging off or on (design revision 3).

### Task 5b: Status parser — device samples and hardening

**Files:** Modify `status/DexoptParser.kt`, `app/src/test/.../status/DexoptParserTest.kt`. The samples already exist in `app/src/test/resources/dumpsys/`.

- [ ] **Step 1: Failing tests.**
  - `parse(firetv-cube3-dexopt.txt, "com.nuvio.tv").filter == "speed"`
  - `parse(onn4kpro-dexopt.txt, "com.nuvio.tv").filter == "speed"`
  - `parse(onn4kpro-dexopt-before.txt, "com.nuvio.tv").filter == "speed-profile"`
  - `parse(firetv-cube3-pkg.txt, "com.nuvio.tv").filter == "speed"` and `parse(onn4kpro-pkg.txt, "com.nuvio.tv").filter == "speed"`, the fallback format. If a pkg file has no dexopt block, assert `null` instead, and say so in the report.
  - **Hardening:** a block where one ABI line has `status=kOatNeedsRecompilation` with no `compilation_filter` and a sibling ABI line has `speed` gives `null` (badge `UNKNOWN`). Any line in the block that mentions `status=` or `compilation_filter` but yields no filter makes the whole result `null`.
  - A CRLF version of the Onn sample parses the same.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*Dexopt*'`. Expected: the new cases FAIL, or pass where they already hold.
- [ ] **Step 3:** Implement the hardening rule.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with message `Verify status parser against device samples and harden it`.

### Task 8: Production helper (shell-uid process)

Design ref: "Built-in helper" and "Talking to it (revision 3)". This is the most security-sensitive code in the app, so review it with the most capable model and a security focus.

**Files:**
- Create: `app/src/main/java/io/github/devjardins/sidespeed/helper/HelperMain.kt` (`object HelperMain { @JvmStatic fun main(args: Array<String>) }`)
- Create (pure, unit-tested): `helper/HelperProtocol.kt`, `helper/ProcNetTcp.kt`, `helper/CommandAllowlist.kt`
- Tests: `app/src/test/.../helper/HelperProtocolTest.kt`, `ProcNetTcpTest.kt`, `CommandAllowlistTest.kt`

**Interfaces (produced):**
```kotlin
const val HELPER_PORT_FIRST = 47_310; const val HELPER_PORT_LAST = 47_319   // loopback only
const val HELPER_PROTOCOL_VERSION = 1
object HelperProtocol {
    // Request: one line "<verb> <arg?>", max 512 chars. Response: lines, then "__END__:<exit>".
    const val END = "__END__:"
    fun parseEnd(line: String): Int?          // exit code if line starts with END, else null
}
object ProcNetTcp {
    /** Returns the uid owning the socket whose local endpoint is 127.0.0.1:[localPort] (or ::ffff:127.0.0.1), from /proc/net/tcp + tcp6 text. */
    fun ownerUid(tcp: String, tcp6: String, localPort: Int): Int?
}
object CommandAllowlist {
    /** Maps a requested command line to a fixed argv, or null if not allowed. */
    fun toArgv(command: String): List<String>?
}
```
- **Allowed commands**, exact forms; `<pkg>` must pass `PackageName.parse`, and `<mode>` is `speed` or `speed-profile`. Everything else returns `null`.
  - `pm compile -m <mode> -f <pkg>`
  - `cmd package compile -m <mode> -f <pkg>`
  - `dumpsys package dexopt`
  - `dumpsys package <pkg>`
  - `ping`, which the helper answers itself as `pong v=<HELPER_PROTOCOL_VERSION> uid=<uid> apk=<path>`.
- **HelperMain behaviour:**
  - Bind a `ServerSocket` on `127.0.0.1`, trying the first free port in the range. Write the chosen port to `/data/local/tmp/sidespeed_helper.port`, which the app reads.
  - For each connection, read `/proc/net/tcp` and `/proc/net/tcp6` and find the uid that owns the client's socket. The client's local port is the server-side remote port. Accept only SideSpeed's uid, looked up at start the same way as the spike. Refuse otherwise and log it.
  - The caller check happens before any request byte is read.
  - Run allowed argv with `ProcessBuilder` (stderr merged) and stream the lines back, then send `__END__:<exit>`.
  - One request per connection, each on its own thread. A compile can take minutes, so there's no read timeout after the request line, and a 10 s timeout before it.
  - Exit when the APK path or its size/mtime changes (checked every 5 s), the same as the spike.
  - Log to `/data/local/tmp/sidespeed_helper.log` with a size cap: rotate at 256 KB.
  - Never write settings, and never run `sh -c`.
- **Launch command** (also used by Task 9 and shown on screen):
  `P=$(pm path io.github.devjardins.sidespeed | cut -d: -f2); CLASSPATH=$P setsid nohup app_process /system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.helper.HelperMain --apk=$P </dev/null >/dev/null 2>&1 &`
  Expose it as `object HelperLaunch { fun command(): String }` in `helper/HelperLaunch.kt`.
- **Release builds:** add `proguard-rules.pro` keep rules for `HelperMain.main`.

- [ ] **Step 1: Failing tests.**
  - `CommandAllowlistTest`:
    - Each allowed form gives the exact argv.
    - These return `null`: `pm compile -m speed -f com.foo;reboot`, `pm uninstall com.foo`, `dumpsys package com.foo bar`, `pm compile -m everything -f com.foo`, `""`, and `sh -c id`.
  - `ProcNetTcpTest`, with real-format lines (copy one line from a device `/proc/net/tcp` into the test):
    - An IPv4 entry `0100007F:B8AE` with uid `10255` gives `ownerUid(..., 0xB8AE) == 10255`.
    - The same entry in tcp6 as `0000000000000000FFFF00000100007F:B8AE` gives `10255`.
    - A missing port gives `null`.
    - Malformed lines are skipped without throwing.
  - `HelperProtocolTest`: `parseEnd("__END__:0") == 0`, `parseEnd("__END__:x") == null`, `parseEnd("Success") == null`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*helper*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the pure objects, then `HelperMain` and `HelperLaunch`.
- [ ] **Step 4:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5:** Commit with message `Add shell-uid helper with loopback TCP and caller check`.
- [ ] **Step 6 (controller): device check on both boxes.** Start the helper with the launch command.
  - From the shell, the helper must refuse uid 2000 and log the refusal.
  - From `run-as io.github.devjardins.sidespeed`, connect with `toybox nc 127.0.0.1 <port>` and send `ping`. Expect `pong`.

### Task 8b: App-side helper client and command routing

**Files:**
- Create: `adb/CommandRunner.kt`, `helper/HelperClient.kt`
- Modify: `adb/AdbBackend.kt` (extends `CommandRunner`), `compile/CompileRunner.kt` and `status/StatusChecker.kt` (take a `CommandRunner` instead of an `AdbBackend`), plus their tests (`FakeAdbBackend` already satisfies `CommandRunner`)
- Test: `app/src/test/.../helper/HelperClientTest.kt`

**Interfaces:**
```kotlin
interface CommandRunner { fun shell(command: String): Flow<ShellEvent> }   // AdbBackend : CommandRunner
class HelperClient(portFile: File = File("/data/local/tmp/sidespeed_helper.port"),
                   connect: (Int) -> Socket = { Socket("127.0.0.1", it) }) : CommandRunner {
    suspend fun ping(): HelperPing?              // null if not running / refused / version mismatch
    override fun shell(command: String): Flow<ShellEvent>  // Lines then Exit; AdbConnectionLost if the socket dies before __END__
}
data class HelperPing(val version: Int, val apkPath: String)
```
- Reads run on `Dispatchers.IO`. Cancelling closes the socket, using the same pattern as `LibAdbBackend.shell`.
- A version mismatch, or an `apkPath` that differs from `context.applicationInfo.sourceDir`, counts as "helper needs restart". The caller passes the expected path.

- [ ] **Step 1: Failing tests** with an in-process fake server: a `ServerSocket` on an ephemeral port, with the `connect` lambda injected.
  - `shell("dumpsys package dexopt")` gives `Line`s and then `Exit(0)`.
  - The server closing before `__END__` throws `AdbConnectionLost`.
  - `ping()` parses `pong v=1 uid=2000 apk=/x` into `HelperPing(1, "/x")`.
  - A missing port file makes `ping()` return `null`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*HelperClient*' --tests '*CompileRunner*' --tests '*StatusChecker*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the code, and switch the `CompileRunner` and `StatusChecker` constructor types.
- [ ] **Step 4:** Run all unit tests. Expected: PASS.
- [ ] **Step 5:** Commit with message `Add helper client and route compiles through CommandRunner`.

### Task 9: Connection strategy, prefs and setup screen (replaces the old Task 8)

**Files:**
- Create: `adb/ConnectionStrategy.kt`, `data/Prefs.kt`, `ui/setup/SetupScreen.kt`, `ui/setup/SetupViewModel.kt`, `ui/setup/PairingCodeGrid.kt`
- Modify: `AppContainer.kt`, `MainActivity.kt` (Setup is the start destination until the helper is ready, then Apps)
- Test: `app/src/test/.../adb/ConnectionStrategyTest.kt`

**Interfaces:**
```kotlin
sealed interface Readiness {
    data object HelperReady : Readiness                       // use HelperClient
    data object HelperNeedsRestart : Readiness                // running but stale (APK updated)
    data class NeedsHelperStart(val reason: String) : Readiness // show the start screen
    data object WaitingForAllowPrompt : Readiness             // own-ADB connect timed out
}
interface Prefs { val lanWarningAcked: Flow<Boolean>; suspend fun setLanWarningAcked(v: Boolean)
                  val pairedTls: Flow<Boolean>; suspend fun setPairedTls(v: Boolean) }
class DataStorePrefs(context: Context) : Prefs
class ConnectionStrategy(helper: HelperProbe, adb: AdbBackend, prefs: Prefs, expectedApk: String,
                         isFireOs: Boolean, timeoutMs: Long = 30_000) {
    suspend fun ensureReady(): Readiness
}
fun interface HelperProbe { suspend fun ping(): HelperPing? }   // HelperClient::ping
```
**Order in `ensureReady`:**
1. Ping the helper. If it's alive and matches the version and APK, return `HelperReady`. If it's alive but stale, return `HelperNeedsRestart`.
2. If `isFireOs`, return `NeedsHelperStart("Fire OS blocks on-box ADB")`. Never try own-ADB on Fire OS.
3. Otherwise try `Classic()`, then `WirelessTls` if `pairedTls`, each under `withTimeout(timeoutMs)`. A timeout returns `WaitingForAllowPrompt`.
4. If connected, run `HelperLaunch.command()` through `adb.shell`, then poll `ping()` for up to 5 s. If it's ready, return `HelperReady`. Otherwise return `NeedsHelperStart("Helper did not start")`.
5. If nothing connected, return `NeedsHelperStart("ADB not reachable")`.

- [ ] **Step 1: Failing tests** with fakes:
  - A live, matching helper gives `HelperReady`, and `adb` is never called.
  - A stale helper gives `HelperNeedsRestart`.
  - Fire OS with no helper gives `NeedsHelperStart`, and `adb.connectAttempts` is empty.
  - Not Fire OS: `Classic` succeeds, the launch command is sent (recorded in `commands`), and the next ping succeeds, giving `HelperReady`.
  - `Classic` hangs: with `timeoutMs = 1_000` in virtual time, the result is `WaitingForAllowPrompt`.
  - Nothing connects: `NeedsHelperStart`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*ConnectionStrategy*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the code.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Setup screen** (D-pad only, with focus starting on the main action):
  1. **Warning, acknowledged once:** "ADB debugging must stay on for SideSpeed to work. Anyone on your network could ask your TV for access; SideSpeed only uses it to compile apps. Turning ADB off, or restarting the TV, stops SideSpeed's helper until you start it again."
  2. Run `ensureReady()` and show the result.
  3. **Start-helper screen** (for `NeedsHelperStart` and `HelperNeedsRestart`):
     - **Fire OS:** turn on ADB debugging (with a button that opens developer settings), then from a PC run `adb connect <wifi-ip>:5555` and the launch command wrapped in `adb shell '...'`. Show the exact text. Add a "Phone app coming later" note.
     - **Google TV:** turn on Wireless debugging, then pair with `PairingCodeGrid` (code and port, then `adb.pair` on `127.0.0.1`, falling back to the Wi-Fi IP, then `setPairedTls(true)`). The PC command is the alternative.
     - A "Check again" button re-runs `ensureReady()`.
  4. `WaitingForAllowPrompt` shows: "Check the TV for an 'Allow debugging' prompt and choose Always allow."
- [ ] **Step 6:** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 7:** Commit with message `Add readiness strategy and guided setup`.

### Task 10: Apps and compile screens (replaces the old Task 9)

The old Task 9 text applies unchanged, with these differences:
- `CompileRunner` and `StatusChecker` are built on the `HelperClient`.
- `AppSource` is introduced here, and `InstalledApps` implements it.
- `InstalledApps.list()` runs on `Dispatchers.IO`.
- **Compile screen:** before starting, show "This can take several minutes for large apps (about 3 min on a Fire TV Cube)", and show an elapsed-time counter while running.
- **Connection lost:** if `Finished.message == "Connection lost"`, offer "Check helper", which goes back to Setup.

Tests: the old Task 9 tests, using `FakeAdbBackend` as the `CommandRunner`, plus `AppsViewModelTest` checking that `list()` is not called on the main dispatcher (inject a test dispatcher).

Commit with message `Add app picker and compile screens`.

### Task 11: Release build, spike removal, NOTICE and device verification (replaces the old Task 10)

The old Task 10 text applies unchanged, with these additions:
- Delete `HelperSpikeMain.kt` along with the spike activity.
- Add `NOTICE`, crediting Shizuku (Apache-2.0, © RikkaW) for the starter technique, plus libadb-android, Conscrypt and BouncyCastle. Don't use Shizuku's name or icons in the app UI.
- R8 keep rules for `io.github.devjardins.sidespeed.helper.HelperMain`. Confirm the release APK's helper starts with `app_process`.
- README: a "Requirements" section stating that ADB or Wireless debugging must stay on, that restarting the TV or turning ADB off stops the helper, and giving the PC command for Fire TV.
- **Device verification:**
  - **Fire TV:** start the helper from the PC.
  - **Onn:** pair or connect in-app, and the helper auto-starts.
  - **Both:** compile Nuvio, see "speed ✓", and `dumpsys` agrees.
  - **After an update:** the helper reports "needs restart".
