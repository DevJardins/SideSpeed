# Companion UI Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the shared companion UI feel polished (feedback, motion, depth, status) without changing behaviour or the calm palette.

**Architecture:** All UI is in `:companion` commonMain (`companion/.../ui/`). Upgrade the `Ss*` components in `Theme.kt` in place using Compose animation APIs (no Material), then rebuild the screens on them. Add a `TvStatus` to the card state so the status dot is data-driven and testable.

**Tech Stack:** Compose Multiplatform 1.12.2 (foundation + animation only), Kotlin 2.4.21.

**Spec:** `docs/superpowers/specs/2026-11-15-companion-ui-polish-design.md`

## Global Constraints

- Palette only from `SsTokens` via `Ss.*`; new tints are alpha or lerp of those colours.
- No Material 3 dependency; no behaviour, store, upkeep or `Copy` string changes.
- Motion 120-250 ms; desktop window 420x640 must fit every screen.
- Keep every control focusable with a visible focus ring and reachable by D-pad/keyboard (Android TV support is the next project, so don't regress remote navigation).
- Existing tests keep passing: `:companion:desktopTest :companion-android:testDebugUnitTest`.

## Review Focus

- A TV with a very long name or address must wrap or ellipsize inside the 420 px card, not overflow.
- Zero TVs shows an empty state ("No TVs yet" plus a pointer to Add a TV), not a blank screen.
- Unknown or failed statuses (`UNKNOWN`, `START_FAILED`, `WRONG_DEVICE`) never show the green "running" dot.
- Restart spinner appears only on the tapped card and always stops, including when the restart fails.
- Rapid double-tap on a button does not fire twice during its press animation.

---

### Task 1: Status model for the dot

**Files:**
- Modify: `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/CompanionViewModel.kt` (`TvCardState`, `refresh`)
- Create: `companion/src/commonMain/kotlin/io/github/devjardins/sidespeed/companion/ui/StatusKind.kt`
- Test: `companion/src/commonTest/kotlin/io/github/devjardins/sidespeed/companion/ui/StatusKindTest.kt`

**Interfaces:**
- Produces: `enum class StatusKind { GOOD, WAITING, BAD, UNKNOWN }`; `fun statusKind(s: TvStatus): StatusKind`; `TvCardState.status: TvStatus` (new field, set from `tv.status`).

- [ ] **Step 1:** Write `StatusKindTest`: `RUNNING -> GOOD`; `WAITING_FOR_ALLOW -> WAITING`; `NOT_RUNNING, OFFLINE, NOT_INSTALLED, WRONG_DEVICE, START_FAILED -> BAD`; `UNKNOWN -> UNKNOWN`; and a loop asserting only `RUNNING` maps to `GOOD`.
- [ ] **Step 2:** Run `./gradlew :companion:desktopTest --tests '*StatusKindTest*'`; expect FAIL (unresolved reference).
- [ ] **Step 3:** Implement `statusKind` as an exhaustive `when`; add `status: TvStatus` to `TvCardState` and fill it in `refresh()`. Fix any `TvCardState(...)` constructions in tests.
- [ ] **Step 4:** Run `./gradlew :companion:desktopTest`; expect PASS.
- [ ] **Step 5:** Commit `Add TvStatus to card state and a status-kind mapping`.

### Task 2: Interactive components

**Files:**
- Modify: `companion/.../ui/Theme.kt`
- Create: `companion/.../ui/Components.kt`

**Interfaces:**
- Produces (all `@Composable`, in package `...companion.ui`):
  - `SsButton(label, onClick, primary = false, selected = false, enabled = true, modifier)` (existing signature plus `enabled`), now with press scale 0.97, hover/focus colour, and 300 ms click debounce.
  - `SsCard(modifier, content: @Composable ColumnScope.() -> Unit)`: surface fill, 1 dp border at 8% text alpha, 16 dp corners, 16 dp padding.
  - `SsIconButton(onClick, contentDescription, content: @Composable () -> Unit)`: 40 dp round hit target with the same feedback.
  - `SsSwitchRow(label, checked, onChange)`: label left, animated pill switch right.
  - `SsSegmented(options: List<String>, selectedIndex, onSelect)`: sliding selected pill.
  - `SsStatusDot(kind: StatusKind, pulsing: Boolean)`: 10 dp dot; GOOD = `Ss.okText`, WAITING = `Ss.warnText`, BAD = `Ss.dangerText`, UNKNOWN = `Ss.textSecondary`; pulses alpha when `pulsing`.
  - `SsSpinner(size: Dp = 16.dp)`: rotating arc in `Ss.accent`.
  - `SsLogoMark(size: Dp)`: sage rounded square with a simple lightning/arrow glyph drawn on a `Canvas` (no image resource).

- [ ] **Step 1:** Implement the components with `animateColorAsState`, `animateFloatAsState`, `graphicsLayer` scale, `collectIsPressedAsState`/`collectIsHoveredAsState` on a shared `MutableInteractionSource`, and `rememberInfiniteTransition` for the spinner and pulse. Keep focus-ring behaviour from the current `SsButton`.
- [ ] **Step 2:** Run `./gradlew :companion:compileKotlinDesktop :companion-android:assembleDebug`; expect BUILD SUCCESSFUL.
- [ ] **Step 3:** Run `./gradlew :companion:desktopTest`; expect PASS (no behaviour change).
- [ ] **Step 4:** Commit `Add polished interactive components`.

### Task 3: Screens and transitions

**Files:**
- Modify: `companion/.../ui/{HomeScreen,SettingsScreen,AddTvScreen,FirstRunScreen,Navigation}.kt`

**Interfaces:**
- Consumes: Task 2 components, `TvCardState.status`, `statusKind`.
- Produces: unchanged public screen signatures (callers in `Navigation.kt`, `Main.kt`, `MainActivity.kt` keep compiling).

- [ ] **Step 1 (Navigation):** Wrap the page switch in `AnimatedContent` (fade 180 ms plus 16 dp horizontal slide; going back reverses). Give About a header with a back icon button instead of the text Back button.
- [ ] **Step 2 (Home):** Header row: `SsLogoMark(28.dp)`, "SideSpeed" title, `SsIconButton` for Settings at the end. Trusted-network banner becomes a rounded pill with a status dot. Each TV is an `SsCard`: `SsStatusDot(statusKind(tv.status), pulsing = restarting)`, name (ellipsized, 1 line), address caption, `statusLabel`, "Checked N min ago", and Restart now. Track `restartingId: String?` locally; Restart now shows `SsSpinner` and is disabled while `state.busy && restartingId == tv.id`; when busy ends with status `RUNNING`, show "Restarted ✓" for 2 s. Empty state: centered card "No TVs yet" with a primary "Add a TV" button. Add a floating-style primary "Add a TV" button at the bottom when TVs exist.
- [ ] **Step 3 (Settings):** Group into `SsCard`s: Trusted Networks (rows with Remove), Notifications (`SsSwitchRow`), Check every (`SsSegmented` of "15 minutes", "1 hour"), desktop only `SsSwitchRow("Start when I log in")`, About row. Back becomes an icon button in the header.
- [ ] **Step 4 (Add TV, First run):** Text field in an `SsCard` with an animated accent border on focus; found hosts as tappable cards; First run gets the logo mark, larger title and a centered layout.
- [ ] **Step 5:** Run `./gradlew :companion:desktopTest :companion-android:testDebugUnitTest :companion-desktop:compileKotlin :companion-android:assembleDebug`; expect PASS.
- [ ] **Step 6:** Run the desktop app (`./gradlew :companion-desktop:run`), screenshot Home (with and without a TV), Add, Settings, About and First run at 420x640; check nothing overflows and the long-name case (temporarily seed a long name in the store) ellipsizes. Fix anything off.
- [ ] **Step 7:** Commit `Polish companion screens and add transitions`.

### Task 4: App icons and release check

**Files:**
- Create: `companion-desktop/src/main/resources/icons/{app.png,app.icns,app.ico}` (generated from `docs/brand/sidespeed-logo.webp` or the logo mark)
- Modify: `companion-desktop/build.gradle.kts` (`nativeDistributions`: `macOS { iconFile }`, `windows { iconFile }`, `linux { iconFile }`), `Main.kt` window `icon`
- Modify: `docs/spike/M2-results.md` (UI polish note)

- [ ] **Step 1:** Generate a 1024 px square PNG of the mark; build `.icns` with `sips`+`iconutil` and `.ico` (multi-size) with a script kept out of the repo; commit only the outputs.
- [ ] **Step 2:** Wire the icon files in Gradle and the window icon; run `./gradlew :companion-desktop:createDistributable`; launch it and confirm the Dock icon is no longer the default.
- [ ] **Step 3:** Rebuild the Android debug APK, copy it to `docs/spike/companion-android-debug.apk`, and ask the owner to check Home, Settings and the restart flow on the phone.
- [ ] **Step 4:** Run the full suite `./gradlew :shared:allTests :companion:allTests :companion-desktop:test :companion-android:testDebugUnitTest :app:testDebugUnitTest`; expect PASS.
- [ ] **Step 5:** Commit `Add companion app icons`.
