# Phase 0 results

Last updated 2 November 2026.

**Status:** the Pixel Watch 2 streams live heart rate to Haanf over the standard profile, confirmed on real hardware with no workout running on the watch. The phone microphone records during a live session. The workout, screen-off, dropout and earbud tests are still to run, the Amazfit is untested, and the Health Connect contents have not been captured.

Result meanings:

- **Pass (real):** tested on a real device.
- **Partial (real):** some of it was seen on a real device, and the note says what is missing.
- **Pass (simulated):** tested on the Android emulator against a simulated Bluetooth device. This proves Haanf's code, not any watch.
- **Not tested:** still needs doing.

## Real hardware

The test phone is a Pixel 6 on Android API 37.1, connected by wireless debugging.

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Install debug APK | Pass (real) | `adb install` reported Success |
| Pixel 6 | Launch | Pass (real) | Haanf started, no crash in its logs |
| Pixel 6 | Stability during the test session | Pass (real) | No Haanf crashes in the phone's crash log |

### Test 1: live heart rate

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Bluetooth scan and permission prompts | Pass (real) | Bluetooth and notification access granted through the app, watch found and connected |
| Pixel Watch 2 | Appears in the diagnostic view, and which services it advertises | Partial (real) | Listed as "Google Pixel Watch 2". Advertised services not captured, because no scan list was exported. Services found after connecting are listed below. |
| Pixel Watch 2 | Standard profile, no workout on the watch | Pass (real) | Session 1: connected on the first attempt, 162 readings over 5 minutes 9 seconds, 76 to 87 bpm, no dropouts. Session was still running when read. |
| Pixel Watch 2 | Standard profile, workout running on the watch | Not tested | |
| Pixel Watch 2 | Connects while paired to the same phone | Partial (real) | Connected with no pairing activity logged. Whether the watch is paired to this Pixel 6 still needs confirming. |
| Pixel Watch 2 | Screen off for 10 minutes | Not tested | No gap over 3 seconds so far, but nothing records whether the screen was off |
| Pixel Watch 2 | Dropout and automatic reconnect | Not tested | No dropout has happened yet |
| Pixel Watch 2 | CSV export | Not tested | No export made on the phone |
| Amazfit GTR 2e | Appears in the diagnostic view, and which services it advertises | Not tested | |
| Amazfit GTR 2e | Standard profile, no workout on the watch | Not tested | |
| Amazfit GTR 2e | Standard profile, workout running on the watch | Not tested | |
| Amazfit GTR 2e | Screen off, dropout and reconnect | Not tested | |

### Test 2: talk test

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 mic | Counting and passage, easy, moderate and hard | Partial (real) | One recording so far: counting, easy, 14.4 seconds |
| Pixel 6 mic | Records real sound | Pass (real) | Measured on the phone, so no audio was copied off: about 4 seconds of quiet, then sound roughly 10 times louder. Peak 2,507 of 32,768, no clipping. Listening quality not judged. |
| Pixel 6 mic | Recording during a live heart rate session | Pass (real) | Heart rate 78 to 76 saved and linked to session 1. 8 readings arrived during the recording, longest gap 3 seconds. |
| Pixel 6 mic | Recording continues with the screen off | Not tested | |
| Bluetooth earbuds | Recording routes through the earbuds | Not tested | |
| Recordings | Effort levels can be told apart by ear or later analysis | Not tested | |

### Test 3: Health Connect

| Device | Test | Result | Evidence |
| --- | --- | --- | --- |
| Pixel 6 | Read-only permission screen | Pass (real) | All five read permissions granted through the app. The app requests no write permissions. |
| Pixel Watch 2 | What it provides: 14-day report by type and source | Not tested | Haanf shows this on screen only, and no report was exported |
| Amazfit GTR 2e / Zepp | What it provides: 14-day report by type and source | Not tested | |

## What the Pixel Watch 2 showed

From session 1 on the Pixel 6, with no workout marked as running on the watch.

- **Standard profile:** yes. The heart rate service (0x180D) answered and sent notifications.
- **Connection:** about 2.3 seconds, first attempt. The first reading came about 6.6 seconds later.
- **Reading rate:** about one every 2 seconds. Gaps were always about 1.5 or 3 seconds, so an age of up to 3 seconds on screen is normal.
- **No RR intervals.** None of the 162 readings carried them, so live HRV cannot come from this stream. Recovery HRV has to come from Health Connect, if the watch provides it there.
- **No skin contact signal.** The watch does not report it, so Haanf cannot tell from the stream that the watch is off the wrist.
- **Services after connecting:** Generic Access, Generic Attribute, Device Information, Heart Rate, Battery, and two vendor services: `e73e0001-ef1b-4e74-8291-2e4f3164f3b5` and `3675c0ff-21a6-4f96-9984-09d4f10c64f9`. Haanf does not use the vendor services.
- **Heart rate accuracy:** not checked against a reference. Values look plausible for rest.
- **Audio source:** the Pixel 6 does not offer the unprocessed microphone source, so Haanf used `VOICE_RECOGNITION`. Later breath analysis should allow for any processing that adds.

No code changes were needed after this test.

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

- **The watch is paired to this phone.** Zwift staff say a watch paired at OS level may not be seen by an app. The Pixel Watch 2 did connect, but it still needs confirming that it is paired to this Pixel 6.
- **Amazfit GTR 2e is unconfirmed.** No public documentation was found for the Activity heart rate sharing setting on this model. Haanf will report its services if it does not use the standard profile, and goes no further.
- **Android may cache a watch's old service list.** If a paired watch reports no 0x180D even with Connected Fitness on, this is a likely cause.
- **Two identical watches nearby could be confused.** Reconnection falls back to matching by name. Any address change is logged.
- **The Pixel Watch 2 may limit connections.** Google states a limit of 2 for Pixel Watch 3 and newer. It states none for the Watch 2.

## How to run the remaining tests

Session 1 was still running when these results were read. Stop it from Haanf or its notification, because it keeps the phone awake and costs battery on both devices.

Quickest next steps on the Pixel 6:

1. On the Health data tab, tap **Export this report**, then close the share sheet. Haanf keeps a copy that can be read for this document.
2. In the scan screen, open **All nearby (diagnostic)** with Connected Fitness on, and tap **Export this list**.
3. Confirm that the watch is paired to this Pixel 6.
4. Run the workout, screen-off and dropout steps below.

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
