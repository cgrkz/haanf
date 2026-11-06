# Technical decisions

Choices made during Phase 0, with the reason for each.

## Toolchain

Versions were read from Google Maven, Maven Central and the Gradle services API on 2 November 2026, taking the newest stable release of each.

| Component | Version | Note |
| --- | --- | --- |
| Gradle | 9.8.2 | From the wrapper, with its checksum pinned. AGP 9.5 needs 9.6 or newer. |
| Android Gradle Plugin | 9.5.0 | Newest stable. Supports up to API 37. |
| Kotlin | 2.4.21 | Compiled through AGP's built-in Kotlin. |
| KSP | 2.3.13 | For the Room compiler. |
| Compose BOM | 2026.09.00 | |
| Room | 2.8.5 | |
| Health Connect client | 1.1.0 | Newest stable. 1.2 is still alpha. |
| compileSdk | 37 | Already installed. |
| targetSdk | 36 | The newest API with a local emulator image, so its behaviour can be tested. |
| minSdk | 26 | Android 8. Friends' phones are unknown, and the Health Connect client needs this. |

Application id is `app.haanf`. It can change before any store release.

## Heart rate boundary

`HeartRateSource` exposes state, readings and diagnostic notes, and nothing about Bluetooth. `LiveSessionController` depends on this interface only. The proof is `LiveSessionControllerTest`, which drives the whole session logic with a fake wearable on the JVM.

It is a package boundary rather than a Gradle module. One module is simpler for a project this size, and the rule is written in `CLAUDE.md`. A module is the next step if the rule ever gets broken.

## Bluetooth

- **Plain Android Bluetooth, no library.** The job is small: connect, discover, subscribe. A third-party stack would be more to learn than to write.
- **Paired devices are listed even when not advertising.** Google's support pages and forum reports suggest a watch paired to a phone may not appear in a normal scan, so the app offers a direct connection to it.
- **All callbacks run on one handler thread.** The source needs no locks as a result.
- **Pairing is handled, and shown.** Pixel Watch asks for approval on new connections. Android does not open a dialog for this: it posts a quiet "Pairing request" notification that expires after 30 seconds, so Haanf watches the bond state and tells the user where to look. Android resumes the interrupted subscribe by itself once bonded, so the app must not repeat it.
- **The bond listener is an exported receiver.** The Bluetooth service does not run as the system uid, so a non-exported receiver never hears from it. The action is protected, so no other app can send it.
- **A device without 0x180D stops, and does not retry.** The session reports the services it did offer. Retrying cannot help, and this brief forbids reverse-engineering.
- **Reconnection never gives up.** Backoff runs 1, 2, 4, 8, 15, then 30 seconds and holds there. A watch can return at any point in a workout.
- **Every third attempt rescans for 8 seconds,** filtered on 0x180D, in case the address rotated. It matches by address, or by name as a fallback. Two identical watches sharing nearby could be confused by the name match; a session event records any address change so it would be visible.
- **Scanning uses `neverForLocation`.** The app has no use for location. Android may hide some beacons as a result, which does not matter here.

## Dropouts

Two independent signals:

1. The link drops. The source reconnects by itself and the status shows it.
2. The link stays up but readings stop. `DropoutDetector` marks a dropout after 10 seconds of silence, and asks the source to reconnect after 30. Only a timer can catch this case.

A reading with no skin contact proves the link works, so it counts as a sign of life for dropout purposes, but it does not refresh the heart rate shown.

## Foreground service and wake lock

The service type is `connectedDevice`. A partial wake lock is held during a session, capped at 8 hours, so the dropout timer and database writes keep running in deep sleep. The service returns `START_NOT_STICKY`: if Android kills it, Haanf does not restart silently. The next launch closes the session with the reason `app_process_ended`, so a kill shows up in the data.

## Storage

Room holds sessions, samples, connection events and talk test metadata. Each sample is written as it arrives, since about one write a second is cheap and survives a crash. Export gives two CSV files per session, samples and events, so the samples stay easy to load for analysis.

