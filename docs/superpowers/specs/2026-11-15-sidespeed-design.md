# SideSpeed — Design

Date: 2026-11-15
Status: approved by owner 2026-11-15; revision 2 (built-in helper + phone companion) approved 2026-11-15; revision 3 (owner decision: no automatic ADB off/on; helper transport changed after M0b) 2026-11-15
Source spec: [`SPEC.md`](../../../SPEC.md) (repo root). This document builds on it and wins where they differ.

## Revision 3 summary (2026-11-15)

- **Removed by owner decision:** every feature that switches ADB or Wireless Debugging off or on automatically. That means "ADB only while working", "Keep ADB on", the automatic re-enable in "Survive reboots", and the helper's settings requests. Reason: the M0b test showed that switching ADB off on the Fire TV Cube left it off and needed manual recovery, and making this reliable across devices would cost far more than it's worth. SideSpeed's job is compiling sideloaded apps.
- **Instead:** the README, setup and Settings state plainly that ADB debugging (or Wireless Debugging) must stay on for SideSpeed to work, alongside the LAN-exposure caution.
- **Helper transport changed:** SELinux on Fire OS denied the app (`untrusted_app`) connecting to the helper's abstract Unix socket (`connectto` on a `shell` socket). A loopback TCP connection from the app to a shell-owned listener worked. See "Talking to it".
- Sections below that describe the removed features are kept, struck out under "Removed in revision 3", so the history is visible. Builders must not implement them.

## Intent and the why

Apps installed from the Play Store come with Baseline Profiles, so ART compiles their hot code ahead of time. Sideloaded APKs, such as the Nuvio GitHub build (`com.nuvio.tv`), start out interpreted or JIT-compiled and feel slow on low-power TV hardware. Running `pm compile -m speed -f <pkg>` over ADB fixes that, but it needs a PC or phone, and it has to be run again after every app update.

SideSpeed is a sideloadable Android TV / Fire TV app that runs that compile on the box itself. It works by acting as an ADB client to its own device, which gives it the shell access a normal app doesn't have. It also re-runs the compile automatically after updates.

**Audience:** anyone with an Android TV box or Google TV / Fire TV device who sideloads apps. It's a public GPLv3 project ([DevJardins/SideSpeed](https://github.com/DevJardins/SideSpeed)), not a personal tool. A non-technical user has to be able to get through first-time setup from the TV with only a remote.

## Goals

1. Works on any Android TV / Google TV / Fire TV device running Android 7.0+ (minSdk 24).
2. Supports both ways a device exposes ADB:
   - **Classic network ADB** on `127.0.0.1:5555` (Fire TV, most AOSP boxes, older Android TV).
   - **Wireless Debugging** (Android 11+): pairing code, then a TLS connection on a dynamic port. Needed for newer Google TV devices that don't listen on 5555.
3. Everything in SPEC.md's feature list: app picker, compile, status check, auto-recompile, settings.
4. Fully usable with a D-pad, with clear focus states. Shows up on the TV home screen.
5. Guided first-time setup: detect which ADB path the device supports and walk the user through turning it on.

## Non-goals

- Root-based compiling. A root backend might come later, but not now.
- Phone or tablet UI. It may run there, but there's no layout work for it.
- Distributing through the Play Store. Distribution is by sideload only: GitHub Releases APK.
- Depending on the separate Shizuku app. SideSpeed adapts Shizuku's starter technique into its own built-in helper instead (see "Built-in helper").
- Compiling anything other than user-chosen packages. No "compile everything" button.

## Anti-goals (it fails even if it technically works)

