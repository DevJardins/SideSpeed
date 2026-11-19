# SideSpeed

<img src="docs/brand/sidespeed-logo-2026.png" alt="SideSpeed logo" width="320">

Sideloaded apps on Android TV and Fire TV skip the compiling that store installs get, so they start and run slower. SideSpeed compiles them to native code ahead of time and keeps them compiled after updates.

Website and downloads: **[sidespeed.app](https://sidespeed.app)**

## Install on the TV

The quickest way: install **Downloader** on the TV (from the Amazon Appstore or Google Play), open it and enter the code **3503400**. It downloads the latest SideSpeed APK; choose **Install** (if the TV asks, allow Downloader to install unknown apps). SideSpeed appears on the home screen.

Or open **sidespeed.app/tv** in the TV's browser, which downloads the same APK. You can also download `SideSpeed.apk` from the [Releases page](https://github.com/DevJardins/SideSpeed/releases) and sideload it with a file-transfer app (for example LocalSend) or with `adb install`. Release APKs are signed with the project key.

To update, press **Update now** on the update notice on the Apps screen (SideSpeed 0.5.0 or later). SideSpeed downloads the new APK from this repository's releases, checks its checksum, package name, signing key and version, and installs it. With the helper running it installs silently and SideSpeed reopens; without it, Android asks you to confirm (the first time, allow SideSpeed to install unknown apps). SideSpeed checks for updates at most once a day (or right away from **About** → **Check for updates**, 0.5.2 or later) and never installs anything until you press the button. You can also install the new APK over the old one yourself, for example with Downloader. Your settings are kept either way. Updating stops the helper: on Google TV, just open SideSpeed; on Fire TV, the Companion starts it again.

Fire TV Sticks that run Vega OS (instead of Fire OS) are not supported: they cannot run Android apps.

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

**SideSpeed optimizes itself too.** After you install or update it, SideSpeed compiles itself to speed the next time you leave the app while the helper is running. The Apps screen shows **SideSpeed optimized** once that is done. The APK also includes a baseline profile, which tells Android which of SideSpeed's own code to compile first.

## Tools

The **Tools** button on the Apps screen opens three cards:

- **Box health:** device name, Android version, memory, storage and whether the helper is running.
- **Free up space:** shows free and total storage. **Clean up now** clears app caches (apps keep their data but may download artwork again). **Automatic cleanup** keeps 500 MB, 1 GB or 2 GB free: while the helper runs, SideSpeed checks every few hours and clears only as much cache as it needs. It never runs on a timer. Off by default.
- **Faster animations:** Normal, Faster or Off for Android's window, transition and animator scales. Menus and screen changes move faster; apps do not load faster. **Restore original** puts back the values you had before.

**Launch time.** On an app's compile screen, SideSpeed can time how long the app takes to open before and after compiling (it opens the app briefly, three times each side, and shows the median), for example `1.9 s → 1.1 s (42% faster)`. **Measure** re-checks any app: it resets the app's compiled code, times it, compiles it back and times it again. The result is kept per app version; after an update it is marked as from an older version.

## SideSpeed Companion

Companion apps for Android phones and for Windows, macOS and Linux. They start the SideSpeed helper on your Fire TV for you, so you do not need a PC and a terminal each time. On Google TV and other Android TVs, SideSpeed handles setup itself; you can still add those TVs to see what they are compiling.

**Install:** on a phone, open **[sidespeed.app/phone](https://sidespeed.app/phone)**. On a computer, open **[sidespeed.app](https://sidespeed.app)**, which offers the right installer for your system. Every file is also on the [Releases page](https://github.com/DevJardins/SideSpeed/releases). The desktop installers are unsigned:

- **Android:** install `SideSpeed-Companion.apk` on your phone. It is signed like the TV APK.
- **macOS:** open the `.dmg` (Apple silicon) and drag the app to Applications. If macOS says it cannot verify the developer, right-click the app, choose **Open**, then **Open** again.
- **Windows:** run the `.msi`. If SmartScreen blocks it, choose **More info**, then **Run anyway**.
- **Linux:** install the `.deb` or the `.rpm`.

**First use:** open the companion and choose your Trusted Network (**Trust this network**). Then **Add a TV**: type its address or use **Find TVs**, and tick **Always allow** when the TV asks.

**Trusted Networks:** you choose which networks the companion may act on. On any other network it does nothing and sends nothing. It needs no location permission; a network is recognised by its gateway, subnet and DNS settings.

**Tailscale (desktop only).** Adding a TV by its Tailscale address trusts your own tailnet automatically, and the TV is kept alive wherever Tailscale is connected. TVs shared in from another tailnet are refused. **Settings > Tailnets** lists your tailnets, and removing a tailnet removes its TVs. Anyone who administers the tailnet can reach the TV. The Android companion has no Tailscale support.

**Live status:** while the companion's home screen is open, each TV card shows what SideSpeed is compiling right now, how many apps are waiting, and the last result. The TV needs SideSpeed 0.4.0 or later.

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

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (and in `companion-android/build.gradle.kts` if the companion changed).
2. Write the release notes in `.github/release-notes/v<version>.md`.
3. Merge to `main`, then tag and push: `git tag v<version> && git push origin v<version>`.
4. CI builds everything and creates a **draft** release. It fails if either APK is not signed with the project key. The draft has versioned files, `SHA256SUMS.txt`, and copies under fixed names: `SideSpeed.apk`, `SideSpeed-Companion.apk`, `SideSpeed-Companion-macos-arm64.dmg`, `-windows-x64.msi`, `-linux-amd64.deb` and `-linux-x86_64.rpm`.
5. Check the draft, then publish it.

The Downloader code 3503400 and the sidespeed.app links (`/tv`, `/companion.apk`, `/mac`, `/windows`, `/linux`, `/linux-rpm`) fetch `releases/latest/download/<fixed name>`. So every release must include all of these files under exactly these names.

## Support

SideSpeed is free. If it made your TV faster and you want to say thanks, you can [buy me a coffee on Ko-fi](https://ko-fi.com/devjardins).

## License

GPLv3, see `LICENSE`. Third-party attributions are in `NOTICE`.

## Logo and fonts

The SideSpeed logo and icons are a modified version of the Android robot. The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License (https://creativecommons.org/licenses/by/3.0/).

Brand sources and rules live in `docs/brand/ATTRIBUTION.md`. The banner wordmark uses Poppins SemiBold under the SIL Open Font License (`docs/brand/fonts/OFL.txt`).
