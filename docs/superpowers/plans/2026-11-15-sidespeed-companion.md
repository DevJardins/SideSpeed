# SideSpeed Companion (M2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build SideSpeed Companion, an Android phone app and a Windows/macOS/Linux desktop app from one Kotlin Multiplatform codebase. On the user's Trusted Networks only, it keeps SideSpeed's helper running on their Fire TVs, so no PC is needed after a reboot or update.

**Architecture:**
- A new `:shared` KMP module takes the pure code both apps need, moved out of `:app` (the helper launch command, the shell protocol types, theme tokens).
- `:companion` (KMP library) holds the common logic and Compose UI.
- Thin `:companion-android` and `:companion-desktop` app modules hold the platform pieces: background scheduling, network fingerprint, tray/autostart, notifications.
- The companion talks to Fire TVs with Kadb over `<ip>:5555`, using a fixed set of constant commands.

**Tech Stack:**
- Kotlin Multiplatform and Compose Multiplatform (Android and JVM desktop).
- Kadb `com.flyfishxu:kadb` (Apache-2.0).
- WorkManager (Android); Compose Desktop tray and `jpackage`.
- kotlinx-coroutines; JUnit/kotlin-test.

**Spec:** `docs/superpowers/specs/2026-11-15-sidespeed-companion-design.md`, which builds on `docs/superpowers/specs/2026-11-15-sidespeed-design.md`.

## Global Constraints

- GPLv3 project; every dependency GPL-compatible. Kadb is Apache-2.0.
- Companion Android app: application ID `io.github.devjardins.sidespeed.companion`, minSdk 26, targetSdk/compileSdk 36.
- **No location permission anywhere.** Trusted Networks are recognised by a fingerprint: gateway IP + subnet (address/prefix) + DNS servers, plus the gateway MAC on desktop where readable without prompts.
- The feature is called **"Trusted Network"** in all UI copy and docs, never "home network".
- The companion never connects, checks or scans unless the current fingerprint matches a Trusted Network the user chose, and never before first-run setup has chosen one.
- **Fixed command set.** The companion sends only these constants (from `:shared`), plus a pid validated as digits:
  - `getprop ro.serialno`
  - `pm path io.github.devjardins.sidespeed`
  - `pidof sidespeed_helper`
  - `cat /proc/<pid>/cmdline`
  - `kill $(pidof sidespeed_helper)`
  - `HelperLaunch.command()`
  - add/scan only: `getprop ro.product.manufacturer`, `getprop ro.product.model`, `getprop ro.build.version.name`
- No command is sent to a device whose `ro.serialno` doesn't match the saved serial (except during add, which records it).
- **Background behaviour:**
  - check every 15 min (option: 1 hour), plus immediately on app open, on "Restart now" and on joining a Trusted Network;
  - "Offline" after 3 consecutive failures, with no faster retry;
  - automatic re-find after 3 offline checks, at most once per hour per TV, on that TV's Trusted Network only, matched by serial.
- Notifications are off by default. Desktop "Start when I log in" is off by default.
- Calm theme tokens: exactly those in the TV design's Revision 4 table (`#10151E`, `#171E29`, `#1A222E`, `#222C38`, `#2A3644`, `#EEF1F5`, `#D9DEE6`, `#8C96A4`, sage `#93BFA2`, the ok/warn/danger pill colours). No pure white, no lime.
- Desktop installers: `.msi`, `.dmg` (Apple Silicon only), `.deb`, `.rpm`, unsigned in M2. Window about 420×640.
- `:app` (the TV app) behaviour is unchanged except the Fire OS nudge (Task 9). All its existing tests keep passing.
- **Repo hygiene:**
  - The folder is iCloud-synced. If a build fails on duplicate `" N."` files, `rm -rf` the build dirs (twice if "Directory not empty") and rerun.
  - Never run two Gradle invocations at once.
  - Run Gradle in the foreground under a timeout (`perl -e 'alarm 900; exec @ARGV' ./gradlew ...`).
- Commits: imperative, no attribution trailers, don't push.

## Review Focus

