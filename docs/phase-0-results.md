# Phase 0 results

Last updated 2 November 2026.

**Status:** the app is built and tested in software. No real watch, real microphone recording or real Health Connect data has been tested yet. Only the phone install is proven on real hardware.

Result meanings:

- **Pass (real):** tested on a real device.
- **Pass (simulated):** tested on the Android emulator against a simulated Bluetooth device. This proves Haanf's code, not any watch.
- **Not tested:** still needs doing.

## Real hardware

The test phone is a Pixel 6 on Android API 37.1, connected by wireless debugging.

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Install debug APK | Pass (real) | `adb install` reported Success |
| Pixel 6 | Launch | Pass (real) | Haanf started, no crash in its logs |

### Test 1: live heart rate

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Bluetooth scan and permission prompts | Not tested | |
| Pixel Watch 2 | Appears in the diagnostic view, and which services it advertises | Not tested | |
| Pixel Watch 2 | Standard profile, no workout on the watch | Not tested | |
| Pixel Watch 2 | Standard profile, workout running on the watch | Not tested | |
| Pixel Watch 2 | Connects while paired to the same phone | Not tested | |
| Pixel Watch 2 | Screen off for 10 minutes | Not tested | |
| Pixel Watch 2 | Dropout and automatic reconnect | Not tested | |
| Pixel Watch 2 | CSV export | Not tested | |
| Amazfit GTR 2e | Appears in the diagnostic view, and which services it advertises | Not tested | |
| Amazfit GTR 2e | Standard profile, no workout on the watch | Not tested | |
| Amazfit GTR 2e | Standard profile, workout running on the watch | Not tested | |
| Amazfit GTR 2e | Screen off, dropout and reconnect | Not tested | |

### Test 2: talk test

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 mic | Counting and passage, easy, moderate and hard | Not tested | |
| Pixel 6 mic | Recording during a live heart rate session | Not tested | |
| Pixel 6 mic | Recording continues with the screen off | Not tested | |
| Bluetooth earbuds | Recording routes through the earbuds | Not tested | |
| Recordings | Effort levels can be told apart by ear or later analysis | Not tested | |

### Test 3: Health Connect

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Read-only permission screen | Not tested | |
| Pixel Watch 2 | What it provides: 14-day report by type and source | Not tested | |
| Amazfit GTR 2e / Zepp | What it provides: 14-day report by type and source | Not tested | |

## Software checks

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Fresh git clone | Builds from clean with the wrapper, no cache | Pass | `clean assembleDebug testDebugUnitTest` with `--no-build-cache`, 47 tasks run |
| JVM | Unit tests | Pass | 44 of 44, including session logic driven by a fake wearable |
| Emulator, API 36 | Instrumented tests | Pass | 8 of 8: database, screens, export regression |
| Built APK | No internet, no Health Connect write, backups off, not test-only | Pass | Read from the merged APK with `aapt2` |
| Simulated strap | Scan, connect, live BPM, reading age, status | Pass (simulated) | Connected in about 1 second |
| Simulated strap | Link drops | Pass (simulated) | Reconnected by itself in 2.7 seconds |
| Simulated strap | Link stays up but goes silent | Pass (simulated) | Dropout at 10 seconds, forced reconnect at 30, recovered, 49-second gap logged |
| Simulated strap | Device vanishes for 25 seconds, then returns | Pass (simulated) | Reconnected without help, 50-second gap logged |
| Simulated strap | Session survives Home and the share sheet | Pass (simulated) | Kept collecting, 55 samples after 15 seconds in the background |
| Simulated strap | Stop from the notification | Pass (simulated) | Session closed as `user_stopped`, no service left running |
| Simulated strap | CSV export | Pass (simulated) | 123 samples with RR intervals, gaps match the events file |
| Simulated device | Device without 0x180D | Pass (simulated) | Clear report listing its services, no retry loop |
| Simulated device | Device that demands pairing | Pass (simulated) | Status explains pairing, then streams once paired |
| Emulator mic | Talk test during a live session | Pass (simulated) | Valid WAV: mono, 16-bit, 44.1 kHz, 11.2 seconds. Saved heart rate 140 to 152 |
| Emulator | Earbuds chosen with none connected | Pass (simulated) | Clear error, nothing saved |
| Emulator | Recording privacy and delete | Pass (simulated) | File is private to the app, delete removes it |
| Emulator | Health Connect read-only permission screen | Pass (simulated) | Screen lists five reads and no writes |
| Emulator | Health Connect with no data and with one type refused | Pass (simulated) | Each type reports separately, no errors |

