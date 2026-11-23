# AGENTS.md

Read this before any change. Every rule below is true of the current code;
each cites the file or class that proves it. If a rule and the code disagree,
the code wins: say so and do not follow the rule.

## 1. Project map

- `app/` is the Android TV app.
  Entry points: `app/src/main/java/io/github/devjardins/sidespeed/MainActivity.kt`
  (navigation and screens) and `SideSpeedApp.kt` (own DI graph in `AppContainer.kt`).
- `shared/` is toolkit-free Kotlin shared by all targets:
  theme tokens in `shared/.../theme/SsTokens.kt`,
  helper wire protocol in `shared/.../helper/HelperProtocol.kt`.
- `companion/` is the shared Compose Multiplatform companion UI
  (`companion/src/commonMain/.../ui/`):
  `Navigation.kt` routes, `CompanionViewModel.kt` state, `Components.kt` widgets.
- `companion-android/` is the thin Android wrapper (same version as the TV app).
  `companion-desktop/` is the desktop app (`MainKt`, jpackage installers).
- The helper is a shell-uid (`uid 2000`) process started from ADB via `app_process`
  (`app/.../helper/HelperMain.kt`).
  It listens on 127.0.0.1 only, first free port of
  `HELPER_PORT_FIRST..HELPER_PORT_LAST`, published in a port file.
- The helper runs one fixed request set, defined by `helper/CommandAllowlist.kt`.
  Client side: `helper/HelperClient.kt`.
  Connection handling: `helper/HelperServer.kt`.

## 2. UI copy

- Write plain, honest English with no jargon.
- Use short sentences.
- Use sentence case.
- Say what is true and nothing more.
- Never name third-party launchers anywhere:
  not in UI copy, README, site, or notes.
- When no other home screen app is installed, the whole message is
  `ui/tools/HomeCard.kt`'s line:
  "Install another home screen app first, then come back here."
- On Fire OS the Home card shows only its own line
  (`ui/tools/HomeCard.kt`):
  "Fire TV doesn't allow other home screens."
  No switching is offered there.
- Never tell people what to install.
  No recommendations, no browse lists of apps they do not have.
- SideSpeed tells the user what is out of date; it does not install other apps
  (see "Updates for sideloaded apps" in `README.md`).
- The feature is "Trusted Network", never "home network".
  See `companion/.../ui/FirstRunScreen.kt`, `SettingsScreen.kt`, `HomeScreen.kt`.
- State caveats plainly, next to the choice they qualify.
- Setup warns that ADB debugging must stay on and that anyone
  on the network could ask the TV for access (`ui/setup/SetupScreen.kt`).
- The animation speed card says "It does not make apps load faster"
  (`ui/tools/AnimationsCard.kt`).
- Companion UI copy lives in the `Copy` object
  (`companion/.../ui/CompanionViewModel.kt`); keep strings there, not scattered.
- TV copy sits with its screen; keep each string next to the control it explains.

## 3. TV focus and D-pad

- Every control is focusable with a visible focus ring.
- Every control is reachable by D-pad or keyboard.
- The ring is `SsDimens.FocusRing` (2dp, sage), applied in `ui/theme/Components.kt`.
- Never regress remote navigation: no pointer-only gestures, no click-only rows.
- Returning from a screen restores focus where you were.
- `ui/apps/AppsScreen.kt` scrolls to the opened row and focuses it
  when coming back from Compile; first open focuses the active filter.
  (See commit "Put All first in My Apps and return focus where you were (#42)".)
- OK (center press) on an app row opens the Compile screen
  (`AppsScreen.kt` `onSelect` into `CompileScreen` in `MainActivity.kt`).
- Checkboxes never take first focus: the My Apps keep-boxes (`KeepBoxButton`)
  carry no `FocusRequester`, so initial focus lands on the filter or the row.
- D-pad right from a row reaches its checkbox by layout order (`AppsScreen.kt`).
- Number pads stay hidden unless asked for.
- The D-pad grid `ui/setup/PairingCodeGrid.kt` takes initial focus
  only when `focusFirst = true`.
- The pairing code passes `focusFirst = true`; the port field does not
  (`ui/setup/SetupScreen.kt`).

## 4. Visual rules

- All colours come from `SsTokens` (`shared/.../theme/SsTokens.kt`).
- The TV mirrors them in `ui/theme/Theme.kt` (`SsColors`).
- The companion mirrors them in `companion/.../ui/Theme.kt` (`Ss`).
- `textPrimary` is `#EEF1F5`. Pure white is never used.
- No bright fills and no lime.
- Pills and cards use the muted `*_PILL_BG` / `*_CARD_BG` tokens.
- Focus is a lighter surface (`FOCUS_BUTTON #2A3644` over `BUTTON #1A222E`).
- Focus adds the 2dp sage ring (`ACCENT #93BFA2`);
  see `SsButton` in `ui/theme/Components.kt`.
