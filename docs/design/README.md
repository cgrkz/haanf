# Haanf screen design

The 14 designed screens are in `screens/*.dc.html`, laid out in `canvas.json`, and on the canvas at https://claude.ai/artifact/HJW6YbK8sF8K5AaxZdhC5c. They set the look and the copy. Their numbers are sample data. Where a design disagreed with a feature that already worked, the app keeps the feature and takes the design's look.

`build-screenshots/` holds the built app on the emulator, with sample data seeded to match the designs. The table at the end compares the two.

## Visual system

**Type.** Barlow for text, and Barlow Condensed for headings and large numbers. Both are bundled in `app/src/main/res/font` under the SIL Open Font License 1.1, with the licence in `app/src/main/assets/licenses`. Fonts are never fetched over the network, so network hosts stay limited to the AI providers.

**Light theme only.**

| Use | Colour |
| --- | --- |
| Background | #F3F5F6 |
| Cards | #FFFFFF |
| Text | #101418 |
| Secondary text | #56616B |
| Dividers | #DDE2E6 |
| Primary | #1F5BFF |
| Warning background, icon | #FFF0E0, #9A3C00 |
| Information background | #E4ECFF |
| Input border | #A9B3BC |
| Switch off | #737F8A |
| Future weeks | #D5DBE0 |

| Zone | Colour | Text on it |
| --- | --- | --- |
| Recovery | #009FB0 | #101418 |
| Easy | #1F5BFF | White |
| Hard | #E8850C | #101418 |
| Very hard | #C93400 | White |

A zone's colour always appears with its name.

**Shape.** 8 dp corners, flat cards with no shadows or borders, outline icons drawn from the design's SVG paths, and no emoji.

**Touch targets.** At least 48 dp. Main actions are 56 dp, and anything used mid-workout is 56 to 64 dp.

The code is in `app/src/main/java/app/haanf/ui/theme` (colours, type, icons) and `ui/Kit.kt` (buttons, cards, choices, switches, notices, zone bars, sparklines, text fields).

## Navigation

- First run, in order: welcome, About you with the health check, connect your watch, the first benchmark.
- Three tabs: Program, Coach and Progress.
- History and vitals are part of Progress.
- Settings opens from the top of Progress. It holds About you, heart rate zones, your watch, the coach's provider, key, model and tone, Health Connect sharing, export, and the Lab.
- The coach's key screen opens from the Coach tab. With no key saved, it is the whole tab. With a key saved, the settings button next to the chat button at the top of the Coach tab opens it, and so does Coach in Settings.
- The Lab holds the Phase 0 tools: the heart rate tool, the talk test recorder and the Health Connect report.
- A running workout takes over the whole screen, with no tabs.
- Back steps through screens opened over the tabs. There is no navigation library: `ui/Nav.kt` is a small back stack.

## Screens with no design

These follow the same visual system and stay plain.

- **Settings.** One card per section. About you can be edited in place, and it includes the optional measured maximum heart rate from the earlier profile form.
- **The Lab.** A back button and the tool's name above each Phase 0 tool. The tools themselves were not redesigned.
- **Chat.** "What the coach knows" sits at the top as a card, with Remember, Dismiss and Mark resolved. Coach messages are white bubbles on the left, and the athlete's are blue on the right. The bar at the bottom has the text field, the microphone and Send.
- **Coach setup.** The earlier setup, restyled: provider, key, model and tone, then the "What the AI receives" box. In the Coach tab it also offers "Start a free workout", so a free workout never needs a key. With a key saved, a line under the key field names the saved provider and says to paste a key again to change the provider, model or tone. The key itself is never shown.
- **All workouts.** The same rows as Recent workouts, newest first. Each opens its summary, which has "Delete this workout" at the bottom.
- **Benchmarks.** Every benchmark with its date, level, mile time and VO2max, and a Start benchmark button.
- **Benchmark results.** The summary with a blue card on top: estimated VO2max, the level for age and sex, the typical value, mile time and heart rate near the end. "Your program is unlocked" shows only after the first scored benchmark. A walk with no score says why.
- **Dropout.** The live screen turns #101418, heart rate reads "- -", and a warning banner says Haanf is reconnecting and the workout is still recording.
- **Benchmark walk.** #101418, with distance as the big number for an outdoor walk, or elapsed time on a treadmill, then the stop rule and Finish.

## Built against the designs

