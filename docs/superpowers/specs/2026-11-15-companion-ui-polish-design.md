# Companion UI polish: design

## Intent
The companion apps (Android and desktop, one shared Compose UI in `:companion`) look flat. The owner wants LocalSend-level polish so the app feels premium and people keep using it. The calm palette (dark navy, sage accent, from `SsTokens`) stays.

## Goals
- Visible feedback on every interaction: press and hover states on buttons and cards, animated selection.
- Smooth screen transitions between Home, Add a TV, Settings and About.
- Depth and hierarchy: soft cards with subtle borders, larger rounding, a header with the logo and a settings icon, clearer spacing.
- Status you can read at a glance: a status dot per TV (green running, amber checking, grey offline), a spinner while restarting, a brief "Restarted" confirmation.
- Proper icons: a real desktop app icon from the existing logo; Android launcher icon checked.

## Non-goals
- No new palette, no light theme, no bottom nav or dashboard redesign, no illustrations.
- No change to behaviour, copy (`Copy` strings stay), store, upkeep or ADB logic.
- No Material 3 dependency.

## Approaches considered
- A. Polish the existing `Ss*` components in place (chosen by the owner).
- B. Adopt Material 3 controls: rejected, generic look and extra dependency weight.
- C. Full redesign with navigation changes: rejected, larger and changes how the app is used.

## Design
- Components (`Theme.kt`): `SsButton` gains press-scale and hover/focus colour via animated state; add `SsCard`, `SsIconButton`, `SsSwitchRow` (replaces the On/Off button for toggles), `SsStatusDot`, `SsSpinner`. Android gets a ripple-like press highlight through the same animated state.
- `Navigation.kt`: `AnimatedContent` with a short fade plus slight slide between pages.
- `HomeScreen`: header (logo, title, settings icon); TV cards with status dot, name, address, "Checked N min ago", and Restart now with a spinner while busy.
- Settings: grouped cards; toggles as switches; interval as a segmented control.
- Desktop: icon files wired into `nativeDistributions` (icns, ico, png).
- Motion stays short (about 120-250 ms) and respects reduced motion where the platform exposes it.

## Constraints
- All UI stays in `commonMain`; no behaviour changes, so existing tests must keep passing.
- Only `SsTokens` colours; any new tint is derived from them.
- Window size 420x640 on desktop must still fit every screen.

## Left to the builder
Exact easing, sizes and the icon drawing approach; asset conversion for the desktop icons.

## Verification
Desktop: run the packaged app and screenshot each screen. Android: build debug and check on the owner's phone. Run `:companion:desktopTest :companion-android:testDebugUnitTest`.
