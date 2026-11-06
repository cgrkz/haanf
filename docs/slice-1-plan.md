# Slice 1: terrain and the coach's timeline

The 5 November ride showed what the coach is missing. Its review called a 90 m climb "a hard surge", because heart rate was all it had, and it compared the ride with two benchmark starts that lasted 5 and 12 seconds, because every saved workout reached it unfiltered. The user's aim is for Haanf to do better than Google Health, not to match it: explain why heart rate did what it did, and act on it during the workout rather than afterwards.

Slice 1 is the first of three. Slice 2 tracks fitness between benchmarks: efficiency at easy effort, heart rate drift and readiness from the warm-up. Slice 3 is the talking threshold and personal zones.

## Status

Built on 6 November 2026, on the emulator. Nothing in it has been tried on the real Pixel 6 yet.

| Area | Result | Evidence |
| --- | --- | --- |
| Maths for terrain and effort | Pass | Unit tests for height from pressure, climb detection, speed, splits, watts and grade-adjusted pace |
| Rides and runs in the workout engine | Pass (simulated) | Unit tests drive fake GPS and pressure through a 2 km ride with a 36 m climb, and a run with spoken kilometres |
| Live coach conversation and timeline | Pass (simulated) | Unit tests: one growing conversation, timed check-ins, events since the last one, no talk test at a hard heart rate |
| Review | Pass (simulated) | Unit tests: the timeline word for word, kilometres, no duplicate workout, proposing a fact |
| Caching for Claude | Pass (request shape only) | The request asks for a one-hour cache. Not tried against the real API |
| Database version 6 | Pass | `MigrationTest` carries version 5 data over, and deleting a workout removes its splits and timeline |
| Screens | Pass (emulator) | Screenshots in `docs/design/build-screenshots`, 09-get-ready-ride, 22 and 23 |
| GPS, barometer and watts on the road | Not tested | Needs a ride on the Pixel 6 |

## Decisions from the user, 6 November 2026

- The phone's own sensors measure the workout, rather than the watch's recording read later from Health Connect.
- GPS runs on every outdoor run and ride. Get ready asks for location once, as the benchmark does.
- Haanf asks for the bike type once, on the first ride, and it can be changed in Settings. Rides get estimated watts.
- Runs show pace in minutes per kilometre. Rides show km/h.
- Treadmill workouts stay heart rate only. The coach is told the phone cannot measure them.
- Everything sent to the live coach carries the time into the workout, so it keeps an accurate history.
- The review gets what was said during the workout word for word, not a summary of it.

## In scope

**Measuring.**
- GPS gives distance, speed, moving time and each kilometre's split on outdoor runs and rides.
- The barometer gives climb, descent and gradient. It needs no permission.
- Effort that allows for hills: estimated watts for rides, and grade-adjusted pace for runs.

**The workout timeline.** One saved record per workout, in time order. It holds:
- what the coach said, what the athlete said, and talk test answers;
- plan changes;
- steps starting, heart rate alerts, the watch dropping out and coming back;
- each kilometre, and each climb starting and ending.

Every line carries the time into the workout and the heart rate at that moment.

**The live coach.**
- One conversation per workout. "About you" is sent once at the top, and each check-in is added below with what happened since the last one. The coach remembers what the athlete said.
- Each check-in includes speed or pace, distance, gradient and climb, and estimated effort.
- The start of a climb wakes the coach.
- The overview tool reports heart rate, speed and climb minute by minute, with times.
- With the Claude provider, the repeated start of the conversation is cached for an hour.

**The review.**
- One row per kilometre, or per five minutes without GPS.
- The steps, the timeline, and the totals for distance, speed, climb and effort.
- The workout under review no longer also appears in the workout list.
- It may offer to remember a pain or injury the athlete mentioned during the workout. The athlete confirms it, as in chat.
- The note is shorter.

**Fixes to what every request includes.**
- Workouts shorter than two minutes are left out.
- Abandoned workouts are marked.
- Benchmark walks are called walks.
- Each workout says whether it was a program, coach or free workout, and includes the effort rating and heart rate drop.

**Talk tests.** Without planned steps, heart rate decides what counts as a hard effort. Hard or Very hard means no talk test, in the coach and on the screen.

**Screens.**
- Get ready explains GPS and asks for the bike type.
- The live screen shows distance and speed or pace, and when the athlete is climbing.
- The summary adds distance, speed or pace, climb and effort, the kilometre splits, and "What you and the coach said".
- Settings gains "Your bike".

## Models

**Climb from pressure.**
- Height relative to the start comes from the standard atmosphere formula, `44330 × (1 − (p / p₀)^0.1903)` metres, where `p₀` is the pressure at the start.
- Readings are smoothed over a few seconds.
- Climb and descent only count once a change passes 1.5 m, so sensor noise and gusts add nothing.
- Gradient is the change in height over the last 100 m of distance.
- A climb starts when that gradient reaches 3% with at least 3 m gained. It ends when the gradient falls below 1%.
- Weather changes the pressure slowly, about 1 hPa an hour, or roughly 8 m. That is small over one workout.

**Cycling watts.** The standard road cycling power model (Martin and others, 1998):
- `P = (m·g·(Crr·cos θ + sin θ)·v + ½·ρ·CdA·v³) / 0.97`, counting only the power that pushes, never below zero.
- `m` is the rider's weight plus the bike's. ρ comes from the measured pressure at 15 °C.

| Bike | Bike mass | Crr | CdA |
| --- | --- | --- | --- |
| Road | 9 kg | 0.005 | 0.32 m² |
| Hybrid | 13 kg | 0.007 | 0.45 m² |
| Mountain | 14 kg | 0.010 | 0.50 m² |

Wind, drafting and tyre pressure are unknown, so this is an estimate. It is least accurate in wind.

**Grade-adjusted pace.**
- Minetti and others (2002) measured the energy cost of running at each gradient `i`: `C(i) = 155.4i⁵ − 30.4i⁴ − 43.3i³ + 46.3i² + 19.5i + 3.6` J/kg/m.
- Grade-adjusted speed is speed × `C(i) / C(0)`, with the gradient limited to ±45%.

## Privacy

- GPS measures distance and speed only. Fixes are used and dropped, and no coordinates or route are stored.
- The barometer's height above sea level is never stored or sent. Only climb relative to the start is kept.
- The AI receives distance, speed, gradient, climb and effort. These say nothing about where the athlete is.
- Spoken replies are stored as text, never audio. They already reached the AI during the workout, so the review adds nothing new.

## Data

Database version 6 adds:
- workout totals: distance, moving time, top speed, climb, descent, average watts and grade-adjusted pace;
- the bike type to the profile;
- a table of kilometre splits;
- a table for the workout timeline.

Version 5 data carries over unchanged. `MigrationTest` checks it.

## Out of scope

- Fitness trends between benchmarks, which is slice 2.
- The talking threshold and personal zones, which is slice 3.
- Chat opening one workout's detail. Added after slice 1, on 6 November 2026: see "Looking up workouts" in `docs/ai-mode-spec.md`.
- Workouts recorded only on the watch.
- Saving the recovery numbers each request used.
- Caching through OpenRouter or Hinoki.
- Treadmill speed and incline.

## What only the real Pixel 6 can show

The emulator can check the maths, the screens and the requests. These need the phone:
- GPS distance and speed on the road;
- the Pixel 6 barometer on a real climb, with the phone in a pocket and the screen off;
- estimated watts against how a ride felt;
- climb wake-ups during a ride;
- caching with a real Claude key.