| Design | Built | Notes |
| --- | --- | --- |
| Welcome | Matching | |
| About you and health check | Changed | Adds the optional injuries and conditions field, which the coach reads. Saved as an injury fact, as before. |
| Connect your watch | Changed | The Pixel Watch 2 copy says to turn on Connected Fitness, because Phase 0 needed it. The Amazfit steps keep "Not yet tested with this watch". "Continue without a watch" works, and Get ready asks for a watch before a workout. The screenshot shows the search, since the emulator has no watch. |
| First benchmark | Changed | Adds "Not now", which goes to the app with the program still locked. |
| Program details | Changed | The stay-consistent box says coach and free workouts don't count, rather than only AI workouts. Adds "A draft plan, still under review", and a week-by-week list when opened from "See the full plan". Start asks for confirmation with the existing safety warning. With no benchmark scored, a benchmark card replaces Start. |
| Program tab | Changed | Session names are the program's own ("4 x 2 minutes hard"), since programs are fixed. The warning names the real deadline ("by tomorrow"). Easy sessions describe their pace and bpm. A label says "Next session" if a program workout was already done today. |
| Coach check-in | Changed | Feel has five choices in two rows. Keeps the optional note from the earlier check-in. "Last night" shows which day the data is from, not when the watch synced, because Health Connect doesn't say when a sync happened. "Start a free workout" sits under the main button. A settings button beside the chat button opens the coach's key screen. |
| Coach recommendation | Changed | Each step shows its zone and bpm under its name. The design's line about the next benchmark is left out, because the planner doesn't return it. "Change my answers" returns to the check-in. |
| Get ready | Changed | The talk test switch is "Talk test checks", shown only with the coach on, since the coach now asks the question. Adds the "Coach me" switch from the brief, and an "Allow the microphone" link when needed. |
| Live: steady step | Changed | Shows elapsed time instead of distance when nothing measures distance. With the coach on, the coach's latest line appears, with "Talk to coach", or quick replies on a phone with no on-device speech. The emulator has none, so the screenshot shows quick replies. |
| Live: hard interval | Matching | The step name is the program's own ("Hard 2 of 4"). |
| Talk test | Changed | No counting and no recording. The coach asks, and the answer is spoken or one of three buttons: Could talk, Some effort, Couldn't talk. Adds Skip. Never shown during a hard effort. |
| Workout summary | Changed | The talk test line gives minutes into the workout, not the step, since steps aren't saved with workouts. Adds highest heart rate, and time with no reading when over 30 seconds. "Coach note" appears only when there's a review. |
| Progress tab | Changed | The headline is estimated VO2max, because the talking threshold isn't measured yet. Vitals cover the last 4 weeks, not 6, matching what Haanf reads from Health Connect. The screenshot uses sample vitals, because the emulator has no Health Connect data. |

No designed screen was left unbuilt.

## Screenshots

`DesignScreenshotsTest` seeds sample data, renders each screen, and saves it on the emulator. To refresh them, with only the emulator in mind:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e class app.haanf.DesignScreenshotsTest app.haanf.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 pull /sdcard/Android/data/app.haanf/files/design-screens docs/design/build-screenshots
```

| File | Design |
| --- | --- |
| 01-welcome | Welcome |
| 02-about-you | About you and health check |
| 03-connect-watch | Connect your watch |
| 04-first-benchmark | First benchmark |
| 05-program-home | Program tab |
| 06-program-detail, -bottom | Program details |
| 07-coach-check-in, -unwell | Coach check-in |
| 08-coach-plan | Coach recommendation |
| 09-get-ready | Get ready |
| 10-live-steady | Live: steady step |
| 11-live-interval | Live: hard interval |
| 12-talk-test | Talk test |
| 13-summary, -bottom | Workout summary |
| 14-progress, -bottom | Progress tab |
| 15-settings, -bottom | No design |
| 16-coach-setup, -saved | No design |
| 17-live-dropout | No design |
| 18-history | No design |
| 19-live-benchmark | No design |
| 20-benchmark-result | No design |
| 21-chat | No design |

In the screenshots, a grey box sometimes marks the tab just tapped. That is the test harness running outside touch mode. A real tap on the emulator shows no box.

## Still to check on the real Pixel 6

The emulator checks builds and screens only.

- Get ready connects to the watch to show live heart rate, then lets go so the workout can connect. Whether the Pixel Watch 2 reconnects at once is untested.
- Connect your watch should find a paired Pixel Watch 2 by itself.
- Spoken cues on and off, and the high heart rate alert still speaking with cues off.
- "Talk to coach" and spoken talk test answers, with on-device speech.
- The heart rate drop after the last hard step, which needs real readings.
- GPS distance on the benchmark screen.
- "Last night" and the vitals sparklines with real Health Connect data.
- Fonts and light status bar icons on the dark and coloured screens.
- The new screens on the phone itself. The upgrade in place was done on 5 November 2026: the database moved to version 5, and both heart rate sessions, all 222 samples, the talk test, the workout and the profile survived. The phone was locked, so nothing was seen on screen. Its profile has no weight, sex or benchmark, so it should open on About you, then Connect your watch unless a watch is already saved, then the first benchmark.
