# M2 device verification

Date: 2026-11-15. Fire TV Cube Gen 3 (AFTGAZL) at 192.168.5.140.

| Check | Result |
|---|---|
| Mac + Cube: trust network (192.168.4.0/22), add by address | Pass. TV saved as RUNNING; no Allow prompt appeared on the TV |
| Mac: tray "Restart now" | Pass. Status RUNNING, last check 15 s after the click (read from the app's store; not independently confirmed on the TV) |
| Phone + Cube: first run, add by address | Pass. Connected |
| Phone: reboot TV, open companion | Pass. TV still listed (helper state not separately confirmed) |
| Phone: reboot, don't open, helper back within 15 min | Pending (owner running a 15-minute timer) |
| Untrusted network (hotspot) | Not run (no hotspot data available) |
| Stale helper: `adb install -r` of the same APK (install path changed), then open companion | Pass. Helper pid changed 4455 -> 8026, so it was restarted; phone showed "Helper running, checked 0 mins ago" (not the literal "Restarted" text) |

Notes:
- An "Allow debugging" prompt from `mdez@unknown` appeared on an onn streamer while testing. The companion only contacted 192.168.5.140, so the source is unidentified and probably another ADB client.
- The Mac window and tray were driven through accessibility; the tray click was done by the owner.

## UI polish (2026-11-15)
Press/hover/focus feedback, screen transitions, status dots, restart spinner, empty state, and app icons (mac/win/linux). Full suite green. Owner to eyeball the phone APK (`docs/spike/companion-android-debug.apk`) and the Dock icon.