1. **Allow prompt ignored or denied.** If a TV never accepts the companion's key, background checks must not pop "Allow debugging?" on the TV every 15 minutes. After a pending-auth timeout, mark the TV "Waiting for you to allow" and skip it in background runs until the user opens the app or presses Restart now. Tested in Task 5.
2. **Two helper pids.** `pidof` can return `"123 456"` (two helpers). Each pid is validated, the stale check runs on each, and any stale pid leads to `KILL_COMMAND` and a relaunch. Tested in Task 3.
3. **No or odd network.** No active network, VPN up, or link properties missing: the fingerprint is `null`, which means untrusted, so nothing is sent. Tested in Task 4.
4. **TV asleep or Wi-Fi radio off.** The connect fails fast or times out, so the TV moves to Offline after 3, and there are no tight retries and no prompt spam. Tested in Task 5.
5. **Network changes mid-run.** The gate is rechecked right before any scan (re-find or Find TVs) and before each TV visit. If it no longer matches, the run stops. Tested in Task 5.

---

## File Structure

```
settings.gradle.kts                  # include :shared, :companion, :companion-android, :companion-desktop
shared/build.gradle.kts              # KMP: androidTarget + jvm
shared/src/commonMain/kotlin/io/github/devjardins/sidespeed/
  adb/ShellEvent.kt, adb/AdbConnectionLost.kt, adb/CommandRunner.kt, adb/ShellExit.kt, adb/ShellFlow.kt
  helper/HelperLaunch.kt, helper/HelperProtocol.kt
  theme/SsTokens.kt                  # colour values as Long ARGB + type sizes; no Compose dependency
shared/src/commonTest/...            # moved tests
companion/build.gradle.kts           # KMP library: androidTarget + jvm("desktop"), Compose Multiplatform
companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/
  tv/TvCommands.kt                   # the fixed constants
  tv/TvSession.kt                    # interface: one connection to a TV
  tv/KadbTvSession.kt                # Kadb implementation (common if Kadb common API suffices, else jvm/android actuals)
  tv/HelperKeeper.kt                 # the 6-step visit
  net/NetworkFingerprint.kt, net/FingerprintSource.kt, net/TrustedNetworks.kt
  store/CompanionStore.kt            # saved TVs, Trusted Networks, settings (interface + file/JSON impl)
  upkeep/Upkeep.kt, upkeep/Scanner.kt
  ui/...                             # Compose screens + view models
companion/src/commonTest/...
companion-android/                   # Android app module: Application, MainActivity, WorkManager worker, Android FingerprintSource, notifications
companion-desktop/                   # JVM app: main(), tray, window, timers, desktop FingerprintSource per OS, autostart, jpackage config
app/...                              # TV app: imports moved types from :shared; Fire OS nudge copy
.github/workflows/build.yml
```

---

### Task 1: `:shared` module and moving the shared code

