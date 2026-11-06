# Watch probe

A throwaway Wear OS app. It asks Health Services on the watch what it supports, and nothing else. It answers two questions before anyone builds a real watch app for Haanf:

- Does the Pixel Watch 2 offer running dynamics, such as stride length and ground contact time? Look in the `RUNNING` data list.
- Can heart rate arrive every 5 seconds with the screen off? Look for `HEART_RATE_5_SECONDS` under batching overrides. Without it, Health Services can hold readings back for minutes, which is too late for the live coach and the high heart rate alert.

It lists capabilities twice: once before asking for permissions, and once after. That shows whether permissions change what Health Services reports.

**What it reads:** capability lists only. It never starts a workout or reads a heart rate, steps or location.

**Where results go:** the watch screen, the log under the tag `HaanfProbe`, and `files/probe.txt` in the probe's private storage. It has no internet permission, so nothing leaves the watch.

## Run it on the watch

1. On the watch, turn on Developer options by tapping the build number seven times in Settings, under System, About. Then turn on ADB debugging and Wireless debugging. Keep the watch and the Mac on the same Wi-Fi.
2. In Wireless debugging, choose to pair a new device. The watch shows an address with a port, and a code.
3. On the Mac, pair, then connect to the address Wireless debugging shows (its port differs from the pairing port):

   ```bash
   adb pair <address>:<pairing port> <code>
   adb connect <address>:<port>
   ```

4. Build, install and launch. Use the watch's serial from `adb devices` and nothing else, because other devices may be attached:

   ```bash
   ./gradlew :wearprobe:assembleDebug
   adb -s <watch> install -r wearprobe/build/outputs/apk/debug/wearprobe-debug.apk
   adb -s <watch> shell am start -n app.haanf.wearprobe/.ProbeActivity
   ```

5. Tap Allow on the watch, then read the results:

   ```bash
   adb -s <watch> shell run-as app.haanf.wearprobe cat files/probe.txt
   ```

6. Remove it when done:

   ```bash
   adb -s <watch> uninstall app.haanf.wearprobe
   ```

The probe has its own app id, so it never touches the phone app's data.
