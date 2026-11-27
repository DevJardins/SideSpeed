# SideSpeed

<img src="docs/brand/sidespeed-logo-2026.png" alt="SideSpeed logo" width="320">

<!-- discord-guide:start -->
SideSpeed makes sideloaded apps on Android TV and Fire TV start and run faster. Store apps are compiled to native code. Sideloaded apps are not. SideSpeed compiles them and keeps them compiled after updates. It is free.

Website and downloads: **[sidespeed.app](https://sidespeed.app)**

## Install

1. On the TV, open **Downloader** and enter **3503400**. Or open **sidespeed.app/tv** in the TV's browser.
2. Choose **Install**. If asked, allow Downloader to install unknown apps.
3. Open SideSpeed from the home screen.

Fire TV Sticks with Vega OS can't run Android apps, so SideSpeed doesn't work on them.

## Set up

SideSpeed needs a small helper running on the TV.

**Before you start:** ADB debugging must stay on, and anyone on your network could ask the TV for access. Only do this on a network you trust.

### Google TV and Android TV

1. Go to **Settings > System > Developer options** and turn on **Wireless debugging**.
2. Open SideSpeed and press **I understand**.
3. When the TV asks, tick **Always allow** and press **Allow**.

SideSpeed starts the helper and shows your apps.

Can't connect? Choose **Pair with a code instead**. Enter the code and port from **Pair device with pairing code**.

### Fire TV

Fire TV can't start the helper itself. SideSpeed Companion starts it from your phone or computer.

1. Go to **Settings > My Fire TV > Developer options** and turn on **ADB debugging**.
2. Open SideSpeed and press **I understand**.
3. Get the Companion at **[sidespeed.app/phone](https://sidespeed.app/phone)** or **[sidespeed.app](https://sidespeed.app)**.
4. In the Companion, press **Trust this network**, then **Add a TV**, and pick your Fire TV.
5. Fire TV can't use an encrypted connection, so anyone on your network could see or change the commands sent to it. To go on, tick **I understand. Connect the older way.** and pick the TV again.
6. When the TV asks, tick **Always allow** and press **Allow**.
7. Open SideSpeed again. It shows your apps.
<!-- discord-guide:end -->

## When the helper stops

Restarting the TV, turning ADB off, or updating SideSpeed stops the helper.

- **Google TV:** open SideSpeed. Or turn on **After a restart** in **Tools** (Android 11+ on Wi-Fi) so the helper starts on its own after a reboot.
- **Fire TV:** open the Companion.
- **onn:** it turns Wireless debugging off after a reboot. Turn it back on first, or turn on **After a restart** in **Tools**.

## Keeping apps optimized

An app update throws away its compiled code. SideSpeed compiles it again, one app at a time.

- **My Apps:** tick an app to keep it optimized. Or open the app and choose **Keep this app optimized**. Shows **All** or **Sideloaded** apps.
- **Auto-optimize:** keeps a group of apps optimized without ticking each one.
  - **All apps:** every app you installed. Preinstalled apps only if you pick them.
  - **Sideloaded only:** apps not from a store, plus any you pick.
  - **Selected apps:** only the apps you ticked.
- **History:** the last 20 automatic compiles, on the Auto-optimize screen.
- **Mode:** **speed** (default) or **speed-profile**, on an app's screen. Remembered per app.
- **Launch time:** times how long an app takes to open, before and after compiling. For example `1.9 s → 1.1 s (42% faster)`. **Measure** checks any app again.

Work waits while the helper is down and picks up when it's back. SideSpeed also compiles itself the next time you leave it while the helper runs.

## Updates for sideloaded apps

- Once a day, SideSpeed downloads one list of app versions and compares it on the TV. Your app list never leaves the TV.
- An out-of-date app shows **Update:** in My Apps. Its screen shows a QR code and short link to get it.
- Only versions that are certainly newer show. Betas and test builds never do.
- **Update now** appears when the list has a download for your TV. SideSpeed checks it's the same app, a newer version, and signed with the same key, then installs it. Your data in the app stays.
- SideSpeed only updates apps you already have, one press at a time. It never installs new apps.

The list (`sidespeed.app/updates.json`) is built nightly from a short hand-picked list plus the F-Droid and IzzyOnDroid catalogs.

## Tools

- **Box health:** device name, Android version, memory, storage, and whether the helper is running. When **After a restart** is on, it also shows the result of the last restart.
- **After a restart** (Google TV and Android TV 11+ on Wi-Fi): starts the helper again on its own after the TV restarts. Off by default. Uses permission to change system settings and to see which Wi-Fi network the TV is on, strictly to turn wireless debugging back on for networks you allowed. It never tracks where you are or sends network details anywhere. Turn it off to take both permissions back.
- **Free up space:** **Clean up now** clears app caches. Apps keep their data but may download artwork again. **Automatic cleanup** keeps 500 MB, 1 GB or 2 GB free, checking every few hours while the helper runs. Off by default.
- **Animations:** Normal, Faster or Off. Menus move faster. It does not make apps load faster. **Restore original** puts your old values back. On Fire TV, app animations stay normal so Settings keeps working.
- **Turn off extras:** disables (never uninstalls) apps your TV maker added, from a list for your exact model. **Turn on selected** and **Restore everything** undo it. If only a close match exists, SideSpeed asks: **Use this list** or **Not my TV**. Lists come from [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT).
- **Home screen** (Google TV and Android TV): pick another installed home screen app. **Back to stock** returns to the original. Google TV's own home screen must be turned off in **Turn off extras** first. On Fire TV, this card only says Fire TV doesn't allow other home screens.

## Updating SideSpeed

- When a new version is out, press **Update now** on the Home screen. Nothing installs until you press it.
- SideSpeed checks every 6 hours. To check now, go to **About > Check for updates**.
- Before installing, it checks the checksum, package name, signing key and version.
- With the helper running, it installs on its own. Without it, Android asks you to confirm.
- Your settings are kept.
- Updating stops the helper. See [When the helper stops](#when-the-helper-stops).

**Other ways to install:** download `SideSpeed.apk` from the [Releases page](https://github.com/DevJardins/SideSpeed/releases) and install it with Downloader, LocalSend or `adb install`. Release APKs are signed with the project key.

**Older versions:** **Update now** needs SideSpeed 0.5.0 or later, or 0.5.4 or later on Fire TV and other Android 9 boxes. On older versions, update once with Downloader.

## SideSpeed Companion

For Android phones, Windows, macOS and Linux. It starts the helper on Fire TV, so you don't need a PC. You can add Google TVs too, to see what they're compiling.

### Install

Get it at **[sidespeed.app/phone](https://sidespeed.app/phone)** (phone) or **[sidespeed.app](https://sidespeed.app)** (computer). Every file is also on the [Releases page](https://github.com/DevJardins/SideSpeed/releases).

- **Android:** install `SideSpeed-Companion.apk`. It is signed like the TV app.
- **macOS (Apple silicon):** open the `.dmg` and drag the app to Applications.
- **Windows:** run the `.msi`.
- **Linux:** install the `.deb` or the `.rpm`.

The desktop installers are unsigned:

- **macOS:** if it says it can't verify the developer, right-click the app, choose **Open**, then **Open** again.
- **Windows:** if SmartScreen blocks it, choose **More info**, then **Run anyway**.

### First use

1. Press **Trust this network**.
2. Press **Add a TV**. Type its address or use **Find TVs**.
3. When the TV asks, tick **Always allow**.

- TVs with Wireless debugging pair once with a code, then use an encrypted connection.
- Other TVs connect the older way, only after you agree. Anyone on your network could see or change the commands sent.
- Before any other command, each TV proves it's the one you added. A TV added before this check waits until you press **Confirm** on Home.

### Trusted Networks

- The Companion only acts on networks you trust. Anywhere else, it does nothing and sends nothing.
- It needs no location permission.
- A network is recognised by its router's address, subnet and DNS servers, plus the router's hardware address once read.
- Without the hardware address (always on Android, often on a Mac), the DNS servers must match exactly.
- IPv6-only networks are never trusted.
- [SECURITY.md](SECURITY.md) explains the limits.

### Tailscale (desktop only)

- Adding a TV by its Tailscale address trusts your own tailnet. The TV is kept running wherever Tailscale is connected.
- TVs shared from another tailnet are refused.
- **Settings > Tailnets** lists your tailnets. Removing one removes its TVs.
- Anyone who administers the tailnet can reach the TV.

### TV cards

- **Live status** (TV on 0.4.0 or later): what's compiling, how many apps wait, and the last result.
- **Border colour:**
  - Green: reached on a Trusted Network.
  - Blue: reached over Tailscale.
  - Red: couldn't be reached.
  - Orange, "Waiting for you": confirm it, pair it, or allow the older connection.
  - None: can't be checked from here ("Last seen"), or not checked yet.

### Good to know

- **Find TVs** searches the Trusted Network's subnet, up to a /16. You can also type a range from /16 to /30.
- On Android, Back goes up one screen. On Home, it leaves the app.
- **About** checks for Companion updates. On Android, **Update now** installs it. On desktop, **Download** gets the installer.

## Languages

- **TV app:** English and Arabic.
- **Companion:** English and Arabic.
- Pick one in **About** on the TV, or **Settings** in the Companion.
- A language ships once at least 70% of it is translated.
- If your language isn't complete in a version, the app says so and shows English.
- To help translate, see [sidespeed.app/translate](https://sidespeed.app/translate/). The details are in [docs/TRANSLATING.md](docs/TRANSLATING.md).

## Requirements and caveats

- ADB debugging (Fire TV) or Wireless debugging (Google TV) must stay on.
- That opens ADB on your local network. Only use it on a network you trust. [SECURITY.md](SECURITY.md) explains what SideSpeed protects and its limits.
- `speed` compiling adds tens of MB of storage per app.
- Large apps can take several minutes to compile (about 3 min on a Fire TV Cube).

## Tested devices

| Device | Status |
|---|---|
| Fire TV Cube Gen 3, Fire OS 7.7.1.7 | Helper started from PC, compile verified |
| onn 4K Pro, Android 14 | Helper starts on its own, compile verified. Pairing with a code verified with the macOS Companion |
| Android Companion with Fire TV Cube Gen 3 | First run, add by address, helper restart verified |
| macOS Companion with onn 4K Pro | Adding, checks and card borders verified on the LAN and over Tailscale |
| Other Android TV, Google TV and Fire TV devices | Should work |
| `.msi`, `.deb` and `.rpm` | Built by CI, not tested yet |

## Building

Needs JDK 21 and the Android SDK.

Tests (what CI runs):

```
./gradlew :shared:allTests :companion:allTests :app:testDebugUnitTest :companion-desktop:test
./gradlew -p build-logic test
./gradlew checkTranslations
```

Release builds:

```
./gradlew :app:assembleRelease
./gradlew :companion-android:assembleRelease
./gradlew :companion-desktop:packageDmg :companion-desktop:packageMsi :companion-desktop:packageDeb :companion-desktop:packageRpm
```

- Run the desktop app from source: `./gradlew :companion-desktop:run`
- Signed APKs: copy `keystore.properties.example` to `keystore.properties` and fill it in, or set the `SIDESPEED_*` environment variables. Without a keystore, release APKs stay unsigned.
- Releasing: see [docs/RELEASING.md](docs/RELEASING.md).

## Support

SideSpeed is free. To say thanks, you can [buy me a coffee on Ko-fi](https://ko-fi.com/devjardins).

## License

GPLv3, see `LICENSE`. Third-party attributions are in `NOTICE`.

## Logo and fonts

The SideSpeed logo and icons are a modified version of the Android robot. The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the Creative Commons 3.0 Attribution License (https://creativecommons.org/licenses/by/3.0/).

Brand sources and rules are in `docs/brand/ATTRIBUTION.md`. The banner wordmark uses Poppins SemiBold under the SIL Open Font License (`docs/brand/fonts/OFL.txt`).
