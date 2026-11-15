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
