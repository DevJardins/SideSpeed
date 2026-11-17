# SideSpeed

<img src="docs/brand/sidespeed-logo-2026.png" alt="SideSpeed logo" width="320">

Sideloaded apps on Android TV and Fire TV skip the compiling that store installs get, so they start and run slower. SideSpeed compiles them to native code ahead of time and keeps them compiled after updates.

## Install on the TV

Sideload the release APK onto your TV with a file-transfer app (for example LocalSend or Downloader) or with `adb install`. SideSpeed appears on the home screen.

APKs come from the `ubuntu` artifact of the GitHub Actions build on `main`. CI signs the release APKs with the project key. There is no Releases page.

## Setup

SideSpeed needs a helper running as the shell user. It starts from the APK itself and the app guides you through it. First it shows a warning you must accept with **I understand**.

- **Fire TV / Fire OS:** turn on ADB debugging (Settings > My Fire TV > Developer options). Then start the helper from SideSpeed Companion on your phone or computer (see below), or from a PC with **Other ways to run it**.
- **Google TV / Android TV (e.g. onn 4K Pro):**
  1. Turn on Wireless debugging (Settings > System > Developer options).
  2. Open SideSpeed. When the TV asks, tick **Always allow**, then **Allow**.
  3. SideSpeed starts its helper by itself and shows your apps. No PC and no pairing code are needed.

  If your TV does not accept direct connections, choose **Pair with a code instead** and enter the code and port from "Pair device with pairing code". Starting the helper from a PC also works.

## Keeping apps optimized

An app update throws away its compiled code. Open the app's compile screen and choose **Keep this app optimized**, and SideSpeed recompiles it, one app at a time, after every update.

The **Auto-optimize** tab on the apps screen keeps groups of apps optimized automatically:

- **All apps**: every app you installed, from a store or not, is compiled to speed and kept there after updates. Preinstalled apps are left alone unless you pick them.
- **Sideloaded only**: only apps that did not come from a store are kept optimized. Apps you pick yourself are included too.
- **Selected apps**: only the apps you pick. Open an app and choose "Keep this app optimized" to add it.

Anything still in speed-profile or a lower filter is upgraded to speed. The tab also shows a history of the last 20 automatic compiles. Work waits while the helper is down and resumes when it is back.

## SideSpeed Companion

Companion apps for Android phones and for Windows, macOS and Linux. They start the SideSpeed helper on your Fire TV for you, so you do not need a PC and a terminal each time. Fire TV only; on other TVs SideSpeed handles setup itself.

**Install.** The desktop installers are unsigned:

- **Android:** sideload the companion APK from the `ubuntu` build artifact. It is signed like the TV APK.
- **macOS:** open the `.dmg` from the `macos` build artifact and drag the app to Applications. If macOS says it cannot verify the developer, right-click the app, choose **Open**, then **Open** again.
- **Windows:** run the `.msi` from the `windows` build artifact. If SmartScreen blocks it, choose **More info**, then **Run anyway**.
- **Linux:** install the `.deb` or the `.rpm` from the `ubuntu` build artifact.

**First use:** open the companion and choose your Trusted Network (**Trust this network**). Then **Add a TV**: type its address or use **Find TVs**, and tick **Always allow** when the TV asks.

**Trusted Networks:** you choose which networks the companion may act on. On any other network it does nothing and sends nothing. It needs no location permission; a network is recognised by its gateway, subnet and DNS settings.

**Tailscale (desktop only).** Adding a TV by its Tailscale address trusts your own tailnet automatically, and the TV is kept alive wherever Tailscale is connected. TVs shared in from another tailnet are refused. **Settings > Tailnets** lists your tailnets, and removing a tailnet removes its TVs. Anyone who administers the tailnet can reach the TV. The Android companion has no Tailscale support.

**TV card borders** show how the last check reached the TV: green on the home network, blue over Tailscale, red when it could not be reached. There is no colour when the TV cannot be checked from here (its saved status is shown as "Last seen") or when it has not been checked yet.

**Limits:** IPv6-only networks are never trusted. Find TVs sweeps the Trusted Network's own subnet (a large network shrinks to the /16 around this device or the gateway). You can also type a range from /16 to /30 on the same network instead.

## Requirements and caveats

- ADB debugging (Fire TV) or Wireless debugging (Google TV) must stay on.
- Enabling it exposes ADB on your local network. Only use it on a network you trust; the app asks you to acknowledge this first.
- Restarting the TV, turning ADB off, or updating SideSpeed stops the helper (or leaves it out of date) and you must start it again. The onn turns Wireless debugging off after a reboot.
- `speed` compilation adds tens of MB of storage per app.
- A compile can take several minutes for large apps (about 3 min on a Fire TV Cube).

## Tested devices

| Device | Status |
|---|---|
| Fire TV Cube Gen 3, Fire OS 7.7.1.7 | Helper started from PC, compile verified |
| onn 4K Pro, Android 14 | Wireless debugging on + "Always allow" (classic connection on port 5555), helper auto-start and compile verified (no PC needed). Pairing with a code works; connecting over the paired TLS link is not yet verified |
| Other Android TV, Google TV and Fire TV devices | Should work |
| Android companion app with the Fire TV Cube Gen 3 | First run, add by address, and restarting an out-of-date helper verified over the LAN |
| macOS companion app with the onn 4K Pro | Adding, checks and card borders verified over the LAN and over Tailscale (the onn starts its own helper) |
| `.msi`, `.deb` and `.rpm` | Built by CI but not tested yet |

## Building

JDK 21 and the Android SDK. CI runs these test tasks:

```
./gradlew :shared:allTests :companion:allTests :app:testDebugUnitTest :companion-desktop:test
```

Release builds per module:

```
./gradlew :app:assembleRelease
./gradlew :companion-android:assembleRelease
./gradlew :companion-desktop:packageDmg :companion-desktop:packageMsi :companion-desktop:packageDeb :companion-desktop:packageRpm
```

Run the desktop app from source with `./gradlew :companion-desktop:run`.

For signed APKs, copy `keystore.properties.example` to `keystore.properties` and fill it in (or set the `SIDESPEED_*` environment variables). Without a keystore the release APKs stay unsigned.

## License

GPLv3, see `LICENSE`. Third-party attributions are in `NOTICE`.

## Logo and fonts

The SideSpeed logo and icons are a modified version of the Android robot. The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License (https://creativecommons.org/licenses/by/3.0/).

Brand sources and rules live in `docs/brand/ATTRIBUTION.md`. The banner wordmark uses Poppins SemiBold under the SIL Open Font License (`docs/brand/fonts/OFL.txt`).