**Files:**
- Create: `shared/build.gradle.kts`, the `shared/src/commonMain/...` files listed above, and `shared/src/commonTest/...` (moved tests)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml` (KMP plugin), `app/build.gradle.kts` (`implementation(project(":shared"))`), `app/.../ui/theme/Theme.kt` (`SsColors` reads its values from `SsTokens`)
- Move (`git mv`, keep the package names):
  - `ShellEvent` and `AdbConnectionLost` out of `adb/AdbBackend.kt` into their own files;
  - `adb/CommandRunner.kt`, `adb/ShellExit.kt`, `adb/ShellFlow.kt`;
  - `helper/HelperLaunch.kt`, `helper/HelperProtocol.kt`.
- Tests: move `ShellExitTest`, the syntax-check test and `HelperLaunchTest` into `:shared` tests. Keep tests that need Android/libadb in `:app`.

**Interfaces:**
- Produces, unchanged signatures and packages:
  - `io.github.devjardins.sidespeed.adb.{ShellEvent, AdbConnectionLost, CommandRunner, ShellExit, shellFlow}`
  - `io.github.devjardins.sidespeed.helper.{HelperLaunch, HelperProtocol}`
- Produces: `io.github.devjardins.sidespeed.theme.SsTokens` with `const val BACKGROUND = 0xFF10151E` (and so on for every token), plus `HEADLINE_SP = 28`, `TITLE_SP = 20`, `BODY_SP = 16`, `CAPTION_SP = 13`.
- `ConnectionStrategy.KILL_COMMAND` moves to `HelperLaunch.KILL_COMMAND` (keep a deprecated alias in `:app` only if needed to compile).

- [ ] **Step 1:** Create `:shared`, move the files and tests (`git mv` so history follows), and wire `:app` to depend on it. `shellFlow` uses `Dispatchers.IO`: if that isn't available in `commonMain` for these targets, put `ShellFlow.kt` in an intermediate JVM-family source set shared by `androidTarget` and `jvm`.
- [ ] **Step 2:** Run `./gradlew :shared:allTests :app:testDebugUnitTest :app:assembleRelease`. Expected: everything passes, and the total test count equals the previous 209 (spread across both modules).
- [ ] **Step 3:** Commit with message `Extract shared helper and shell code into a :shared KMP module`.

### Task 2: Companion module skeleton and the Kadb TV session

**Files:**
- Create: `companion/build.gradle.kts` (KMP library, Compose Multiplatform, Kadb dependency), `tv/TvSession.kt`, `tv/KadbTvSession.kt`, the companion key store (expect/actual or interface: Android `filesDir`, desktop `~/.sidespeed-companion/` resolved per OS app-data dir)
- Test: `companion/src/commonTest/.../tv/FakeTvSession.kt` (test helper)

**Interfaces:**
```kotlin
interface TvSession : AutoCloseable {
    /** Runs one shell command; returns trimmed stdout. Throws TvUnreachable / TvAuthPending / AdbConnectionLost. */
    suspend fun run(command: String): String
}
class TvUnreachable(cause: Throwable?) : Exception(cause)      // refused/no route/timeout before ADB handshake
class TvAuthPending : Exception()                               // handshake waiting on "Allow debugging?" past timeout
fun interface TvConnector { suspend fun open(address: String, port: Int = 5555): TvSession }
class KadbTvConnector(keyDir: Path /* or platform key store */, authTimeoutMs: Long = 20_000) : TvConnector
```
- `FakeTvSession`: a scripted map from command to output, records `sent: List<String>`, with `failOnOpen` / `authPending` switches.

- [ ] **Step 1:** Implement `KadbTvConnector`. The key is generated once and persisted, using Kadb's key/cert store API. Map Kadb failures as follows:
  - connect refused or timeout → `TvUnreachable`;
  - auth not granted within `authTimeoutMs` → `TvAuthPending`.
- [ ] **Step 2:** Write `FakeTvSession`.
- [ ] **Step 3:** Run `./gradlew :companion:allTests :companion:compileKotlinDesktop`. Expected: builds.
- [ ] **Step 4:** Commit with message `Add companion module and Kadb TV session`.
- [ ] **Step 5 (controller): device check.** From the Mac (desktop target, small test main or unit test tagged manual), connect to the Fire TV Cube `192.168.5.140:5555` and run `getprop ro.serialno`. Accept "Always allow" (the owner does this on the TV). Expected: a serial comes back, and the second run is silent.

### Task 3: HelperKeeper, the six-step visit

**Files:**
- Create: `tv/TvCommands.kt`, `tv/HelperKeeper.kt`
- Test: `companion/src/commonTest/.../tv/HelperKeeperTest.kt`

**Interfaces:**
```kotlin
object TvCommands {
    const val SERIAL = "getprop ro.serialno"
    const val PKG_PATH = "pm path io.github.devjardins.sidespeed"
    const val PIDOF = "pidof sidespeed_helper"
    fun cmdline(pid: Int): String                 // "cat /proc/$pid/cmdline"; pid > 0 enforced
    val KILL = HelperLaunch.KILL_COMMAND
    val LAUNCH = HelperLaunch.command()
    const val MANUFACTURER = "getprop ro.product.manufacturer"
    const val MODEL = "getprop ro.product.model"
    const val OS_VERSION = "getprop ro.build.version.name"
}
sealed interface VisitResult {
    data object Running : VisitResult                 // helper current, nothing done
    data object Started : VisitResult                 // was missing, now running
    data object Restarted : VisitResult               // was stale, killed and relaunched
    data object NotInstalled : VisitResult
    data object WrongDevice : VisitResult             // serial mismatch, nothing else sent
    data class StartFailed(val detail: String) : VisitResult
}
class HelperKeeper(private val pollIntervalMs: Long = 500, private val pollTimeoutMs: Long = 5_000) {
    suspend fun visit(session: TvSession, expectedSerial: String): VisitResult
}
```

**Visit algorithm (spec order):**
1. `SERIAL`; if it differs from `expectedSerial`, return `WrongDevice`.
2. `PKG_PATH`; if empty, return `NotInstalled`. The APK path is the text after `package:`.
3. `PIDOF`; parse whitespace-separated tokens, each must be all digits, otherwise treat as none.
4. For each pid, `cmdline(pid)`. The output is NUL-separated, so split on `\u0000`. Find `--apk=`. Stale if any pid's value differs from the APK path, or no `--apk=` is found.
5. Stale → `KILL`, wait 500 ms.
6. Not running or stale → `LAUNCH`, then poll `PIDOF` until non-empty or the timeout passes.

- [ ] **Step 1: Failing tests** with `FakeTvSession`:
  - Serial mismatch → `WrongDevice`, and `sent == [SERIAL]`.
  - Empty `pm path` → `NotInstalled`, `sent == [SERIAL, PKG_PATH]`.
  - Running and current (`pidof` "123", cmdline contains `--apk=/data/app/x/base.apk` matching `package:/data/app/x/base.apk`) → `Running`, with no KILL or LAUNCH sent.
  - Not running → `Started`; `sent` contains LAUNCH once, followed by PIDOF polls.
  - Stale (`--apk=/data/app/OLD/base.apk`) → `Restarted`; KILL comes before LAUNCH.
  - Two pids `"123 456"`, one stale → `Restarted`; cmdline is read for both.
  - `pidof` returns garbage `"12a"` → treated as not running. `cmdline` is never called with a non-digit pid, and `cmdline(0)` throws.
  - Launch never appears within the timeout → `StartFailed`.
  - Every command in `sent` across all tests is one of the `TvCommands` constants or matches `cat /proc/\d+/cmdline`.
- [ ] **Step 2:** Run `./gradlew :companion:allTests --tests '*HelperKeeper*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with message `Add HelperKeeper fixed-command visit`.

