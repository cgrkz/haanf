# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Haanf is an AI-native endurance training app for Android. See `docs/product-brief.md`. Phase 0 proved live heart rate, talk test recording and Health Connect on real hardware, and its results are in `docs/phase-0-results.md`. The project is now in Phase 1, the offline training loop: workouts, one fixed program, history and vitals. See `docs/phase-1-plan.md`, including what is deliberately out of scope. Technical decisions are in `docs/decisions.md`.

## Build and test

Requires JDK 17 or newer and the Android SDK (platform 37, build-tools 36). Gradle comes from the wrapper, so nothing else needs installing.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM tests
./gradlew :app:testDebugUnitTest --tests '*DropoutDetectorTest'  # one class
./gradlew clean :app:assembleDebug :app:testDebugUnitTest        # the clean check
```

Instrumented tests live in `app/src/androidTest`. `connectedDebugAndroidTest` runs on every attached device and uninstalls the app afterwards, which wipes Haanf's data on a real phone. With a phone attached, run them on the emulator only:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w app.haanf.test/androidx.test.runner.AndroidJUnitRunner
```

Live logs from a phone, Haanf's tags only:

```bash
adb logcat HaanfBle:I HaanfScan:I HaanfSession:I HaanfService:I '*:S'
```

## Architecture

One Gradle module, `:app`, with packages under `app.haanf`:

- `heartrate`: the device boundary. `HeartRateSource` is the only thing workout logic knows about a wearable.
- `heartrate.ble`: the Bluetooth Heart Rate Profile implementation. Only this package and the scan screen may touch Bluetooth types.
- `training`: pure rules with no Android types. Zones, workout plans, summaries and safety.
- `program`: `ProgramRules` holds the reset rules, and `Programs` holds the fixed plans. Resets are worked out from saved progress on demand, so a reset writes nothing.
- `workout`: `WorkoutController` runs one workout outside any screen, so steps and speech keep going with the phone in a pocket. It reaches heart rate and storage through `LiveLink` and `WorkoutStore`.
- `session`: `LiveSessionController` runs a session against any source and detects dropouts. `HeartRateForegroundService` only holds foreground priority and a wake lock.
- `talktest`: WAV recording, routing and its own microphone foreground service. It gets heart rate through plain functions.
- `health`: read-only Health Connect report.
- `data`: Room. `export`: CSV and the share sheet.
- `tools/ble-sim`: simulated Bluetooth peripherals for the emulator. See its README.
- `ui`: `HaanfRoot` chooses between tabs and a running workout. The Phase 0 tools live under the Lab tab.
- `AppContainer`: manual wiring. One instance per process. Screens read state from it directly.

To add a wearable, implement `HeartRateSource` and return it from a `HeartRateSourceFactory`, keyed by `HeartRateDevice.sourceKind`. Do not change `session` or `talktest`.

## Rules

- Never declare a Health Connect write permission or the internet permission. `ManifestPolicyTest` enforces both.
- Data leaves the phone only through `ShareFiles`, from a user tapping export. Backups stay off.
- Never report hardware behaviour as verified unless it was tested on a real device. The emulator checks builds and screens only.
- Do not reverse-engineer a wearable that lacks the standard heart rate profile. Report it instead.
- Only program workouts may reach `ProgramTracker.credit`. Free and AI workouts must never touch the program clock.
- Programs are fixed. The AI may coach a session but never changes a plan.
- Keep AI calls, backends, accounts and paid services out until the user decides otherwise.
- Add database changes as a new Room version with a migration, and test it in `MigrationTest`. Real phones hold data.
