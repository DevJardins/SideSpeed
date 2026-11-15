# M0 spike runbook (for the owner)

This is a throwaway test build. It answers a few questions about how SideSpeed can talk to ADB on your two boxes. Nothing here is permanent. Menu names vary slightly between Fire OS and Google TV versions; if a name below is not exact, look for the closest one.

You need: a Mac/PC with `adb` installed, on the same Wi-Fi/network as the TV box, and the APK built from this repo (`app/build/outputs/apk/debug/app-debug.apk`; build it with `./gradlew :app:assembleDebug`).

The spike app appears in the launcher as **SideSpeed Spike**. Its buttons are numbered 1 to 9. Use the D-pad and OK. A log fills the right side; the same lines go to logcat under the tag `SideSpeedSpike`.

Below, `<ip>` is the box's IP address (Settings, Network, your Wi-Fi, or the box's About screen). Use `firetv-cube3` and `onn4kpro` for `<device>` in file names.

---

## Part A. Fire TV Cube Gen 3 (classic ADB on port 5555)

1. Enable developer options: Settings, My Fire TV, About, then press OK on the device name (the "Fire TV Cube" name) 7 times until it says you are a developer.
2. Settings, My Fire TV, Developer options: turn **ADB debugging** ON. (Also **Apps from Unknown Sources** ON if asked.)
3. On the PC: `adb connect <ip>:5555`. Accept the "Allow USB debugging?" prompt on the TV and tick "Always allow".
4. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. Install Nuvio on the box if not already (package `com.nuvio.tv`).
6. Start **SideSpeed Spike** from the launcher. At startup it logs the model and whether this is a Fire TV.
7. On the box, in the spike, press in this order:
   1. **1. Connect 127.0.0.1:5555**. A prompt "Allow debugging?" may appear. Tick "Always allow" and accept, then press button 1 again if needed. Write down: did it connect?
   2. **4. Run id**. Expected `uid=2000(shell)`.
   3. **5. dumpsys package dexopt -> file**.
   4. **6. Compile com.nuvio.tv (speed)**. Note whether `pm compile` worked or the `cmd package compile` fallback was needed, and the output.
   5. **7. pm grant self WRITE_SECURE_SETTINGS**. Should end with exit=0 and no error.
   6. **8. Toggle ADB off -> on**. The connection will drop (expected). Write down: did it say RECONNECTED, and after how many ms? If not, did port 5555 come back on its own? (Check from the PC: `adb connect <ip>:5555`.)
   7. After button 8, press **4. Run id** again: is the connection really working and is the key still trusted (no new prompt)?
   8. **9. Send tcpip:5555**. Record the result. This may drop the connection. Afterwards, from the PC, run `adb connect <ip>:5555` and write down whether it connects.
8. Capture from the PC right after the run (the logcat buffer is small and old lines get overwritten, so do this immediately):
   ```
   adb connect <ip>:5555
   adb -s <ip>:5555 shell dumpsys package dexopt > firetv-cube3-dexopt.txt
   adb -s <ip>:5555 shell dumpsys package com.nuvio.tv > firetv-cube3-pkg.txt
   adb -s <ip>:5555 logcat -d -s SideSpeedSpike > firetv-cube3-spike.log
   ```
   (If only one device is connected you can drop the `-s <ip>:5555` part.)

---

## Part B. Onn 4K Pro (Google TV, Android 14, Wireless Debugging)