### Task 4: Trusted Networks, fingerprint and matching

**Files:**
- Create: `net/NetworkFingerprint.kt`, `net/FingerprintSource.kt`, `net/TrustedNetworks.kt`
- Test: `companion/src/commonTest/.../net/TrustedNetworksTest.kt`

**Interfaces:**
```kotlin
data class NetworkFingerprint(
    val gatewayIp: String,
    val subnet: String,              // "192.168.5.0/24" normalised
    val dnsServers: Set<String>,
    val gatewayMac: String? = null,  // desktop only, when readable
)
fun interface FingerprintSource { suspend fun current(): NetworkFingerprint? }   // null = no usable network
data class TrustedNetwork(val id: String, val name: String, val fingerprint: NetworkFingerprint)
object TrustMatcher {
    /** Same gateway IP and subnet, and DNS sets intersect (or both empty); if both have gatewayMac they must be equal. */
    fun matches(current: NetworkFingerprint, trusted: NetworkFingerprint): Boolean
    fun currentTrusted(current: NetworkFingerprint?, all: List<TrustedNetwork>): TrustedNetwork?
}
```

- [ ] **Step 1: Failing tests:**
  - An identical fingerprint matches.
  - A different gateway IP → no match.
  - A different subnet → no match.
  - Same gateway and subnet, DNS sets disjoint → no match.
  - Same gateway and subnet, DNS changed from {a,b} to {b,c} (they intersect) → match.
  - Both MACs present and different → no match. One missing → MAC ignored.
  - `currentTrusted(null, list) == null` (Review Focus 3).
  - Subnet normalisation: `192.168.5.140/24` becomes `192.168.5.0/24`.