The emulator's microphone proves the recording flow only. It says nothing about audio quality.

## Bugs found by testing

All three are fixed.

1. **Exporting a talk test crashed the app and killed any live session.** Found when a session unexpectedly ended as `app_process_ended`. A regression test now taps the real button, and it was confirmed to fail with the bug put back.
2. **Pairing updates never reached Haanf.** Its listener rejected messages from the Bluetooth service.
3. **Pairing looked like a stuck "Connecting" screen.** Android's prompt is a quiet notification that expires after 30 seconds. Haanf now tells you to look for it.

## Known risks for the real test

- **The watch is paired to this phone.** Zwift staff say a watch paired at OS level may not be seen by an app. Haanf lists paired devices so you can connect directly, but whether that works is unknown.
- **Amazfit GTR 2e is unconfirmed.** No public documentation was found for the Activity heart rate sharing setting on this model. Haanf will report its services if it does not use the standard profile, and goes no further.
- **Android may cache a watch's old service list.** If a paired watch reports no 0x180D even with Connected Fitness on, this is a likely cause.
- **Two identical watches nearby could be confused.** Reconnection falls back to matching by name. Any address change is logged.
- **The Pixel Watch 2 may limit connections.** Google states a limit of 2 for Pixel Watch 3 and newer. It states none for the Watch 2.

## How to run the remaining tests

### Before you start

Close other fitness apps and disconnect the watch from any gym equipment. Open Haanf on the phone.

### Test 1 with the Pixel Watch 2

1. On the watch, swipe down, tap **Connected Fitness**, then **Connect**. Accept **Wider device access** if asked. If the tile is missing, use **Settings > Connectivity > Share Heart Rate**.
2. In Haanf, tap **Scan for a heart rate device**, then **Allow Bluetooth**.
3. Switch to **All nearby (diagnostic)** and tap **Export this list**. The list contains nearby strangers' devices, so check it before sharing.
4. Leave **Workout running on the watch** off, and tap **Connect** on the watch.
5. If asked to pair, pull down the notification shade, tap **Pairing request**, then **Pair**. Leave "Also allow access to contacts and call history" off. Accept on the watch too.
6. Wait for BPM to update, for 2 minutes. Then tap **Stop session**.
7. Start a workout on the watch, turn **Workout running on the watch** on, and repeat.
8. Screen off: start a session, lock the phone for 10 minutes, unlock, stop.
9. Dropout: start a session, leave the phone and walk away with the watch for a minute, come back. Check that it reconnects by itself.
10. Tap **Export CSV** on a session to check the files.

If it stops with "This device does not share heart rate over the standard profile", note the services listed and check Connected Fitness is on. If the watch is not listed at all, that is a result too.

### Test 1 with the Amazfit GTR 2e

1. In Zepp, turn on **Device > General > Activity heart rate sharing**, then close Zepp.
2. Install Haanf using [install-on-a-phone.md](install-on-a-phone.md).
3. Run the same steps, both with and without a workout started on the watch.
4. If it reports no standard heart rate service, that is the answer. Save the service list. Do not try to work around it.

### Test 2

1. On the Talk test tab, tap **Allow microphone**.
2. Start a live heart rate session first, so that heart rate is saved.
3. Choose the test, effort, activity and input, and tap **Start**. Pause any music first.
4. Record a few of each effort level during a real workout, on both tests.
5. Lock the phone during one recording, to check it keeps recording.
6. Tap **Play** to listen, and **Export all with labels** when finished.

### Test 3

1. On the Health data tab, tap **Choose what to share** and allow all.
2. If a type shows no data, check Health Connect sync is on in the Fitbit or Google Health app, or in Zepp.
3. Tap **Export this report**.
