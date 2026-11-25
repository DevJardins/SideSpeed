# SideSpeed

<img src="docs/brand/sidespeed-logo-2026.png" alt="SideSpeed logo" width="320">

<!-- discord-guide:start -->
SideSpeed makes sideloaded apps on Android TV and Fire TV start and run faster. Store installs get compiled to native code; sideloaded apps don't. SideSpeed compiles them and keeps them compiled after updates. It is free.

Website and downloads: **[sidespeed.app](https://sidespeed.app)**

## Install on the TV

1. On the TV, open **Downloader** and enter the code **3503400**. Or open **sidespeed.app/tv** in the TV's browser.
2. Choose **Install**. If the TV asks, allow Downloader to install unknown apps.
3. SideSpeed appears on the home screen.

Fire TV Sticks that run Vega OS can't run Android apps, so SideSpeed can't run on them.

## Set it up

SideSpeed needs a small helper to do its work. How you start it depends on your TV.

### Google TV and Android TV

1. Turn on **Wireless debugging**: Settings > System > Developer options. ADB debugging must stay on, and anyone on your network could ask the TV for access. Only do this on a network you trust.
2. Open SideSpeed and press **I understand**.
3. When the TV asks, tick **Always allow**, then press **Allow**.
4. SideSpeed starts its helper and shows your apps. You're done.

If SideSpeed can't connect, choose **Pair with a code instead** and enter the code and port from **Pair device with pairing code**.

### Fire TV

1. Turn on **ADB debugging**: Settings > My Fire TV > Developer options. ADB debugging must stay on, and anyone on your network could ask the TV for access. Only do this on a network you trust.
2. Open SideSpeed and press **I understand**.
3. Fire TV can't start the helper by itself, so SideSpeed Companion starts it from your phone or computer. Get it at **[sidespeed.app/phone](https://sidespeed.app/phone)** or **[sidespeed.app](https://sidespeed.app)**.
4. In the Companion, press **Trust this network**, then **Add a TV** and pick your Fire TV. Fire TV can't use an encrypted connection, so the Companion asks first: anyone on your network could see or change the commands it sends. Tick **I understand. Connect the older way.** and pick your Fire TV again. When the TV asks, tick **Always allow**, then press **Allow**.
5. The Companion starts the helper. Open SideSpeed again and it shows your apps.
<!-- discord-guide:end -->

## Using it

- **My Apps:** tick an app to keep it compiled after every update.
- **Auto-optimize:** keep all your apps, or only sideloaded ones, compiled without ticking each one.
- **Updates:** when a new version of SideSpeed is out, press **Update now** on the Home screen.
- **Language:** pick it in **About** on the TV, or in **Settings** in the Companion.
- **If the helper stops** (after a restart, or after updating SideSpeed): on Google TV, open SideSpeed; on Fire TV, open the Companion. The onn turns Wireless debugging off after a reboot, so turn it back on first.

The rest of this page is the detail: every feature, how it works, and the full caveats.

## More about installing and updating

Downloader is in the Amazon Appstore and on Google Play. The code **3503400** and **sidespeed.app/tv** both download the latest `SideSpeed.apk`. You can also download it from the [Releases page](https://github.com/DevJardins/SideSpeed/releases) and sideload it with a file-transfer app (for example LocalSend) or with `adb install`. Release APKs are signed with the project key.

To update, press **Update now** on the update notice on the Home screen (SideSpeed 0.5.0 or later). SideSpeed downloads the new APK from this repository's releases, checks its checksum, package name, signing key and version, and installs it. With the helper running it installs silently and SideSpeed reopens; without it, Android asks you to confirm (the first time, allow SideSpeed to install unknown apps). SideSpeed checks for updates every 6 hours (or right away from **About** → **Check for updates**, 0.5.2 or later; from 0.5.4 it offers **Update now** straight away) and never installs anything until you press the button. You can also install the new APK over the old one yourself, for example with Downloader. Your settings are kept either way. On Fire TV and other Android 9 boxes, Update now works from 0.5.4; on 0.5.2 or 0.5.3, update once with Downloader. Updating stops the helper: on Google TV, just open SideSpeed; on Fire TV, the Companion starts it again.

## More about setup

The helper runs as the shell user, which is what lets SideSpeed ask Android to compile other apps. It starts from the SideSpeed APK itself; nothing else is installed on the TV.

- **Google TV / Android TV** (tested on the onn 4K Pro): SideSpeed connects to the TV's own Wireless debugging, so no PC and no pairing code are needed. Pairing with a code is there for TVs that don't accept direct connections.
- **Fire TV:** Fire OS doesn't let an app start the helper by itself, so SideSpeed Companion does it over the network. You can also start it from a PC: in SideSpeed, choose **Other ways to run it**.

## Keeping apps optimized

SideSpeed's Home screen has four buttons: **My Apps**, **Auto-optimize**, **Tools** and **About**.

An app update throws away its compiled code. On the **My Apps** screen each app has a checkbox: tick it and SideSpeed recompiles the app, one app at a time, after every update. You can also open the app and choose **Keep this app optimized**. The screen shows **All** apps or **Sideloaded** apps (All is the default).

The **Auto-optimize** screen keeps groups of apps optimized automatically:

- **All apps**: every app you installed, from a store or not, is compiled to speed and kept there after updates. Preinstalled apps are left alone unless you pick them.
- **Sideloaded only**: only apps that did not come from a store are kept optimized. Apps you pick yourself are included too.
- **Selected apps**: only the apps you ticked.

An app already covered by Auto-optimize shows a ticked, locked box. Anything still in speed-profile or a lower filter is upgraded to speed. **History** on the Auto-optimize screen shows the last 20 automatic compiles. Work waits while the helper is down and resumes when it is back.

On an app's compile screen, **Mode** picks **speed** or **speed-profile** (speed is the default); the app's last-used mode is remembered.

**Updates for sideloaded apps.** Sideloaded apps don't update through a store, so SideSpeed tells you when a newer version is out. Once a day it downloads one list from `sidespeed.app/updates.json` (built nightly from a short hand-picked list plus the F-Droid and IzzyOnDroid catalogs) and compares it on the TV; your app list never leaves the TV. When an installed sideloaded app is out of date, its row in **My Apps** shows **Update:** and the new version, the Home screen's My Apps button counts the updates, and the app's screen shows where to get it as a QR code and a short link. Only a version that is certainly newer shows; betas and test builds never do. The app's screen also offers **Update now** when the list names a download that fits your TV: SideSpeed downloads it, checks it is a real update of the app you have (same app, newer version, same key), then installs it. Your data in the app stays. SideSpeed installs only updates of apps already installed, one press at a time; it never installs new apps.

**SideSpeed optimizes itself too.** After you install or update it, SideSpeed compiles itself to speed the next time you leave the app while the helper is running. The Home screen shows **SideSpeed optimized** once that is done. The APK also includes a baseline profile, which tells Android which of SideSpeed's own code to compile first.

## Tools

The **Tools** button on the Home screen opens five cards:

- **Box health:** device name, Android version, memory, storage and whether the helper is running.
- **Free up space:** shows free and total storage. **Clean up now** clears app caches (apps keep their data but may download artwork again). **Automatic cleanup** keeps 500 MB, 1 GB or 2 GB free: while the helper runs, SideSpeed checks every few hours and clears only as much cache as it needs. It never runs on a timer. Off by default.
- **Animations:** Normal, Faster or Off for Android's window, transition and animator scales. Menus and screen changes move faster; apps do not load faster. **Restore original** puts back the values you had before. On Fire TV, app animations stay at normal speed so the Settings menu keeps working.
- **Turn off extras:** opens a screen that disables (never uninstalls) apps your TV maker added that you don't use, from a list matched exactly to your box model. Pick packages and press **Turn off selected**. Any app on the list that is off can be turned back on with **Turn on selected**, even if something else turned it off; **Restore everything** still turns back on only what SideSpeed turned off. Lists come from the [CleanYourTV](https://github.com/Gathin23/cleanyourtv) project (MIT). When no list matches exactly but one names your TV's model and codename, SideSpeed suggests it: **Use this list** or **Not my TV**, and remembers your answer. TVs with neither show "No list for this TV yet."
- **Home screen:** on Google TV and Android TV, pick an installed home screen app as Home. SideSpeed remembers the home screen the TV had first, and **Back to stock** returns to it. If no other home screen app is installed, install another home screen app first, then come back here. While Google TV's own home screen is on, it keeps that place: turn it off in **Turn off extras** first. On Fire TV this card only says Fire TV doesn't allow other home screens.

**Launch time.** On an app's compile screen, SideSpeed can time how long the app takes to open before and after compiling (it opens the app briefly, three times each side, and shows the median), for example `1.9 s → 1.1 s (42% faster)`. **Measure** re-checks any app: it resets the app's compiled code, times it, compiles it back and times it again. The result is kept per app version; after an update it is marked as from an older version.

## SideSpeed Companion

Companion apps for Android phones and for Windows, macOS and Linux. They start the SideSpeed helper on your Fire TV for you, so you do not need a PC and a terminal each time. On Google TV and other Android TVs, SideSpeed handles setup itself; you can still add those TVs to see what they are compiling.

**Install:** on a phone, open **[sidespeed.app/phone](https://sidespeed.app/phone)**. On a computer, open **[sidespeed.app](https://sidespeed.app)**, which offers the right installer for your system. Every file is also on the [Releases page](https://github.com/DevJardins/SideSpeed/releases). The desktop installers are unsigned:

- **Android:** install `SideSpeed-Companion.apk` on your phone. It is signed like the TV APK.
- **macOS:** open the `.dmg` (Apple silicon) and drag the app to Applications. If macOS says it cannot verify the developer, right-click the app, choose **Open**, then **Open** again.
- **Windows:** run the `.msi`. If SmartScreen blocks it, choose **More info**, then **Run anyway**.
- **Linux:** install the `.deb` or the `.rpm`.

**First use:** open the companion and choose your Trusted Network (**Trust this network**). Then **Add a TV**: type its address or use **Find TVs**, and tick **Always allow** when the TV asks. A TV with Wireless debugging pairs once with a code and then uses an encrypted connection. A TV that can't still connects the older way after you agree to it: anyone on your network could see or change the commands it sends. Before any other command, each TV proves it is the one you added. A TV you added before that check existed waits until you press **Confirm** on Home; the Companion checks it is the same TV, then leaves the secret on it.

**Trusted Networks:** you choose which networks the companion may act on. On any other network it does nothing and sends nothing. It needs no location permission. A network is recognised by its router's address, subnet and DNS servers, and by the router's hardware address once the companion has read it. Without the hardware address (always on Android, and often on a Mac), the DNS servers must be exactly the ones you saved. [SECURITY.md](SECURITY.md) explains the limits.

**Tailscale (desktop only).** Adding a TV by its Tailscale address trusts your own tailnet automatically, and the TV is kept alive wherever Tailscale is connected. TVs shared in from another tailnet are refused. **Settings > Tailnets** lists your tailnets, and removing a tailnet removes its TVs. Anyone who administers the tailnet can reach the TV. The Android companion has no Tailscale support.

**Live status:** while the companion's home screen is open, each TV card shows what SideSpeed is compiling right now, how many apps are waiting, and the last result. The TV needs SideSpeed 0.4.0 or later.

**TV card borders** show how the last check reached the TV: green on a Trusted Network, blue over Tailscale, red when it could not be reached. The border is orange, with "Waiting for you", while it waits for you to confirm it, pair it or allow the older connection. There is no colour when the TV cannot be checked from here (its saved status is shown as "Last seen") or when it has not been checked yet.

**Limits:** IPv6-only networks are never trusted. Find TVs sweeps the Trusted Network's own subnet (a large network shrinks to the /16 around this device or the gateway). You can also type a range from /16 to /30 on the same network instead.

**Back on Android:** the phone's Back button goes up one screen. On Home it leaves the app.

**Keeping the Companion up to date:** its About screen shows the installed version and checks for updates when it opens, or when you press **Check for updates**. On Android, **Update now** downloads the new APK, checks it, and installs it. On desktop, **Download** gets the installer for your system.

## Languages

SideSpeed and the Companion are in English. Both have a **Language** setting (**About** on the TV, **Settings** in the Companion): **Same as device** or a language. A language ships once at least 70% of its text is translated. If a language you picked isn't complete in a version, the app says so and shows English. To help translate, see [docs/TRANSLATING.md](docs/TRANSLATING.md).

## Requirements and caveats

- ADB debugging (Fire TV) or Wireless debugging (Google TV) must stay on.
- Enabling it exposes ADB on your local network. Only use it on a network you trust; the app asks you to acknowledge this first. [SECURITY.md](SECURITY.md) explains what SideSpeed protects and its known limits.
- Restarting the TV, turning ADB off, or updating SideSpeed stops the helper (or leaves it out of date) and you must start it again. The onn turns Wireless debugging off after a reboot.
- `speed` compilation adds tens of MB of storage per app.
- A compile can take several minutes for large apps (about 3 min on a Fire TV Cube).

## Tested devices

| Device | Status |
|---|---|
| Fire TV Cube Gen 3, Fire OS 7.7.1.7 | Helper started from PC, compile verified |
| onn 4K Pro, Android 14 | Wireless debugging on + "Always allow" (classic connection on port 5555), helper auto-start and compile verified (no PC needed). Pairing with a code and the encrypted connection verified with the macOS companion |
| Other Android TV, Google TV and Fire TV devices | Should work |
| Android companion app with the Fire TV Cube Gen 3 | First run, add by address, and restarting an out-of-date helper verified over the LAN |
| macOS companion app with the onn 4K Pro | Adding, checks and card borders verified over the LAN and over Tailscale (the onn starts its own helper) |
| `.msi`, `.deb` and `.rpm` | Built by CI but not tested yet |

## Building

JDK 21 and the Android SDK. CI runs these checks:

```
./gradlew :shared:allTests :companion:allTests :app:testDebugUnitTest :companion-desktop:test
./gradlew -p build-logic test
./gradlew checkTranslations
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

1. Merge the open Weblate translation pull requests whose checks pass.
2. Bump `sidespeed.versionName` in `gradle.properties`. The Android `versionCode`s (TV and companion, `major*10000 + minor*100 + patch`) and the desktop package version (`(major+1).minor.patch`) derive from it automatically — nothing else needs a hand bump.
3. Write the release notes in `.github/release-notes/v<version>.md`. List the languages included, and any that show some English, from the language check in the build output.
4. Merge to `main`, then tag and push: `git tag v<version> && git push origin v<version>`.
5. CI builds everything and creates a **draft** release. It fails if either APK is not signed with the project key. The draft has versioned files, `SHA256SUMS.txt`, and copies under fixed names: `SideSpeed.apk`, `SideSpeed-Companion.apk`, `SideSpeed-Companion-macos-arm64.dmg`, `-windows-x64.msi`, `-linux-amd64.deb` and `-linux-x86_64.rpm`.
6. Check the draft, then publish it.
7. Update sidespeed.app (its changes page and What's new) and this README.

The Downloader code 3503400 and the sidespeed.app links (`/tv`, `/companion.apk`, `/mac`, `/windows`, `/linux`, `/linux-rpm`) fetch `releases/latest/download/<fixed name>`. So every release must include all of these files under exactly these names.

## Support

SideSpeed is free. If it made your TV faster and you want to say thanks, you can [buy me a coffee on Ko-fi](https://ko-fi.com/devjardins).

## License

GPLv3, see `LICENSE`. Third-party attributions are in `NOTICE`.

## Logo and fonts

The SideSpeed logo and icons are a modified version of the Android robot. The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License (https://creativecommons.org/licenses/by/3.0/).

Brand sources and rules live in `docs/brand/ATTRIBUTION.md`. The banner wordmark uses Poppins SemiBold under the SIL Open Font License (`docs/brand/fonts/OFL.txt`).