- [ ] **Step 2:** Run `./gradlew :companion:allTests --tests '*TrustedNetworks*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with message `Add Trusted Network fingerprint matching`.

### Task 5: Store, upkeep loop and scanner

**Files:**
- Create: `store/CompanionStore.kt` (interface + JSON file implementation using kotlinx.serialization if already available, otherwise a small hand-written encoder), `upkeep/Upkeep.kt`, `upkeep/Scanner.kt`
- Test: `companion/src/commonTest/.../upkeep/UpkeepTest.kt`, `ScannerTest.kt`

**Interfaces:**
```kotlin
enum class TvStatus { RUNNING, NOT_RUNNING, OFFLINE, WAITING_FOR_ALLOW, NOT_INSTALLED, WRONG_DEVICE, START_FAILED, UNKNOWN }
data class SavedTv(val id: String, val name: String, val address: String, val serial: String,
                   val trustedNetworkId: String, val status: TvStatus = TvStatus.UNKNOWN,
                   val lastCheckedEpochMs: Long? = null, val offlineCount: Int = 0,
                   val lastRefindEpochMs: Long? = null, val skipBackgroundUntilUserAction: Boolean = false)
data class CompanionSettings(val notifications: Boolean = false, val intervalMinutes: Int = 15, val startAtLogin: Boolean = false)
interface CompanionStore {
    suspend fun tvs(): List<SavedTv>; suspend fun saveTv(tv: SavedTv); suspend fun removeTv(id: String)
    suspend fun trustedNetworks(): List<TrustedNetwork>; suspend fun saveTrustedNetwork(n: TrustedNetwork); suspend fun removeTrustedNetwork(id: String)
    suspend fun settings(): CompanionSettings; suspend fun saveSettings(s: CompanionSettings)
}
enum class Trigger { BACKGROUND, APP_OPEN, RESTART_NOW, NETWORK_JOINED }
data class UpkeepReport(val gated: Boolean, val visited: Map<String, TvStatus>, val restartedNames: List<String>)
class Upkeep(store: CompanionStore, fingerprints: FingerprintSource, connector: TvConnector,
             keeper: HelperKeeper, scanner: Scanner, clock: () -> Long) {
    suspend fun runOnce(trigger: Trigger): UpkeepReport
}
interface Scanner { suspend fun scan(subnet: String, port: Int = 5555): List<String> }   // addresses answering on port
class TcpScanner(connectTimeoutMs: Int = 300, parallelism: Int = 64) : Scanner           // /24 only, else returns empty
```

**`runOnce` rules:**
- Fingerprint → `currentTrusted`. If null, return `gated = true` and touch nothing.
- Visit only the TVs whose `trustedNetworkId` matches. Recheck the gate before each visit and before any scan (Review Focus 5).
- Open a session, then `keeper.visit`:
  - **Unreachable:** `offlineCount++`; at 3, status OFFLINE. At `offlineCount >= 3` and `now - lastRefind >= 1h`, scan, connect to each answering host, read `SERIAL`, and on a match update the address and visit.
  - **AuthPending:** WAITING_FOR_ALLOW, with `skipBackgroundUntilUserAction = true`. BACKGROUND and NETWORK_JOINED skip such TVs; APP_OPEN and RESTART_NOW clear the flag and try (Review Focus 1).
  - **Success:** reset `offlineCount` and set the status from the result. Started or Restarted adds the name to `restartedNames`.

- [ ] **Step 1: Failing tests** with fakes (store in memory, fixed fingerprint, `FakeTvSession` connector, fake scanner, fake clock):
  - An untrusted or null fingerprint → `gated`, connector never called, scanner never called.
  - No Trusted Network chosen yet → `gated`.
  - A TV on another Trusted Network isn't visited.
  - Missing helper → status RUNNING after Started, and its name appears in `restartedNames`.
  - Three unreachable runs → OFFLINE on the 3rd, then a re-find scan once. The scan finds `.77` with a matching serial → the address is updated to `.77`, then visited. A 4th run within the hour doesn't scan again.
  - Re-find skips a host whose serial differs, and sends nothing beyond `SERIAL` to it.
  - AuthPending on BACKGROUND → WAITING_FOR_ALLOW. The next BACKGROUND run doesn't open a session for that TV; APP_OPEN does.
  - The fingerprint changes to untrusted between two TVs → the second TV isn't visited (the fake source flips after the first `current()` call).
  - `TcpScanner.scan("10.0.0.0/16")` returns empty (/24 only). `ScannerTest` uses a local `ServerSocket` on 127.0.0.1 with `scan("127.0.0.0/24", port)` and expects it to include `127.0.0.1`.
- [ ] **Step 2:** Run `./gradlew :companion:allTests --tests '*Upkeep*' --tests '*Scanner*'`. Expected: FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5:** Commit with message `Add companion store, upkeep loop and scanner`.

### Task 6: Companion UI (common Compose)

**Files:**
- Create: `companion/src/commonMain/.../ui/{Theme.kt, FirstRunScreen.kt, HomeScreen.kt, AddTvScreen.kt, SettingsScreen.kt, CompanionViewModel.kt, Navigation.kt}`
- Test: `companion/src/commonTest/.../ui/CompanionViewModelTest.kt`

**Interfaces:**
```kotlin
class CompanionViewModel(store: CompanionStore, fingerprints: FingerprintSource, upkeep: Upkeep,
                         connector: TvConnector, scanner: Scanner, scope: CoroutineScope) {
    val state: StateFlow<CompanionUiState>
    fun onOpen()                                    // upkeep.runOnce(APP_OPEN)
    fun trustCurrentNetwork(name: String)           // explicit user action only
    fun removeTrustedNetwork(id: String)
    fun addTvByAddress(address: String)             // connect → identity getprops → save → upkeep
    fun findTvs()                                   // only when current network trusted; else error state
    fun restartNow(tvId: String? = null)
    fun updateSettings(s: CompanionSettings)
}
```

**UI copy (exact):**
- First run: title "Choose your Trusted Network"; body "SideSpeed Companion only works on networks you choose. It never connects or scans anywhere else."; current-network line "Router <gatewayIp> · <subnet>"; buttons "Trust this network" and "Not now".
- Home banner: "Trusted Network: <name> ✓" or "Not on a Trusted Network".
- TV status labels: "Helper running" / "Not running" / "Offline" / "Waiting for you to allow" / "SideSpeed isn't installed" / "Different device at this address" / "Couldn't start the helper". Plus "Checked N min ago".
- Add a TV: an address field with placeholder "192.168.1.50", "Find TVs", and the step "Tick Always allow on your TV".
- Settings: "Trusted Networks", "Add as Trusted Network", "Notifications", "Check every: 15 minutes / 1 hour", desktop-only "Start when I log in", "About and licences".
- Theme: `SsTokens` colours, focus and selection in sage, phone type scale 24/18/15/13 sp.

- [ ] **Step 1: Failing tests:**
  - No Trusted Network → state is FirstRun, and `onOpen()` doesn't call `upkeep`.
  - `trustCurrentNetwork("Living room")` saves the current fingerprint under that name.
  - `findTvs()` on an untrusted network → error "Join a Trusted Network to find TVs", and the scanner isn't called.
  - `addTvByAddress` saves a `SavedTv` with the serial from `SERIAL` and the name from `MODEL`, then runs upkeep with `APP_OPEN`.
  - `restartNow` maps to `Trigger.RESTART_NOW`.
  - Adding a TV whose `MANUFACTURER` isn't `Amazon` still saves it, but its card shows "Not a Fire TV: SideSpeed on this TV handles setup itself, and the companion can't reach it after restarts." This is the spec's Google TV note.
- [ ] **Step 2:** Run `./gradlew :companion:allTests --tests '*CompanionViewModel*'`. Expected: FAIL.
- [ ] **Step 3:** Implement the view model and screens.
- [ ] **Step 4:** Run `./gradlew :companion:allTests :companion:compileKotlinDesktop`. Expected: PASS and builds.
- [ ] **Step 5:** Commit with message `Add companion screens with calm theme`.

### Task 7: Android companion app

**Files:**
- Create: `companion-android/build.gradle.kts` (com.android.application, applicationId and SDKs per Global Constraints), `AndroidManifest.xml` (INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS; **no location**), `CompanionApp.kt`, `MainActivity.kt`, `UpkeepWorker.kt`, `AndroidFingerprintSource.kt`, `Notifier.kt`, launcher icon from the logo robot
- Test: `companion-android/src/test/.../AndroidFingerprintSourceTest.kt` (a pure mapping function from LinkProperties-like data to `NetworkFingerprint`)

**Behaviour:**
- `AndroidFingerprintSource`:
  - `getLinkProperties(activeNetwork)`;
  - gateway = the default route's gateway (IPv4 preferred);
  - subnet from the IPv4 `LinkAddress` (normalised);
  - DNS from `dnsServers`;
  - null if there's no active network or no IPv4 default route.
- `UpkeepWorker` (CoroutineWorker):
  - periodic, using `intervalMinutes` (15 or 60), constraint `NetworkType.CONNECTED`, unique work name `sidespeed-upkeep`, re-enqueued with `REPLACE` when the interval changes;
  - runs `upkeep.runOnce(BACKGROUND)`;
  - posts "SideSpeed restarted on <name>" per restarted TV if notifications are on.
- On app open: an expedited one-off `APP_OPEN` run.
- A `ConnectivityManager.NetworkCallback` registered in `CompanionApp` triggers `NETWORK_JOINED` when the fingerprint changes and is trusted.
- POST_NOTIFICATIONS is requested only when the user turns notifications on (Android 13+).

- [ ] **Step 1:** Write the failing mapping test: given a gateway `192.168.5.1`, link address `192.168.5.23/24` and DNS `[192.168.5.1]`, expect `NetworkFingerprint("192.168.5.1", "192.168.5.0/24", setOf("192.168.5.1"))`. No IPv4 default route → null.
- [ ] **Step 2:** Implement until it passes. Then run `./gradlew :companion-android:testDebugUnitTest :companion-android:assembleDebug`. Expected: PASS and APK built.
- [ ] **Step 3:** Check `aapt2 dump permissions` on the APK. Expected: no location permission listed.
- [ ] **Step 4:** Commit with message `Add Android companion app with WorkManager upkeep`.

### Task 8: Desktop companion app

**Files:**
- Create: `companion-desktop/build.gradle.kts` (Compose Desktop application, `nativeDistributions`: Msi, Dmg, Deb, Rpm; packageName "SideSpeed Companion"; the macOS bundle ID matches the app ID), `Main.kt`, `Tray.kt`, `DesktopFingerprintSource.kt` (with `MacFingerprint`, `WindowsFingerprint`, `LinuxFingerprint` behind it), `Autostart.kt` (per-OS), `DesktopNotifier.kt`, the tray icon (robot from the logo)
- Test: `companion-desktop/src/test/.../FingerprintParsersTest.kt` (pure parsers for captured command outputs)

**Behaviour:**
- **Window:** 420×640 with the calm theme. Closing the window keeps the app in the tray.
- **Tray menu:** "Restart now", "Open SideSpeed Companion", "Pause checks" / "Resume checks", "Quit".
- **Timers:** upkeep every `intervalMinutes`, plus a 30 s fingerprint poll that triggers `NETWORK_JOINED` on a change to a trusted network.
- **Fingerprint sources:**
  - macOS: `route -n get default` (gateway), `ipconfig getifaddr` / `ifconfig` (address/mask), `scutil --dns` (DNS), `arp -n <gw>` (MAC).
  - Windows: `route print -4` or PowerShell `Get-NetRoute`, `Get-NetIPAddress`, `Get-DnsClientServerAddress`, `arp -a <gw>`.
  - Linux: `ip route show default`, `ip -4 addr`, `/etc/resolv.conf` or `resolvectl dns`, `ip neigh show <gw>`.
  - All parsing lives in pure functions.
- **Autostart:** off by default. Windows uses a Startup-folder `.lnk` or the HKCU Run key; macOS a `~/Library/LaunchAgents/io.github.devjardins.sidespeed.companion.plist`; Linux `~/.config/autostart/sidespeed-companion.desktop`.

- [ ] **Step 1:** Write the failing parser tests from captured sample outputs, one per OS command, written into the test as strings (the builder captures the macOS ones live on this Mac). Each must produce the expected `NetworkFingerprint`. Missing default route → null.
- [ ] **Step 2:** Implement until they pass. Then run `./gradlew :companion-desktop:test :companion-desktop:packageDmg`. Expected: PASS, and a `.dmg` built (on macOS).
- [ ] **Step 3 (controller):** Launch the packaged app on this Mac. Expected: the tray icon appears and the window opens with the First run screen.
- [ ] **Step 4:** Commit with message `Add desktop companion with tray, timers and autostart`.

### Task 9: TV app Fire OS nudge

**Files:** Modify `app/.../ui/setup/SetupScreen.kt` (Fire OS step 2) and its view-model or copy constants, plus tests.

**Copy (exact):**
- step 2 title: "Start the helper from your phone or computer";
- body: "Open SideSpeed Companion on your phone or computer. It starts the helper for you. Get it from github.com/DevJardins/SideSpeed/releases";
- the PC commands move under the existing "Other ways to run it" toggle.

- [ ] **Step 1:** Write a failing test. If copy is in constants: assert the Fire OS step 2 title and body constants equal the exact strings, and that the default (collapsed) state doesn't include the PC commands.
- [ ] **Step 2:** Implement until it passes. Then run `./gradlew :app:testDebugUnitTest :app:assembleRelease`. Expected: PASS.
- [ ] **Step 3:** Commit with message `Point Fire TV setup to SideSpeed Companion`.

### Task 10: CI, README, NOTICE

**Files:**
- Create: `.github/workflows/build.yml`
- Modify: `README.md` (a "SideSpeed Companion" section), `NOTICE` (Kadb Apache-2.0, Compose Multiplatform Apache-2.0)

**Workflow:**
- On push and pull request, a matrix of `ubuntu-latest` / `macos-latest` / `windows-latest`, JDK 21.
- ubuntu: `:shared:allTests :companion:allTests :app:testDebugUnitTest :companion-android:assembleRelease :app:assembleRelease :companion-desktop:packageDeb :companion-desktop:packageRpm`.
- macos: `:companion-desktop:packageDmg`.
- windows: `:companion-desktop:packageMsi`.
- Upload artefacts. No signing secrets yet.

**README:** what the companion is for (Fire TV, no PC), install per OS including the unsigned-installer warning and how to open it anyway, Trusted Networks explained (chosen by you, nothing happens elsewhere, no location permission), and the "Always allow" step.

- [ ] **Step 1:** Write the workflow, README and NOTICE.
- [ ] **Step 2:** Run the ubuntu job's Gradle task list locally on macOS (the Deb/Rpm packaging tasks may be skipped locally). Expected: PASS.
- [ ] **Step 3:** Commit with message `Add CI matrix and companion docs`.

### Task 11: Device verification (controller and owner)

- [ ] **Phone + Fire TV Cube:**
  - install the companion APK on the owner's phone;
  - first run, trust the network, add `192.168.5.140`, accept Always allow on the TV;
  - expect the helper to start;
  - reboot the TV, open the companion: the helper starts immediately;
  - reboot again without opening it: the helper is back within 15 min.
- [ ] **Mac + Cube:** the same with the desktop app; tray "Restart now" works.
- [ ] **Untrusted network:** with the phone on a hotspot, open the companion. Expect "Not on a Trusted Network" and no connection attempts (the TV shows no prompt; the logs show no connect).
- [ ] **Stale helper:** update the SideSpeed TV app (adb install -r), then open the companion. Expect "Restarted".
- [ ] Record the results in `docs/spike/M2-results.md`, then commit with message `Record companion device verification`.