- **It silently leaves ADB exposed without telling the user.** The LAN-exposure warning must appear in setup and stay visible in Settings.
- **It breaks or slows the target app.** Errors from `pm compile` must be shown verbatim, never swallowed. It never runs destructive package commands: only `compile`, `dumpsys`, and read-only queries. The one exception is reopening the 5555 port; see Connection.
- **Setup can't be done from the couch.** Any step that requires a PC is a failure for the main flows where the device allows it. Typing a pairing code and port with a remote is acceptable. Exception, forced by Amazon: on current Fire OS a PC or the phone companion is needed once per boot to start the helper.
- **The ADB key is lost and the user has to re-approve the "Allow USB debugging" prompt repeatedly.**

## Constraints

- Kotlin, minSdk 24, targetSdk = current stable (36 at time of writing). Gradle Kotlin DSL, version catalog.
- Jetpack Compose for TV (`androidx.tv:tv-material`). Leanback launcher intent and a 320×180 banner.
- License: GPLv3. Every dependency must be GPLv3-compatible (Apache-2.0, MIT, BSD, LGPL, GPL).
- `QUERY_ALL_PACKAGES` and `INTERNET` permissions are required. That's fine for a sideloaded app.
- The release APK is signed with a keystore that is never committed. Signing config is read from `keystore.properties` or environment variables, both already in `.gitignore`.
- Test devices the owner has on hand:
  - **Fire TV Cube Gen 3:** Fire OS 7.7.1.7 (Android 9). M0 showed on-box ADB is blocked here; uses the helper started from a PC or phone.
  - **Onn 4K Pro (2026):** Google TV on Android 14, most likely the Wireless Debugging path.

  Anything else is built from documentation and should be labelled "should work" in the README.
- **Device testing:** the owner installs debug builds on both boxes and sends back logcat and command output, using the exact commands the builder provides. The Onn is on Wi-Fi but needs to be set up again before testing, so plan an explicit hand-off point for it.

## Approaches

Three ways to give the app ADB access were considered:

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | A built-in ADB client library that handles classic 5555, Wireless Debugging pairing and the TLS connection. Hidden behind a `AdbBackend` interface. | One code path covers both test devices from the first release. The library APIs are rougher than dadb's, and TLS on older Android needs bundled Conscrypt/BouncyCastle (a few MB). |
| B | dadb for 5555, plus pairing (SPAKE2) and TLS written ourselves. | dadb has a nicer API, but we'd hand-roll the most complicated, most security-sensitive part. More work, more risk, no benefit to users. |
| C | Shizuku only. | Least code for us. But it's an extra app to install, its UI is built for phones, and it has to be restarted after every reboot, which breaks background auto-recompile. Kept as a possible second backend later. |

**The owner picked A.**

**Revision 2 (2026-11-15, after the Fire TV M0 test).** On the Fire TV Cube (Fire OS 7.7.1.7), adbd answers connections from the Mac but sends nothing to connections that start on the box itself, on both `127.0.0.1` and its own LAN IP. This is Amazon's deliberate block on local ADB, present since Fire OS 7.6.6.9 / 8.1.0.3 (CVE-2024-27350). Approach A alone therefore can't work on current Fire TVs. Google is also discussing a similar loopback block for Android.

Options considered for this:
- **Our own built-in helper, adapting Shizuku's starter technique (chosen by the owner).** One app; a one-line start after each reboot on Fire TV.
- Depending on the separate Shizuku app. Less code for us, but a second app with a phone-style UI.
- Dropping Fire TV from the on-box flow. Fails the "any Android TV box" goal.

A stays: SideSpeed still uses its own ADB connection where the device allows it (Google TV / Wireless Debugging), and uses it to start the helper itself.

