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
