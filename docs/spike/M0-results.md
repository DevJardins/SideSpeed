# M0 results

Fill in one row per question. Use yes / no / n/a plus short notes.

| Question | Fire TV Cube | Onn 4K Pro | Notes |
|---|---|---|---|
| Can pairing be done from the TV alone? (pair to 127.0.0.1 or the Wi-Fi IP using the on-screen code) | | | |
| Is 5555 reachable (button 1)? | | | |
| Does a key trusted via "Always allow" / pairing work over TLS (button 3, then `id`)? | | | |
| After `pm grant` WRITE_SECURE_SETTINGS, does writing `adb_wifi_enabled` bring Wireless Debugging back after a reboot? | | | |
| Does toggling `adb_enabled` / `adb_wifi_enabled` off and on bring back a working connection (button 8)? Reconnect time (ms)? | | | |
| Did Wireless Debugging stay on after a reboot (Onn)? | | | |
| Does `pm compile -m speed -f com.nuvio.tv` work (or only the `cmd package compile` fallback)? | | | |
| Does `id` return `uid=2000(shell)`? | | | |
| `pm grant` self WRITE_SECURE_SETTINGS succeeded? | | | |
| `dumpsys package dexopt` captured (file names)? | | | |
| Model / Android / Fire OS version | | | |

## M0b: built-in helper (Part C)

| Question | Fire TV Cube | Onn 4K Pro | Notes |
|---|---|---|---|
| Which launch form kept the helper running after `adb shell` returned (primary with setsid / fallback 1 nohup only / fallback 2 interactive)? Does the device have `setsid`? | | | |
| Startup log: `uid=2000`? SideSpeed uid found (not `-1`)? | | | |
| Button 10 (start via own ADB) worked? (Onn) | n/a | | |
| Button 11: does the app reach the helper, reply `pong uid=2000`? (Any SELinux `avc` denial?) | | | |
| PC client (`--client ping` / `id`) works? | | | |
| Button 12: compile via helper works? Duration (ms)? | | | |
| Alive 30 s after `adb kill-server` (same pid)? | | | |
| Alive while ADB is off (`adboff 60`)? Ping during off answered? Heartbeat gaps? ADB came back by itself? | | | |
| Exits by itself after `adb install -r` (log line)? | | | |
| Gone after a reboot? | | | |

## Fire TV Cube Gen 3 — results recorded by the controller (2026-11-15)

Device: AFTGAZL, Fire OS 7.7.1.7 (Android 9, SDK 28).

| Question | Result |
|---|---|
| On-box classic ADB (spike button 1) | **Blocked.** adbd accepts the TCP connection, then closes it without AUTH. It answers the same CNXN from the Mac. Matches Amazon's local-ADB block (Fire OS 7.6.6.9+, CVE-2024-27350). |
| Helper start from PC (setsid + nohup) | Works. uid=2000, parent pid 1, keeps running after `adb shell` returns. `setsid` exists. |
| App → helper over abstract Unix socket | **Denied by SELinux** (`avc: denied { connectto } untrusted_app → shell unix_stream_socket`). |
| App uid → shell-uid listener over loopback TCP | Works (tested with run-as + toybox nc). |
| Compile com.nuvio.tv via helper | Success, **3 min 09 s**. |
| Helper survives `adb kill-server` (30 s) | Yes, same pid. |
| Helper survives ADB debugging switched off | **No.** Killed immediately (no heartbeat after the switch), so ADB was never restored; owner re-enabled it by hand. Feature dropped in design revision 3. |
| Helper exits when SideSpeed is updated | Yes, "self-exit: APK disappeared" ~15 s after `adb install -r`. |
| Helper gone after reboot | Not run (expected; low value). |

## Onn 4K Pro — results recorded by the controller (2026-11-15)

Device: onn 4K Pro Streaming Device (jarvis2), Android 14 (SDK 34), build URO3.260203.035.A1. SELinux enforcing. At test time: `adb_enabled=0`, `adb_wifi_enabled=1`, `service.adb.tcp.port=5555` (opened from the owner's PC).

| Question | Result |
|---|---|
| On-box classic ADB from the app (spike button 1) while 5555 is open | **Works** ("connect classic: OK in 6390 ms"). No Amazon-style block on Google TV. |
| Helper start from shell (setsid + nohup) | Works. uid=2000, parent pid 1. |
| App → helper over abstract Unix socket | **Denied** (same as Fire TV: client "Permission denied"). |
| App uid → shell-uid listener over loopback TCP | Works. |
| `/proc/net/tcp*` readable by shell, with uid column | Yes, on both boxes. So caller check option (a) is viable. |
| Compile com.nuvio.tv via helper | Success, **51 s**. Filter went `speed-profile` (bg-dexopt) → `speed` (cmdline). |
| Port 5555 after reboot | **Gone** (connection refused for 3 min after boot). |
| Wireless Debugging after reboot | **Off** (owner checked on the TV). After a reboot the user must turn it back on by hand. |
| Pairing from the TV alone | Not tested (the UI can't be driven remotely; 5555 was open). |

Captured samples: `app/src/test/resources/dumpsys/{onn4kpro-dexopt-before,onn4kpro-dexopt,onn4kpro-pkg,firetv-cube3-dexopt,firetv-cube3-pkg}.txt`.

Fire TV format note: `arm: [status=speed] [reason=unknown]` lines under `path:`, with `used by other apps:` lines in some blocks.

## M1 verification (release build, 2026-11-15)

Release APK (R8 on), signed with a local debug key for testing. Results recorded by the controller over ADB from the Mac, driving the UI with D-pad key events.

| Check | Fire TV Cube Gen 3 (Fire OS 7.7.1.7) | onn 4K Pro (Android 14) |
|---|---|---|
| App launches, warning, "I understand" | ✓ | ✓ |
| Helper start | From the PC (Fire OS blocks on-box ADB) ✓ | **By SideSpeed itself** over its own ADB (port 5555 open) ✓, after the fix below |
| Helper verified (nonce ping), app list shown | ✓ | ✓ |
| Sideloaded tab excludes system apps, initial D-pad focus on tab | ✓ | ✓ |
| Compile com.nuvio.tv from the app | Success, about 3 min, badge "speed ✓", dumpsys `status=speed` | Success, under 75 s, badge "speed ✓", dumpsys `status=speed [reason=cmdline]` |
| After a SideSpeed update | helper self-exits | helper self-exits; next launch restarts it automatically and returns to the app list ✓ |

**Bug found and fixed during this run:** on the onn, SideSpeed connected and sent the launch command, but the helper never started. libadb runs commands over the legacy PTY `shell:` service. When that session closed, the hang-up reached the backgrounded job before `setsid`/`nohup` took effect. This was reproduced from the Mac with `adb shell -tt`. The fix: the launch command now begins with `trap "" HUP` (inherited, no race). It was verified 3/3 over a forced PTY on the onn, and on the Fire TV.

**Still not verified:** pairing through the TV alone with the in-app grid while 5555 is closed (the Wireless Debugging TLS path). In this run 5555 was open, so SideSpeed took the classic route.