## Talk test audio

- **WAV, 16-bit mono.** Breath sounds are what lossy codecs discard first, and this data exists to find out what can be analysed later.
- **Phone mic at 44.1 kHz, using `UNPROCESSED` where the phone supports it,** otherwise `VOICE_RECOGNITION`. Call-style noise suppression removes breath noise. The source used is saved with each recording.
- **Bluetooth earbuds at 16 kHz using `VOICE_COMMUNICATION`.** Earbud microphones run through the call path, which is narrowband and processed. Quality will differ from the phone mic, so the two should not be pooled without checking.
- **The input actually used is saved beside the input requested.** If Android routes elsewhere, the label stays honest and the app warns.
- **Its own foreground service with type `microphone`.** Android silences the microphone for background apps, so without this a pocketed phone would record silence.
- **A recording is written to a `.part` file, then renamed.** A crash cannot leave a truncated file that looks complete.
- **Limit of 90 seconds.** The user stops it, and the cap catches a forgotten recording.

The effort label is chosen before recording. Music is not paused automatically, so pause it first.

## Privacy

Haanf talks to the internet only for the AI provider the user chose, with their own key, after a screen listing what is sent. Without a key it makes no network calls. Connections are encrypted only, and a test fails if any other host appears in the code. Audio, location and device details never go to the AI. Backups and device transfer are off for every data domain. Recordings live in app-private storage. Otherwise data leaves only through the share sheet, after the user taps export, and the export cache is cleared on each new export and on delete.

## AI

- **Providers sit behind `LlmClient`,** so a subscription can replace bring-your-own-key later.
- **OpenRouter uses plain HTTP, and Claude uses the official SDK.** The SDK adds about 13 MB to the debug APK. Its default sends the key as a Bearer token, so `ClaudeClient` sets `x-api-key` explicitly, as Anthropic documents for API keys.
- **Keys are encrypted with the Android Keystore,** and `.env` is never built into the app. A test fails if its key appears anywhere under `app/src`.
- **Hinoki is selectable in the app,** at the user's request, for their own testing. Debug builds accept a key file placed over adb, which they encrypt and delete, because only someone with adb access to that phone can place it.
- **The rule check has no AI in it.** It decides, and the model only ever receives its reasons.
- **Increases are refused in code,** in any turn the athlete's own words did not trigger.
- **The coach is heard only through its `speak` tool.** Some models put private reasoning in their reply text, which must never be read aloud.

The Bluetooth diagnostic export contains the names and addresses of nearby devices, including other people's, so check it before sharing it.

## Health Connect

Read permissions for five data types and no write permissions. Each type is read separately, so one failure or refusal does not hide the others. Records are paged at 1,000 and only counts, sources, device names and timestamps are kept; the health values themselves are not stored.

## Watch apps

Live heart rate stays on the standard Bluetooth heart rate profile. Health Services on Wear OS batches readings while the screen is off, takes over any workout the watch is recording, and would need the Wearable Data Layer to reach the phone. A watch app would add running dynamics, which the phone cannot measure.

`wearprobe` is a separate module and app (`app.haanf.wearprobe`) that only lists what Health Services offers on a watch. It adds `androidx.health:health-services-client` 1.1.0 to that module only, at the user's request on 6 November 2026. It has no internet permission. The phone app is unchanged. Whether to build a real watch app waits for its results on the Pixel Watch 2.

## Testing limits

- `tools/ble-sim` runs simulated Bluetooth devices on the emulator. It found two real bugs that unit tests could not, both in pairing. It tests Haanf's code, not any watch.
- Instrumented tests run through `am instrument` on the emulator, never with `connectedDebugAndroidTest`, because that uninstalls the app from any attached phone.
- `adb` is used for Haanf only: install, launch, Haanf's log tags and Haanf's permissions. Facts about the test phone come from its wireless debugging announcement and from Haanf's own session record.
