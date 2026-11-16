# M2 device verification

Date: 2026-11-15. Fire TV Cube Gen 3 (AFTGAZL) at 192.168.5.140.

| Check | Result |
|---|---|
| Mac + Cube: trust network (192.168.4.0/22), add by address | Pass. TV saved as RUNNING; no Allow prompt appeared on the TV |
| Mac: tray "Restart now" | Pass. Status RUNNING, last check 15 s after the click (read from the app's store; not independently confirmed on the TV) |
| Phone + Cube (Android APK, reboot tests) | Not run |
| Untrusted network (hotspot) | Not run |
| Stale helper (adb install -r, then open companion) | Not run |

Notes:
- An "Allow debugging" prompt from `mdez@unknown` appeared on an onn streamer while testing. The companion only contacted 192.168.5.140, so the source is unidentified and probably another ADB client.
- The Mac window and tray were driven through accessibility; the tray click was done by the owner.
