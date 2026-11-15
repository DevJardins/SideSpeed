# SideSpeed Companion — Design (M2)

Date: 2026-11-15
Status: draft (builder-checked; owner answered the 3 questions), awaiting owner approval
Builds on: `docs/superpowers/specs/2026-11-15-sidespeed-design.md` (SideSpeed TV, Revisions 1–4) and `docs/spike/M0-results.md`.
Replaces the earlier "Phone companion (M4)" section of the TV design. The companion is now M2, and it covers phone **and** desktop.

## Intent and the why

On current Fire OS, an app on the Fire TV can't use the box's own ADB (Amazon's local-ADB block, CVE-2024-27350; verified on the Fire TV Cube, Fire OS 7.7.1.7). So SideSpeed's helper must be started from outside the TV after every reboot or SideSpeed update. Today that takes a PC and a terminal command.

**SideSpeed Companion** removes the PC step for Fire TV owners. It's a small app on an Android phone, **or** on a Windows / macOS / Linux computer, for people without Android. It connects to the Fire TV over the home Wi-Fi as an outside device (which Amazon allows), checks whether SideSpeed's helper is running, and starts it if not. It does this in the background, quietly, and only on networks the user has marked as theirs.

**Audience:** non-technical Fire TV owners who sideload apps. They should never need a terminal.

## Goals

1. **One codebase** that builds an Android phone app and desktop apps for Windows, macOS and Linux.
2. **Setup in about a minute:**
   - add a TV (enter its address, or "Find TVs" on a trusted network);
   - accept "Always allow" on the TV once;
   - done.
3. **Background upkeep:**
   - about every 15 minutes, and immediately on joining a trusted network: check each saved TV and start the helper if it's missing;
   - silent by default, with an optional notification.
4. **"Restart now":** opening the phone app, or choosing "Restart now" in the desktop tray, checks all saved TVs immediately.
5. **Trusted networks:** the companion never connects, checks or scans on a network the user hasn't trusted.
6. **TV nudge:** when SideSpeed on a Fire TV finds the helper missing, it tells the user to open SideSpeed Companion on their phone or computer. PC commands stay available as a fallback.
7. **The same calm dark theme** as the TV app, adapted for phone and desktop.

## Non-goals

- iPhone / iOS (Apple doesn't allow this kind of app).
- Cloud, accounts, push servers, telemetry. Everything stays on the home network.
- Google TV / onn support. After a reboot its Wireless debugging is off and port 5555 is closed, so a companion can't reach it either, and on-TV "Always allow" already covers it. The companion may list such a TV, but it says it can't help after reboots.
- Doing anything to the TV other than checking for the helper and starting it. No compiles from the companion, and no arbitrary shell.
- App store distribution. Sideload / GitHub Releases only.
- Paid code-signing for Windows/macOS in M2. The README explains the "unknown developer" warning.

## Anti-goals (fails even if it technically works)

- **Scanning or connecting on networks the user didn't trust** (coffee shops, work, neighbours).
- **Running any TV command beyond the fixed check and launch.** The phone's or desktop's key is trusted by the TV, so a bug here would be a real exposure.
- **Draining the phone battery** or keeping a permanent notification just to exist. Background work uses WorkManager, not a foreground service.
- **Requesting the location permission at all.** Home networks are recognised without it.
- **A desktop app that needs a terminal to install or run.**

## Constraints

- **Language and UI:** Kotlin Multiplatform with Compose Multiplatform, Gradle Kotlin DSL, version catalog. Same repo as the TV app.
- **Android companion:** minSdk 26 (Android 8; listed under "Calls made"). targetSdk / compileSdk 36 like the TV app.
- **Desktop:** JVM via Compose Desktop. Installers via `jpackage`: `.msi` (Windows), `.dmg` (macOS), `.deb` and `.rpm` (Linux). Each must be built on its own OS, so add a GitHub Actions matrix (macos, windows, ubuntu). A bundled runtime is fine (installers ~60–90 MB).
- **License:** GPLv3; dependencies must be GPL-compatible. Kadb is Apache-2.0.
- **ADB library for the companions: Kadb** (`com.flyfishxu:kadb`, Apache-2.0, Kotlin Multiplatform for JVM and Android). The companions only need the classic TCP connection to `<tv-ip>:5555` with an RSA key and the "Always allow" prompt. No pairing, no TLS.
- **The TV app keeps libadb-android.** It is not migrated in M2.
- **Test devices:**
  - Fire TV Cube Gen 3 (Fire OS 7.7.1.7) as the target;
  - the owner's Android phone;
  - the owner's Mac (macOS) for desktop. Windows and Linux are built by CI and labelled "should work" until tested.

## Approaches

**Code layout. Picked by the owner: A.**

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | Separate companion app, plus a shared module holding the code both apps need | Each app does one job: the phone/desktop app stays small and has no TV permissions. Shared fixes (the `trap "" HUP` launch, the line-loss fix, the theme) live in one place. Costs a one-time mechanical move of files first. |
| B | One APK that switches to companion mode on phones | No move, but the phone gets the whole TV app and its permissions, two UIs get tangled, and it's confusing to explain. No desktop story. |
| C | Separate repo / copied code | Every fix has to be made twice. |

**Cross-platform technology. Picked by the owner: A.**

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | Kotlin Multiplatform + Compose Multiplatform, ADB via Kadb | Same language and toolkit as the TV app. One codebase covers Android, Windows, macOS and Linux. Tray and autostart are supported. Reuses proven code. Costs: bundled JVM on desktop. |
| B | Flutter/Dart | No mature Dart ADB library, so we'd hand-write the security-sensitive ADB auth. No code reuse. |
| C | Tauri/Rust | Young mobile support, a third language, no reuse. |

Per-OS builds are unavoidable with any choice. One codebase plus a CI matrix makes them automatic.

**Background behaviour. Picked by the owner: "A plus a nudge".** A background check about every 15 minutes, plus a "Restart now" button, plus the TV telling the user to open the companion. Rejected:
- an always-on listener with a permanent notification (battery, OEM killing, notification clutter);
- background checks only, with no nudge (up to a 15 min wait after reboot with no hint).

## Design

### Module layout

- **`:shared`** (new, Kotlin Multiplatform; targets `androidTarget` and `jvm`). Moved out of `:app`, behaviour unchanged:
  - `HelperLaunch.command()`, including `trap "" HUP`;
  - `HelperProtocol` constants;
  - `ShellEvent`, `ShellExit`, `shellFlow`;
  - the `AdbConnectionLost` and `CommandRunner` types;
  - the pure parts of `DexoptParser`, if useful;
  - theme colour tokens (as plain values Compose on each platform can use).
  
  Only pure-Kotlin code with no Android dependencies goes in `commonMain`.
- **`:app`** (the TV app) depends on `:shared`. No behaviour change except the TV nudge (below). All 209 existing tests must still pass after the move.
- **`:companion`** (new, KMP with Compose Multiplatform). Targets:
  - `androidTarget` → the "SideSpeed Companion" APK;
  - `jvm("desktop")` → the desktop app.
  
  Source sets:
  - `commonMain` (UI, logic, Kadb use);
  - `androidMain` (WorkManager, Wi-Fi name, notifications);
  - `desktopMain` (tray, autostart, network identity, notifications).

### What the companion does to a TV (fixed command set)

Connect to `<tv-ip>:5555` with the companion's own RSA key (generated once, stored in app-private storage / the desktop app-data directory, never synced). Then:

Every command is a constant defined in `:shared`. The companion sends exactly these and nothing else (owner decisions 2 and 3, 2026-11-15):

1. **Identify:** `getprop ro.serialno`. It must equal the serial saved when the TV was added, otherwise stop: wrong device. Run on every visit, before anything else.
2. **Installed?** `pm path io.github.devjardins.sidespeed`. If empty, report "SideSpeed isn't installed on this TV" and stop.
3. **Running?** `pidof sidespeed_helper`.
4. **Current?** If running, read the helper's command line: `cat /proc/<pid>/cmdline`, with `<pid>` taken only from step 3's numeric output and validated as digits. Compare its `--apk=` value with step 2's path. A mismatch (SideSpeed was updated) means stale.
5. **Restart if stale:** `kill $(pidof sidespeed_helper)` (= `ConnectionStrategy.KILL_COMMAND`), wait 500 ms.
6. **Start if not running, or after step 5:** exactly `HelperLaunch.command()`. Then poll `pidof` for up to 5 s and report success or failure.

The companion never takes free-form commands, never uninstalls, compiles or changes settings. Tests assert that only these constants (with a validated numeric pid) are ever sent.

Each run uses one connection that is closed afterwards.

If the TV's auth prompt is pending (connect times out), report "Waiting for you to allow this device on the TV" and don't retry in a tight loop.

### Adding a TV

Two ways:
- **Enter the address:** SideSpeed on the TV shows it.
- **"Find TVs":** only on a network the user has marked as home (see Trusted networks). Scan the local /24 for port 5555 with short timeouts and list the hosts that answer. A host is identified only after connecting, via `getprop ro.product.manufacturer`, `ro.product.model`, `ro.build.version.name` and `ro.serialno` (all read-only).

Adding a TV:
1. connects (the TV shows "Allow debugging?"; the UI says "Tick Always allow on your TV");
2. saves the TV: name (editable, defaults to the model, e.g. "Fire TV Cube"), address, **serial number**, and the home network it belongs to;
3. runs a check and start right away.

### Home networks (trusted networks)

**The user chooses them explicitly. It's set-and-forget (owner decision, 2026-11-15).**
- **First-run setup:** the first screen is "Choose your home network". It shows the network the device is on now, with a fingerprint summary such as "Router 192.168.5.1 · 192.168.5.x". The user confirms "This is my home network", or says "Not now". Nothing touches the network until a home network is chosen. Adding a TV comes after.
- **Later:** to add another home network, the user opens the app while on it and chooses "Add this network as home" in Settings. The app never suggests or auto-adds networks.

**Identity of a network: no location permission on any platform (owner decision 1 = option b).**
- **Fingerprint:** the default gateway IP, the subnet (address and prefix length) and the DNS server addresses.
  - Android: `ConnectivityManager.getLinkProperties(activeNetwork)`, which needs no location permission. The Wi-Fi name (SSID) is **not** used.
  - Desktop: OS routing and DNS info, behind an interface per OS. The builder may add the gateway MAC where it's readable without prompts (Windows / Linux ARP table) to strengthen the match.
- **Display name:** the user can name each home network (default "Home"). The app never needs the real Wi-Fi name.
- **Collision safety:** a different network with the same router address and subnet (e.g. 192.168.1.1/24 at a café) could match. The worst outcome is one TCP connection attempt to the saved TV address, which fails or reaches a device whose `ro.serialno` doesn't match, so the companion stops (step 1 of the command set). It never sends any command to a device whose serial doesn't match, and it never scans because of a fingerprint match alone. The automatic re-find (below) needs 3 consecutive offline checks on that network first.
- **Rules:**
  - Background checks, "Restart now" and "Find TVs" run only while the current network matches a trusted network.
  - Otherwise: show "Not on a trusted network" and do nothing on the network.
  - Saved TVs are checked only if their saved network is the current one.
- **Automatic re-find (owner decision 3 = option a):** after 3 consecutive offline checks for a TV while on its home network, scan that network's /24 once for port 5555, connect to hosts that answer, and match by `ro.serialno`. If found, update the saved address and continue. Run at most once per hour per TV. Manual "Find TVs" stays available.
- **Joining a trusted network** triggers a check immediately:
  - Android: a `ConnectivityManager.NetworkCallback` registered while the app process is alive, plus the WorkManager periodic job;
  - desktop: poll the network identity every 30 s while running.
- **Settings:** the home-network list (rename, remove) and "Add this network as home", offered only as an explicit user action while on that network.

### Background upkeep

- **Android:**
  - a `WorkManager` periodic job every 15 minutes with constraint `NetworkType.CONNECTED`. The job itself checks the trusted-network rule before doing anything;
  - no foreground service;
  - a one-off expedited job when the app opens or a trusted network is joined.
- **Desktop:**
  - a tray icon with menu items "Restart now", "Open SideSpeed Companion", "Pause checks", "Quit";
  - a coroutine timer every 15 minutes, plus the 30 s network-identity poll;
  - "Start when I log in", off by default: Windows Startup-folder shortcut or a registry Run key, macOS LaunchAgent plist, Linux XDG autostart `.desktop`. Builder's choice per OS, documented.
- **Notifications** (optional, off by default): "SideSpeed restarted on <TV name>", plus a one-time notification if a check needs the user ("Allow this device on <TV name>").
- **Backoff:** if a TV is unreachable 3 checks in a row, show it as "Offline" and keep the normal 15-minute schedule (no faster retries).

### Companion UI (calm theme)

The same tokens as the TV app (Revision 4 table): background `#10151E`, surface `#171E29`, text `#EEF1F5` / `#D9DEE6` / `#8C96A4`, sage accent `#93BFA2`, the pill colours. Phone-sized typography. Desktop uses the same Compose screens in a modest window (about 420×640).

Screens:
1. **Home:**
   - a "Trusted network: <name> ✓" or "Not on a trusted network" banner;
   - one card per TV: name, address, status (● Helper running / Not running / Offline / Waiting for you to allow / SideSpeed not installed), and "Checked N min ago";
   - per-card "Restart now";
   - "+ Add a TV".
2. **Add a TV:** an address field, "Find TVs" (trusted network only), the scan results list, the "Tick Always allow on your TV" step, done.
3. **Settings:**
   - trusted networks;
   - notifications on/off;
   - check interval (15 min, fixed minimum; "1 hour" option);
   - desktop only: "Start when I log in";
   - about and licences (NOTICE).

### TV app change (the nudge)

On Fire OS, when `Readiness` is `NeedsHelperStart` or `HelperNeedsRestart`, step 2 of the setup screen becomes:
- **title:** "Start the helper from your phone or computer";
- **body:** "Open SideSpeed Companion on your phone or computer. It starts the helper for you. Get it from github.com/DevJardins/SideSpeed/releases";
- the PC commands move under "Other ways to run it".

The existing retry loop already detects the helper within a few seconds of the companion starting it, and moves on.

### Build and release

- GitHub Actions workflow:
  - `ubuntu-latest`: the companion and TV Android APKs, the Linux `.deb` / `.rpm`, and unit tests;
  - `macos-latest`: `.dmg`;
  - `windows-latest`: `.msi`;
  - artefacts uploaded per run.
  
  Signing secrets for the Android APKs come later (owner keystore). Desktop installers are unsigned in M2.
- The README gains a "SideSpeed Companion" section: install per OS, the unsigned-installer warning and how to proceed, and the trusted-network explanation.

## Testing

- **`:shared`:** the existing tests move with the code and still pass.
- **`:companion` common logic, with fakes:**
  - check → start → verify flow;
  - "SideSpeed not installed";
  - pending-auth timeout;
  - offline backoff;
  - home-network gating: no network calls when the current fingerprint isn't a home network, and none before first-run setup chooses one;
  - serial mismatch → no further commands;
  - stale helper (`--apk` mismatch) → kill, then launch; validated numeric pid only;
  - automatic re-find after 3 offline checks, at most hourly, matched by serial;
  - scan only when trusted;
  - only the two constant commands are ever sent, asserted with a recording fake ADB.
- **Device checks:**
  - phone + Fire TV Cube: add, Always allow, reboot the TV, helper back within 15 min, and immediately on opening the app;
  - Mac + Cube: the same;
  - untrusted network (phone hotspot): no connections attempted.

## Decided by the owner

- M2 is the Fire TV companion, on phone **and** desktop.
- Code layout A (shared module + separate companion app). Technology A (Kotlin Multiplatform + Compose Multiplatform).
- Background check ~15 min + "Restart now" + TV nudge.
- Trusted networks: only act on networks the user marked as theirs; no checking or scanning elsewhere.
- The same calm theme on phone and desktop.
- **Home networks are chosen by the user** at first-run setup, plus "Add this network as home" later from Settings. Set and forget.
- **Network identity uses no location permission** (decision 1 = b): a router, subnet and DNS fingerprint, with a serial-number check before any command.
- **Stale helper (decision 2 = a):** the companion may read the helper's command line and kill it when stale, then relaunch.
- **Address changes (decision 3 = a):** automatic re-find on the home network after 3 offline checks, matched by serial.
- Everything in the earlier playback: setup flow, silent by default with optional notification, fixed command set, multiple TVs, Android only on mobile, no cloud.

## Calls made in this design (owner can override)

- ADB via Kadb in the companions; the TV app stays on libadb-android.
- Android companion minSdk 26.
- Fingerprint = gateway IP + subnet + DNS servers (plus gateway MAC on desktop where readable without prompts).
- "Find TVs" scans the /24 only, and only on a home network; automatic re-find at most hourly per TV.
- Desktop: tray app, "Start when I log in" off by default, window about 420×640.
- Notifications off by default. Check interval options: 15 min (default) or 1 hour.
- "Offline" after 3 failed checks, with no faster retry.
- Google TV boxes: not targeted; the companion shows a note if one is added.

- Module split: AGP 9 doesn't allow an Android application inside a KMP module, so the companion is a KMP library (`:companion`, the shared UI and logic) plus thin `:companion-android` and `:companion-desktop` app modules. This is still layout A.
- Apple Silicon `.dmg` only in M2. An Intel Mac build can come later if asked.
- The companion's application ID is `io.github.devjardins.sidespeed.companion`.

## Left to the builder

- Exact KMP / Compose Multiplatform / Kadb versions compatible with the existing AGP/Kotlin.
- How theme tokens are shared (a common constants object is fine).
- Per-OS autostart and network-fingerprint lookup implementations, behind interfaces.
- Desktop tray icon artwork: reuse the robot from the logo.
- Scan concurrency and timeouts (keep it under ~5 s for a /24).