1. Enable developer options: Settings, System, About, press **Android TV OS build** (or "Build") 7 times.
2. Settings, System, Developer options: turn **USB debugging** ON, and turn **Wireless debugging** ON. Accept the "Allow wireless debugging on this network?" prompt.
3. Install the APK. You can use a PC that is paired with the box, or do it the plain way:
   - Settings, System, Developer options, Wireless debugging, **Pair device with pairing code**. A 6-digit code and an address like `<ip>:<pairport>` appear. Leave this screen open.
   - On the PC: `adb pair <ip>:<pairport>` and type the code.
   - Back on the Wireless debugging screen, look at "IP address & Port" (a different port, e.g. `<ip>:<connectport>`). On the PC: `adb connect <ip>:<connectport>`.
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - Install Nuvio if not present (`com.nuvio.tv`).
4. Start **SideSpeed Spike**. Note the startup lines (model, Android version, Wi-Fi IP).
5. **Test pairing from the TV alone** (the key question):
   1. Press **1. Connect 127.0.0.1:5555**. Write down the result (probably fails on this box, which is fine).
   2. Open Settings, System, Developer options, Wireless debugging, **Pair device with pairing code**. A popup shows a 6-digit code and a pairing port. Leaving that popup normally dismisses it and invalidates the code, so try this once only: with the popup open, switch to **SideSpeed Spike** (Home button, then launch it, or use Recent apps). **Write down whether the popup disappeared when you left it.** If the code is still showing in Recent apps / split screen, enter it; if the popup is gone, stop here and go to step 6 (this is itself an answer).
   3. If the code is still valid: in the spike, use the number pad to enter the 6-digit code (select "Typing into: CODE") and then switch to PORT and enter the pairing port. Press **2. Pair**. The log shows the try via 127.0.0.1 and then via the Wi-Fi IP. Write down which worked, or both failed and the exact errors.
   4. If pairing worked, press **3. Connect TLS (mDNS)**, then **4. Run id**. Write down both results (this is the "pairing key works over TLS" test).
   5. Press **5. dumpsys ...**, **6. Compile**, **7. pm grant self**.
   6. Press **8. Toggle ADB off -> on**. Record RECONNECTED and the ms, or the failure.
6. **If pairing from the TV alone failed, test the "Always allow" key over TLS.** (Pairing from the PC only authorizes the PC's key, not the app's, so it does not help the app by itself.) The app's own key has to be trusted over classic port 5555 first:
   1. On the PC, with the box connected over Wireless debugging: `adb tcpip 5555`. This drops the connection. Then `adb connect <ip>:5555`.
   2. In the spike press **1. Connect 127.0.0.1:5555**. Accept "Allow debugging?" and tick **Always allow**; press 1 again if needed. Write down whether it worked.
   3. Press **7. pm grant self** (needed later).
   4. On the TV, turn Wireless debugging OFF and ON in Developer options so it is active on a TLS port.
   5. In the spike press **3. Connect TLS (mDNS)**, then **4. Run id**. Write down the result. (This answers: does a key trusted via "Always allow" work over TLS?)
   6. If the box did not accept 5555 at step 1, say so; that also answers "is 5555 reachable".
7. **Reboot test (Onn):**
   1. Make sure button 7 (pm grant self) succeeded before, and that a connect (button 3 or 1) worked at some point (the spike remembers the last good target across reboots).
   2. Restart the box (Settings, System, Restart, or unplug for 10 s).
   3. After it boots, go to Settings, System, Developer options. Is **Wireless debugging still ON**? Write it down (yes/no).
   4. Start the spike. Press **3. Connect TLS (mDNS)**. Did it connect without re-pairing? Write it down.
   5. If Wireless debugging is OFF after reboot: start the spike and press **8. Toggle ADB off -> on**. This works even with no ADB connection, because it only needs the grant from button 7. This is the key test of "survive reboots". The spike will try to reconnect to the saved target; if it does not, press **3** manually. Write down: did Wireless debugging come back ON, did reconnect work, and how many ms?
8. Capture from the PC right after the run (the logcat buffer is small; do not wait). Connect with `adb connect <ip>:<connectport>`, which may be different after a reboot. If you can, capture the logcat once before the reboot test too:
   ```
   adb shell dumpsys package dexopt > onn4kpro-dexopt.txt
   adb shell dumpsys package com.nuvio.tv > onn4kpro-pkg.txt
   adb logcat -d -s SideSpeedSpike > onn4kpro-spike.log
   ```



---

## What to send back

1. Filled-in `docs/spike/M0-results.md` (yes/no and short notes for each device).
2. For each device: `<device>-dexopt.txt`, `<device>-pkg.txt`, `<device>-spike.log`.
3. The file `dexopt.txt` the app wrote on the box is optional (it is at `/data/data/io.github.devjardins.sidespeed/files/dexopt.txt` via `adb shell run-as io.github.devjardins.sidespeed cat files/dexopt.txt`); the PC copy above is enough.
4. A photo or text of any error that appeared on screen that is not in the log.
5. Exact model and software versions (Settings, About) for both boxes.