**Candidate libraries.** Picking one is the builder's job, using the criteria below. The two leads:
- [libadb-android](https://github.com/MuntashirAkon/libadb-android), used by App Manager. It supports classic, pairing and TLS. Its license is in a COPYING file; verify it's GPL-compatible.
- Kadb, a dadb-derived Kotlin library that adds pairing and TLS. Listed minSdk is 23. Android 6–8 need Conscrypt for pairing.

**Criteria:**
- GPL-compatible license.
- Works at minSdk 24.
- Supports pairing, the TLS connection, a persisted key, and shell plus `tcpip:` services.
- Actively maintained.

If neither works, fall back to dadb for classic plus a ported pairing implementation, and flag that to the owner.

## Design

### Connection layer

- `AdbBackend` interface:
  - `connect()`
  - `pair(host, port, code)`
  - `shell(cmd): Flow<String>` (streamed output plus exit status)
  - `isConnected`
  - `close()`

  There is one implementation now (`LibAdbBackend`, named after the chosen library). The interface leaves room for Shizuku later.
- **Key storage:** the RSA keypair (and the TLS certificate, if separate) is generated once and stored in app-private storage (`filesDir`). It is never backed up: `android:allowBackup="false"`, or exclude the file. So after the first "Always allow", later connections are silent.
- **Connection strategy**, tried in order each time:
  1. `127.0.0.1:5555` (classic).
  2. If Wireless Debugging was previously paired: use mDNS (`NsdManager`, `_adb-tls-connect._tcp`) to find the current TLS port on the loopback/own IP, then connect with TLS.
  3. Otherwise report "not set up" and send the user to Setup.
- **Make it last after reboot (Wireless Debugging devices):** after a successful TLS connection, the app may offer an opt-in setting that sends the `tcpip:5555` service. That reopens classic 5555 until the next reboot, so background jobs can connect without mDNS. It's off by default, and its label says it widens exposure in the same way as the classic path. Test whether this works and helps on the Onn 4K Pro before shipping it.
- **Known risk:** on some Android versions Wireless Debugging turns itself off after a reboot or a network change. When it's off, auto-recompile can't run. In that case the app notes it ("Couldn't reach ADB — reopen Wireless Debugging") in the status screen and in a notification. It does not fail silently. The "Survive reboots" option below exists to fix this.

### Built-in helper (shell-uid process, revision 2)

**What it is.** A small Java/Kotlin entry point inside SideSpeed's own APK, launched with `app_process` from an ADB shell so it runs as the shell user (uid 2000). As that user it runs `pm compile`, `cmd package compile` and `dumpsys` directly. SideSpeed talks to it locally, with no ADB connection needed while it runs. It is adapted from Shizuku's starter technique (Apache-2.0). Credit Shizuku in a `NOTICE` file, and never use the Shizuku name, its app id `moe.shizuku.privileged.api` or its icons, which its license forbids.

**Launch.** A start script is written by SideSpeed to a location the shell user can read (for example `/sdcard/Android/data/io.github.devjardins.sidespeed/files/start.sh`, or the exact command shown on screen). It runs:
`CLASSPATH=<SideSpeed APK path> app_process /system/bin --nice-name=sidespeed_helper io.github.devjardins.sidespeed.helper.HelperMain &`
Backgrounded and detached (`setsid`/`nohup` or equivalent) so it survives the ADB shell exiting. Because the APK path changes when SideSpeed is updated, the helper exits when its APK is replaced, and SideSpeed shows "Restart the helper".

**Who starts it:**
- **Google TV / Wireless Debugging devices:** SideSpeed itself, over its own ADB connection (Approach A), right after connecting. No PC.
- **Fire TV and any device where on-box ADB is blocked:** a PC (`adb shell sh <path>/start.sh`, shown on screen) or the phone companion (milestone M4), once per boot.

**Talking to it (revision 3).** The helper listens on loopback TCP (`127.0.0.1`, a fixed port chosen by the builder, with a fallback range), never on other interfaces. The abstract Unix socket from revision 2 is blocked by SELinux for normal apps (M0b, Fire OS 7.7).

Because TCP has no `SO_PEERCRED`, the helper must confirm the caller is SideSpeed on every connection. The builder picks one of these, verifies it on both test devices, and notes the choice:
- **(a)** Look up the connecting socket's owner uid in `/proc/net/tcp` / `/proc/net/tcp6`, matching on the client's port; the shell user can read these. Accept only SideSpeed's uid.
- **(b)** A shared secret written by the helper at start to a place only SideSpeed can read, presented by the app on each connection.
- **(c)** Shizuku-style Binder hand-off, if (a) and (b) both fail.

Anything else is refused and logged.

**What it accepts.** A fixed set of requests, not arbitrary shell:
- compile `<package>` `<mode>`, with the package validated by the same package-name rule as the app;
- `dumpsys package dexopt` / `dumpsys package <package>`;
- `ping` and `version`, so SideSpeed can see the helper is alive and matches its APK.

Output streams back line by line with the exit code.

**Backend.** The helper is a second implementation of the command layer, beside `LibAdbBackend`. CompileRunner and StatusChecker keep working through the same interface. The connection strategy becomes:
1. Helper alive → use it.
2. Else Approach A (classic, then TLS); if connected, start the helper through it, then use the helper.
3. Else show the "Start SideSpeed's helper" screen: PC command or phone companion.

**Lifetime.** The helper ends at reboot and leaves nothing installed. ADB debugging stays on, as revision 3 requires. The helper does not switch ADB off or on.

**Security.** The helper is the one shell-uid component. It must refuse other uids, accept only the fixed request set, never pass input to a shell unvalidated, and log refusals. A reviewer with security focus checks it before it ships.

### Phone companion app (milestone M4, owner-approved, later stage)

A separate small Android phone app, in this repo as a second module, sharing the ADB code. Its only job is starting SideSpeed's helper on the TV for users who don't want to use a PC.
- **Setup, once:** finds the TV on the local network and connects over ADB. Phones count as outside devices, so Amazon's block doesn't apply. The TV asks "Allow debugging from this phone?" once.
- **After a TV reboot:** a background check, about every 15 minutes (WorkManager's minimum), sees that the helper isn't running. Two modes, chosen by the user: silent restart (default, the builder's pick) or a notification with a one-tap "Re-enable SideSpeed" button.
- No servers, accounts or cloud push; everything stays on the home network.
- Android only (iOS can't do this). A desktop app is out of scope; the README gives the PC command.
- **Trade-offs to state in the app:** on Fire TV with the phone route, ADB debugging must stay on so the phone can reach the TV, which revision 3 requires anyway. The phone holds a key the TV trusts.
- Designed in detail in its own plan after M1–M3 work on both boxes.

### Advanced route: one-time PC/phone help (owner-approved fallback)

Pairing may not work from the TV alone, because the stock pairing dialog closes when you leave Settings. For devices where it doesn't work, and for any device where 5555 is closed, setup offers an **Advanced setup** route:

1. SideSpeed shows the device's IP and one command to run from a PC or phone: `adb connect <ip>:5555` (or the wireless-debugging pair/connect pair of commands), then `adb tcpip 5555`. The screen also offers a short link or QR code to a README section with the steps.
2. SideSpeed connects to `127.0.0.1:5555` and the user accepts "Always allow". SideSpeed's own key is now trusted.
3. **After a reboot (revision 3):** SideSpeed does not switch ADB or Wireless Debugging back on by itself. If ADB is unreachable, it says so on the status screen and in a notification, with the steps to turn it back on and, on Fire TV, the helper start command.

### First-time setup (guided)

On first launch, or whenever connecting fails:

1. Detect the device type:
   - Fire OS: `Build.MANUFACTURER == Amazon` / `amazon.hardware.fire_tv` feature.
   - Android version.
   - Whether 5555 answers.
2. Show path-specific steps with a D-pad-friendly button to open the right settings screen, where an intent exists:
   - **Classic:** turn on Developer options, then ADB / Network debugging, then come back. SideSpeed connects and the "Allow debugging" dialog appears. Tick "Always allow".
   - **Wireless Debugging:** turn on Developer options, then Wireless debugging, then "Pair device with pairing code". The user enters the 6-digit code and pairing port with a big on-screen number pad. SideSpeed pairs, then connects.
3. Show the LAN-exposure warning before the first connection, and require an explicit "I understand".
4. Success screen, then the app list.

### Features

These follow SPEC.md. Notes on how each is done:

1. **App picker**
   - Lists installed apps that have a launcher entry.
   - Sideloaded apps sort first. "Sideloaded" means `getInstallSourceInfo` (API 30+) or `getInstallerPackageName` (older) is not `com.android.vending` or Amazon's appstore (`com.amazon.venezia`).
   - A **Popular** preset tab is loaded from bundled `assets/presets.json` (`[{ "package": ..., "label": ..., "note": ... }]`), filtered to apps that are installed.
   - **Manual package entry** checks the package with `PackageManager.getPackageInfo`.
2. **Compile**
   - Mode is `speed` (default) or `speed-profile`.
   - Runs `pm compile -m <mode> -f <pkg>`. If that exits non-zero or its output doesn't contain `Success`, it retries with `cmd package compile -m <mode> -f <pkg>`.
   - Output streams live into a scrolling log pane, with a clear Success/Failure banner at the end.
   - Only one compile runs at a time. The queue is serial.
   - Records the time and mode on success.
3. **Status check**
   - Parses the compiler filter for each tracked package from `dumpsys package dexopt` (fallback: `dumpsys package <pkg>`).
   - Shows `speed ✓`, `speed-profile`, or "Needs recompile" for each app.
   - The parser must cope with formats differing across Android versions. Unit-test it against captured samples from both test devices, plus Android 7–9 samples taken from AOSP sources.
4. **Auto-recompile**
   - Room or DataStore holds each tracked package with its `lastUpdateTime` at its last compile, plus the mode used.
   - Checked on app launch and by a periodic `WorkManager` job. Default interval is 6 h, configurable, and the job only runs while the device isn't low on storage.
   - If `lastUpdateTime` changed, or the status check shows a downgraded filter, it recompiles.
   - No `PACKAGE_REPLACED` manifest receivers.
5. **Settings**
   - Tracked apps.
   - Auto-recompile on/off.
   - Check interval.
   - Default mode.
   - The opt-in "reopen 5555" toggle.
   - **Connection diagnostics:** detected path, connect test, last error, key fingerprint, and a "forget pairing / regenerate key" action.
   - The persistent LAN-exposure warning.
   - Storage note: `speed` adds about 30–60 MB per app.

### UI

- Compose for TV, dark theme, every interactive element focusable with a visible focus ring or scale.
- Screens: Setup, Apps (tabs: Sideloaded / Popular / All / Manual), Compile (log + result), Status, Settings.
- Nothing ever requires touch or a pointer. The pairing-code entry uses a D-pad number grid, not the soft keyboard.

### Build and release

- Single `:app` module. Splitting out the ADB layer into its own module is left to the builder.
- `./gradlew assembleRelease` produces a signed APK when signing config is present, and an unsigned one otherwise.
- A GitHub Actions workflow builds and runs unit tests on push. Producing signed release APKs in CI from secrets is a later addition.

## Removed in revision 3 (do not implement)

<details><summary>"Survive reboots" automatic re-enable, and "ADB only while working"</summary>

~~3. **Survive reboots (opt-in, recommended on these devices):** over that first connection, SideSpeed runs `pm grant <its own package> android.permission.WRITE_SECURE_SETTINGS` on itself, with no extra step for the user.
4. A `BOOT_COMPLETED` receiver (allowed on Android 8+) and the periodic job then use that permission to:
   - turn Wireless Debugging back on (`Settings.Global` `adb_wifi_enabled = 1`, Android 11+);
   - find its port by mDNS;
   - connect over TLS with the already-trusted key;
   - optionally send `tcpip:5555`.

   This is the same technique Shizuku uses for its "start on boot (non-root)" mode.

What it needs and what it can't do:
- Wi-Fi (Wireless Debugging won't turn on over Ethernet).
- Android 11+.
- **Ethernet-only boxes on Android 11+ without 5555:** they get the advanced route, but must repeat the PC step after each reboot. The README says so.
- **Unverified:** that a key trusted through the classic "Always allow" prompt is accepted over the TLS connection. If it isn't, the fallback is a one-time pair from the PC using SideSpeed's own key: export the key through the app, or show the pairing command for it. The M1 spike settles this.
- **Security:** `WRITE_SECURE_SETTINGS` is a powerful permission. SideSpeed only ever writes `adb_wifi_enabled`, shows the grant clearly in Settings → Diagnostics, and has a "Revoke" button that runs `pm revoke` on itself.~~

~~### ADB only while working (default on)

**Why:** security-minded users don't want ADB listening on their network all the time. The owner chose this to be on by default.

**What happens:**
1. **Grant the permission once.** Over its first working ADB connection (any route, including the plain Fire TV 5555 route with no PC), SideSpeed grants itself `WRITE_SECURE_SETTINGS` with `pm grant`.
   - This is the same grant "Survive reboots" uses. With it, those two features are one permission.
   - Setup explains the grant in one screen before doing it.
2. **Check for updates with ADB off.** Spotting updated apps never needs ADB; it uses PackageManager (`lastUpdateTime`, and `getChangedPackages` on API 26+). ADB is only needed when there is compile work or a status check to do.
3. **For each batch of work:**
   1. Turn ADB on: `Settings.Global.ADB_ENABLED = 1`, plus `adb_wifi_enabled = 1` on Wireless Debugging devices.
   2. Wait for adbd and connect. Use mDNS for the TLS port, with a timeout.
   3. Run the queued compiles and status checks.
   4. Turn ADB off again, setting both values back to 0.

   Manual compiles from the UI use the same wrapper.
4. **"Keep ADB on" switch** (Settings, plus a shortcut on the home screen) for users who want to use ADB from a PC. While it's on, SideSpeed leaves ADB alone. It shows a reminder while it's on, and it can switch itself off after a time limit the user picks.
5. **Safety net:**
   - If turning ADB back on doesn't produce a working connection within the timeout, SideSpeed posts a notification explaining how to turn it back on by hand.
   - After a failure on a device, it turns this mode off for that device, so the device isn't left locked out each time.
   - Diagnostics shows the last on/off cycle and its result.
6. **Without the grant** (the user declined, or `pm grant` failed): ADB is left as the user set it, and the LAN warning stays.

**Unverified (M0 spike):** whether setting `adb_enabled` back to 1 brings 5555 back on the Fire TV Cube, and whether `adb_wifi_enabled` brings Wireless Debugging back on the Onn, with keys still trusted.

**Risk wording for the README and setup:**
- An open port does not give control to anyone on the LAN.
- **Wireless Debugging** accepts only paired devices.
- **Classic 5555** shows an "Allow debugging?" prompt for unknown keys.
- The real risks are someone accepting that prompt by mistake, and bugs in adbd. This mode shrinks how long either is possible.~~

</details>

## Milestones

0. **M0 — spike on the Onn 4K Pro (throwaway, before any setup UI).** Fire TV result already in (revision 2): on-box ADB is blocked. Remaining on the Onn:
   - Can pairing be done from the TV alone?
   - Is 5555 reachable?
   - Does a key trusted via "Always allow" work over TLS?
   - After a reboot, is Wireless Debugging still on? (Record only. SideSpeed will not switch it back on; revision 3.)
   - Capture `dumpsys package dexopt` samples from both boxes.

   The results decide the default setup route for Google TV. Report them to the owner before M1's setup UI.
   **M0b — helper spike (throwaway):** start a minimal `app_process` helper from the PC on the Fire TV Cube and from SideSpeed's own ADB on the Onn, run `pm compile` and `id` through it, and check whether it survives the ADB shell exiting, ADB debugging being switched off, and SideSpeed being updated.
1. **M1 — first milestone (from SPEC.md, extended to both paths and the helper):**
   - Project scaffold.
   - `AdbBackend` with classic and Wireless Debugging pairing/TLS.
   - Minimal setup flow.
   - Pick an app (list or manual entry), compile, show live output and result, show status.
   - Verified on both test devices.
2. **M2:** tracked apps, auto-recompile (launch and WorkManager), Popular presets, full Settings and diagnostics.
3. **M3:** polish. Banner/icon, README with a device table, the opt-in 5555 reopen if testing shows it helps, CI.
4. **M4:** phone companion app (see its section).

## Decided by the owner

- Public GPLv3 project in DevJardins/SideSpeed. The target is any Android TV box.
- Built-in ADB client covering both classic and Wireless Debugging paths (Approach A).
- If pairing from the TV alone doesn't work, the fallback is a one-time PC/phone step (option a), offered as an "Advanced setup" route.
- ~~Wants a one-time advanced option that lets SideSpeed reopen access by itself after reboots.~~ Withdrawn in revision 3.
- The owner will test builds on both boxes and send back output.
- Revision 2: a built-in helper adapted from Shizuku's starter (not the Shizuku app) runs compiles as the shell user, started by SideSpeed itself where on-box ADB works and by a PC or the phone companion where it doesn't (Fire TV).
- Revision 3: no automatic ADB off/on anywhere; the docs state that ADB must stay on.
- Revision 2: a phone companion app that restarts the helper after TV reboots, as a later stage (M4). Desktop app out of scope.
- ~~ADB is switched off between jobs and on only while SideSpeed is working, on by default.~~ Withdrawn in revision 3: ADB stays on, and the docs say so. This replaces the idea of tying in Obtainium: its "update available" signal comes before the install, and SideSpeed can detect installs itself.
- Everything in SPEC.md: features, UI stack, minSdk 24, sideload-only signed APK, caveats.

## Calls made in this design (owner can override)

- Wireless Debugging is in M1, not "later", because the target is any device and one test device likely needs it.
- Application ID is `io.github.devjardins.sidespeed`.
- Default auto-recompile interval is 6 h.
- The "reopen 5555 after TLS connect" trick is opt-in and off by default.
- The pairing code is entered on a D-pad number grid rather than the system keyboard.
- `allowBackup` is off, so the ADB key never leaves the device.
- An M0 spike comes before any setup UI.
- Helper transport is loopback TCP with a caller check, per revision 3. The builder picks the check method from the listed options.
- The helper accepts a fixed request set, not arbitrary shell.
- The helper exits when SideSpeed's APK is updated, and SideSpeed prompts to restart it.
- Phone companion default is silent restart; notification-and-tap is the alternative mode.
- Status-parser unit tests use captured `dumpsys` samples.

## Left to the builder

- Which ADB library to use, against the criteria above.
- Module layout, DI approach (manual or Hilt), and Room vs DataStore.
- Exact screen layouts within the UI rules above.
- Contents of the initial `presets.json`, beyond Nuvio, Stremio, Kodi and SmartTube. Known Nuvio fork package names need looking up.

## Revision 4 (2026-11-15): "Always allow" setup flow and calm TV theme (owner-approved)

**Why.**
- **Device finding:** on the onn 4K Pro, turning on Wireless debugging also makes adbd listen on `tcp:5555` (`adbd listening on tcp:5555`, then `adb wifi started on port 38341`). SideSpeed's classic connection to `127.0.0.1:5555` then needs only the standard "Allow debugging?" prompt with **Always allow**, and no pairing code. This is the flow the owner wants as the default: turn on Wireless debugging, open SideSpeed, tap Always allow, done.
- **Correction:** the earlier "TLS pairing verified" note was wrong. That run connected over 5555 too. Code pairing itself worked (SideSpeed appeared under Paired devices), but the TLS connect path is unverified.
- **Readability:** the owner asked for a readable, pretty UI that is easy on the eyes in a dark room.

**Setup flow (Google TV / non-Fire OS).**
- **Default screen:** headline "Allow SideSpeed on this TV", with three step cards:
  1. "Turn on Wireless debugging". Ticked "Detected" when `Settings.Global` `adb_wifi_enabled == 1` or `adb_enabled == 1`.
  2. "When your TV asks, tick Always allow, then Allow". Shows "Waiting for you…" while a connect attempt is pending or retrying.
  3. "SideSpeed starts its helper". Shows a spinner while launching.
  
  Buttons: "Open developer settings" (initial focus) and "Pair with a code instead".
- **Retry loop:** SideSpeed retries the readiness check on its own while the setup screen is visible. Attempts run strictly one after another: an attempt finishes, then a 2 s pause, then the next. A second connect is never started while one is pending, so the TV never stacks prompts. When ready, it goes to the app list.
- **Number pads:** hidden by default. "Pair with a code instead" reveals the pairing grid; it is never shown automatically on top of the default flow. If the classic connection is refused outright (port closed) for 3 consecutive attempts while Wireless debugging is on, a hint appears with focus moved to that button: "Your TV doesn't accept direct connections. Pair with a code instead."
- **Already allowed or paired:** the same silent retry loop connects and continues. The pads never appear.

**Setup flow (Fire OS).** Same layout and the same automatic retry, polling the helper ping.
1. "Turn on ADB debugging". Detected via `adb_enabled`.
2. "Start the helper from a PC". Shows the existing PC commands.
3. "Helper starts".

**App list.**
- A "Helper running" badge.
- Tabs: Sideloaded / All apps / Type a package.
- A compact mode selector.
- Rows show the app icon (loaded off the main thread and cached), label, package or "compiled <date>", and a status pill:
  - "speed ✓", "speed-profile" or "Not compiled", from **one** `dumpsys package dexopt` call parsed per package with `DexoptParser`;
  - "Checking…" until that call returns;
  - no pill if the check fails.

**Compile screen.**
- Header with the app icon, name, package and mode.
- Before starting: the "can take several minutes" note and "Start compile".
- While running: "Compiling…" with mm:ss elapsed and the "please wait" note. Back stays blocked.
- **Success:** a calm result card, "Compiled to speed", "Took 2 min 58 s · status confirmed speed ✓" (badge from the status check).
- **Failure:** a soft-red card with the message and the log expanded.
- Buttons: "Back to apps" (focused after finishing), "Compile again", "Show log" / "Hide log". The log is collapsed on success.
- "Check helper" stays for "Connection lost".

**Theme (dark-room friendly; all colours as Compose constants in `ui/theme`).**

| Token | Value | Use |
|---|---|---|
| background | `#10151E` | screen |
| surface | `#171E29` | cards |
| button | `#1A222E` | resting buttons and tabs |
| raised | `#222C38` | focused row |
| focusButton | `#2A3644` | focused button |
| textPrimary | `#EEF1F5` | headlines, focused text (never pure white) |
| textBody | `#D9DEE6` | body |
| textSecondary | `#8C96A4` | captions, packages |
| accent (sage) | `#93BFA2` | focus ring (2dp), "done" ticks, icons |
| okPill | bg `#1D2B24`, text `#9FC9AC` | |
| warnPill | bg `#2A2620`, text `#C9B48F` | |
| dangerCard | bg `#2E1F22`, text `#E3B1B1` | |

- **Rules:** no bright fills, no lime, no pure white. Focus is shown by a lighter surface plus the 2dp sage ring.
- **Type sizes (sp):** headline 28, title 20, body 16, caption 13. Generous padding (overscan-safe 48dp horizontal margins).
- **Logo:** the logo stays as is for the launcher icon and banner only.
