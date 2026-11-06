# Watch probe

A throwaway Wear OS app. By default it asks Health Services on the watch what it supports. It answers two questions before anyone builds a real watch app for Haanf:

- Does the Pixel Watch 2 offer running dynamics, such as stride length and ground contact time? Look in the `RUNNING` data list.
- Can heart rate arrive every 5 seconds with the screen off? Look for `HEART_RATE_5_SECONDS` under batching overrides. Without it, Health Services can hold readings back for minutes, which is too late for the live coach and the high heart rate alert.

It lists capabilities twice: once before asking for permissions, and once after. That shows whether permissions change what Health Services reports.

Three more modes look at the watch's raw sensors through SensorManager. Start them with an extra:

- `--es mode sensors` lists every sensor, with its rate and batching. It reads nothing.
- `--es mode listen` listens for 30 seconds to heart rate, heart beat, off-body detection, steps, the barometer and every Google-specific sensor. It reports which ones an ordinary app may use, and how often they report. The watch must be worn with its screen awake, because Android gives background apps no sensor data and allows heart rate only while the app is in use.
- `--es mode detail` logs every event in full for 30 seconds from heart rate, Google's extended heart rate, step cadence and the step detector.

To check the old permission model, build with `./gradlew :wearprobe:assembleDebug -PprobeTargetSdk=35`. That version asks for `BODY_SENSORS` instead of the newer heart rate permission. Uninstall the probe first, because the two versions handle permissions differently.

Each mode saves to its own file, such as `files/listen.txt`. The listen report includes the barometer's absolute pressure, which gives height above sea level, so leave it out of anything saved in this repository.

**What it reads:** by default, capability lists only. It never starts a workout or reads location. The sensor modes above read heart rate, steps and pressure for 30 seconds.

**Where results go:** the watch screen, the log under the tag `HaanfProbe`, and a file in the probe's private storage, `files/probe.txt` by default. It has no internet permission, so nothing leaves the watch.

## Run it on the watch

1. On the watch, turn on Developer options by tapping the build number seven times in Settings, under System, About. Then turn on ADB debugging and Wireless debugging. Keep the watch and the Mac on the same Wi-Fi.
2. In Wireless debugging, choose to pair a new device. The watch shows an address with a port, and a code.
3. On the Mac, pair, then connect to the address Wireless debugging shows (its port differs from the pairing port):

   ```bash
   adb pair <address>:<pairing port> <code>
   adb connect <address>:<port>
   ```

   If the watch never answers on its IPv4 address, use its link-local IPv6 address instead, from the same Wireless debugging screen, with the Mac's network interface after `%`. The Pixel Watch 2 needed this on 7 November 2026:

   ```bash
   adb pair "[fe80::…%en0]:<pairing port>" <code>
   adb connect "[fe80::…%en0]:<port>"
   ```

4. Build, install and launch. Use the watch's serial from `adb devices` and nothing else, because other devices may be attached:

   ```bash
   ./gradlew :wearprobe:assembleDebug
   adb -s <watch> install -r wearprobe/build/outputs/apk/debug/wearprobe-debug.apk
   adb -s <watch> shell am start -S -n app.haanf.wearprobe/.ProbeActivity
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

## Results

The Pixel Watch 2's reports are in `results-pixel-watch-2.txt` for Health Services and `results-pixel-watch-2-sensors.txt` for SensorManager, and what it means for Haanf is under "Watch apps" in `docs/decisions.md`. Each data type appears twice in the lists, because Health Services gives a reading and its summary, such as a running total or a minimum, maximum and average, the same name.
