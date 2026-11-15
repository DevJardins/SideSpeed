# TV Speed Compiler — Project Spec

An Android TV / Fire TV app you sideload onto the box. It force-compiles chosen apps with ART (the same as running `adb shell pm compile -m speed -f <pkg>`) right on the device, with no phone or PC needed after the first setup.

## Background
Play Store installs come with Baseline Profiles, so their code is compiled ahead of time. Sideloaded APKs (for example the Nuvio GitHub build, `com.nuvio.tv`) start out interpreted/JIT and feel sluggish. Running this command over ADB fixes that:

```
pm compile -m speed -f <package>
# older devices / early Fire TV fallback:
cmd package compile -m speed -f <package>
```

The compile has to be run again after every app update, because updating replaces the compiled code.

## Core constraint
A normal app can't run `pm compile`; it needs shell (ADB) UID. **Approach: the app talks ADB to its own device.**
- Primary: a built-in ADB client (e.g. `dadb` by mobile.dev) connects to `127.0.0.1:5555`.
  - The user turns on ADB / Network Debugging once and accepts the "Always allow" prompt once.
  - Keep the RSA keypair in app-private storage so later connections are silent.
- Android 11+ Google TV with no port 5555: support Wireless Debugging pairing (pairing code + port entry).
- Optional later: Shizuku API as an alternative backend.

## Features
1. **App picker**
   - List installed apps (needs `QUERY_ALL_PACKAGES`), with sideloaded ones sorted first (installer ≠ Play Store).
   - "Popular" preset list: Nuvio (`com.nuvio.tv`) plus known forks, Stremio, Kodi, SmartTube, etc. Keep it in a bundled JSON file that's easy to edit.
   - A field to type in a package name manually, with a check that the package is installed.
2. **Compile**
   - Mode selector: `speed` (default) or `speed-profile`.
   - Try `pm compile`, and if that fails use `cmd package compile`.
   - Show live output and a Success/Failure result. A run usually takes 10–30 s.
3. **Status check**
   - Read back the compiler filter from `dumpsys package dexopt` or `dumpsys package <pkg>` and show "speed ✓" or "Needs recompile" for each app.
4. **Auto-recompile after updates**
   - Save each tracked package's `lastUpdateTime`.
   - Check on app launch and with a periodic WorkManager job. If the time has changed, recompile.
   - Don't rely on manifest `PACKAGE_REPLACED` receivers (Android 8+ restricts implicit broadcasts).
5. **Settings**
   - Tracked apps, auto-recompile on/off, check interval, and connection diagnostics.

## UI
- Jetpack Compose for TV (`androidx.tv`), fully usable with a D-pad and clear focus states.
- Leanback launcher intent and a banner, so it shows up on the Android TV / Fire TV home screen.

## Tech
- Kotlin, minSdk 24 (to cover older Fire TV), and target the current SDK.
- Gradle build that outputs a signed release APK for sideloading.

## Caveats to surface in the UI
- ADB debugging has to stay enabled, which exposes ADB on the LAN. Show a warning.
- Background dexopt on some Android versions can downgrade compiled apps, which is why the status check and auto-recompile exist.
- `speed` adds about 30–60 MB per app.

## First milestone
Connect to localhost ADB → pick an app from the list or type a package → run the compile → show the result and status.