- Reuse the existing `Ss*` components (`SsButton`, `SsCard`, `SsRow`,
  `SsPill`, `SsSwitchRow`, `SsSegmented` in `companion/.../ui/Components.kt`
  and their TV twins in `app/.../ui/theme/Components.kt`).
- The companion app uses no Material libraries
  (plain `BasicText` and custom widgets); do not add any.

## 5. Helper and security

- The helper answers only SideSpeed's uid.
- `HelperServer.kt` checks the caller against the allowed uid
  (via `/proc/net/tcp{,6}` in `HelperMain.kt`) before reading any request byte,
  and closes anything else.
- It starts only when SideSpeed's uid resolves; with no uid it exits.
- The helper runs only `CommandAllowlist.kt` commands
  as a fixed argv via `ProcessBuilder`.
- It never runs `sh -c` and never passes unvalidated input to a shell.
- Unknown requests are refused with `EXIT_REFUSED` and logged
  (`"REFUSED ..."` in `HelperServer.kt`).
- Refusals are the contract: uid mismatch, non-loopback peer,
  overlong or non-ASCII request, wrong tokens, unvalidated package
  or class names, and short install bodies all fail closed.
- `install-self` accepts at most `MAX_INSTALL_BYTES` (64 MiB).
- `install-self` installs only `INSTALL_SELF_PACKAGE`.
- Update installs verify before applying (`app/.../update/UpdateVerify.kt`):
  checksum against the release `SHA256SUMS` first, then package name,
  then version, then exact signer-set equality with the installed app.
- Reject on any mismatch.
- Tools writes are conservative too: disable is only
  `pm disable-user --user 0 <pkg>` and restore is only `pm enable <pkg>`;
  there is no uninstall form in `CommandAllowlist.kt`.
- Back to stock only ever touches the recorded original Home
  (`app/.../tools/HomeScreen.kt`).
- Never widen helper powers (new commands, looser validation,
  weaker install checks) without the owner's explicit decision.
- New forms are exact-token allowlist entries with tests,
  as in `CommandAllowlist.kt`.

## 6. Versions and releases

- Bump only `sidespeed.versionName` in `gradle.properties`.
- The Android `versionCode`s (TV and companion,
  `major*10000 + minor*100 + patch`) derive from it in the root `build.gradle.kts`.
- The desktop package version (`(major+1).minor.patch`) derives from it
  in `companion-desktop/build.gradle.kts`.
- The app itself reports the plain version via generated `CompanionVersion`.
- Write release notes in `.github/release-notes/vX.Y.Z.md`.
- Pushing a `v*` tag makes CI (`.github/workflows/build.yml`) build everything
  and create a draft release.
- The draft carries versioned files, `SHA256SUMS.txt`, and fixed-name copies
  (`SideSpeed.apk`, `SideSpeed-Companion.apk`, `.dmg`, `.msi`, `.deb`, `.rpm`)
  for the Downloader code and sidespeed.app links.
- Publishing the draft is manual, by the owner.
- Every release also updates sidespeed.app (separate repo:
  `/tv`, `/companion.apk`, `/mac`, `/windows`, `/linux` links and changelog).
- Every release also updates the `README.md` feature and release sections.

## 7. Workflow

- Read the linked spec or plan file first; plans name their spec at the top.
- Then test-driven development: write the failing test, watch it fail,
  implement, watch it pass.
- Run one Gradle build at a time, with the SDK exported first:
  `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
  (it may be unset in a fresh shell).
- Test tasks (also what CI runs):
  `./gradlew :shared:allTests :companion:allTests :app:testDebugUnitTest :companion-desktop:test`
- Release builds per module (see "Building" in `README.md`):
  `./gradlew :app:assembleRelease`
  `./gradlew :companion-android:assembleRelease`
  `./gradlew :companion-desktop:packageDmg :companion-desktop:packageMsi :companion-desktop:packageDeb :companion-desktop:packageRpm`
- The emulator binary is
  `/opt/homebrew/share/android-commandlinetools/emulator/emulator`
  (not on PATH).
- AVDs: `sidespeed-tv` (Android 34, TV) and `sidespeed-api28` (Android 28).
- Animation and trim-caches runs go on the emulator, never on real hardware.
- Commit messages are plain sentences
  with no `Co-authored-by` or other trailers.
- Never commit planning notes (`HANDOFF.md`, `SPEC.md`, `MUSE_TASK.md`,
  `docs/superpowers/`, `tools/`).
- Never commit key material (`*.jks`, `*.keystore`, `keystore.properties`,
  passwords); keystores are gitignored and only the owner handles signing.
- One PR per task, against `main`.
- Never merge, tag, or publish: opening the PR is where agent work stops,
  and releases are the owner's calls.
