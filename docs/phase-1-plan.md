# Phase 1 plan

Phase 0 proved that the Pixel Watch 2 streams live heart rate, so the user asked to move on from testing and keep building. No next phase had been defined, so this one comes from the product brief. Redirect it freely.

## Status

Built on 2 November 2026.

| Area | Result | Evidence |
| --- | --- | --- |
| Unit tests | Pass | 127 of 127, from a fresh clone with no cache |
| Emulator tests | Pass | 21 of 21, including both database migrations and program saving |
| Full workout flow | Pass (simulated) | Enrol, program session against a simulated strap, background, notification, early finish, history |
| Setup and health check | Pass (simulated) | From a clean install to the first benchmark |
| Outdoor benchmark | Pass (simulated) | A fake GPS route counted up, Haanf stopped itself at the mile, and it refused to score a 3-minute walk |
| Treadmill benchmark | Pass (simulated) | An 8:38 walk scored 63.6, matching the equation by hand, and unlocked the program |
| Upgrade on the real Pixel 6 | Pass (real) | Installed over existing data with no crash. Database moved from version 2 to 3, and all 2 sessions, 222 samples, the talk test with its audio, the workout and the profile survived |
| Spoken cues | Pass on the emulator only | The speech engine ran. Not heard on a real phone |
| Free workout with the real watch | Partial (real) | One workout of about 5 seconds on the Pixel 6: connected in under a second, one reading at 77 bpm, saved to history. Too short to show more |
| Program session or benchmark with the real watch | Not tested | |
| GPS on a real phone | Not tested | |
| Vitals with real Health Connect data | Not tested | |
| A workout counting towards the program, end to end | Not tested | Each part is tested, but no run reached 80% of a plan |

## Goal

Turn the proven pieces into the real training loop: start a workout, follow a fixed program, and keep a history. The Phase 0 tools stay available in a Lab tab.

## In scope

- **Profile:** year of birth and optional measured maximum heart rate, kept on the phone only.
- **Heart rate zones** from that profile.
- **Workout flow:** choose an activity, stream heart rate, follow timed steps with spoken cues, finish, see a summary.
- **Workout history,** shared by both modes.
- **Program mode:** one fixed 8-week program, and the reset rules from the brief.
- **Vitals:** resting heart rate, HRV and sleep trends from Health Connect, labelled when stale.
- **Safety:** a health check, a pre-enrolment warning, and an alert when heart rate stays above the estimated maximum.
- **First benchmark:** added at the user's request. It follows setup and unlocks the program. See [benchmarks.md](benchmarks.md). It was paused at the user's request on 7 November 2026.

## Out of scope, and why

- **AI mode and the AI coach.** Phase 0's limits on AI calls, backends and paid services were not lifted, and they need a decision from the user on provider and cost.
- **Repeat benchmarks chosen by the AI,** and tests beyond the first one.
- **Final design.** The screens stay plain.

The data model leaves room for AI workouts: `Workout.origin` separates them, and only program workouts touch the program clock.

## Decisions

### Zones

Five numbered zones, as shares of maximum heart rate. On 13 November 2026 the user replaced the four named zones (Recovery, Easy, Hard and Very hard) with these, and asked for them to be editable.

| Zone | Share of maximum | Basis |
| --- | --- | --- |
| Zone 1 | Below 60% | Everything under Zone 2 |
| Zone 2 | 60 to 70% | Upper half of the [AHA](https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates) moderate band |
| Zone 3 | 70 to 80% | AHA vigorous activity starts at 70% |
| Zone 4 | 80 to 90% | |
| Zone 5 | 90% and above | The 4 x 4 study's 90 to 95% |

The 60, 70, 80 and 90% starts are the common five-zone default, the one Garmin uses. Zone 3 and above count as hard work, which keeps the old line at 70%.

**Editing.** Settings lets the athlete set where Zones 2 to 5 start, in bpm. Zone 1 is everything below Zone 2. Each start must be higher than the one before. Edited zones stay put when the maximum changes, until "Use the defaults" puts them back. Each workout saves the zones it was measured against, so editing them later does not change past workouts.

**Moving saved workouts to five zones.** The database's version 7 works out each saved workout's time in each zone again from its saved heart rate, against the default zones for the maximum it was recorded with. A workout with no saved heart rate keeps its old totals, moved to the nearest new zone: Recovery to Zone 1, Easy to Zone 2, Hard to Zone 3 and Very hard to Zone 5.

Maximum heart rate is `208 - 0.7 x age` ([Tanaka 2001](https://pubmed.ncbi.nlm.nih.gov/11153730/)), unless the user enters a measured value. It is an estimate, and individuals can differ from it.

An earlier idea of using 64 to 76% and 77 to 93% was dropped: those bands came from a research paper's cut-offs, not from official guidance.

### Program reset rules

These are computed from the last counted program workout whenever the app needs them, so nothing is stored or changed by a reset.

- Days are calendar days on the phone.
- 7 or more days since the last program workout: repeat the last week trained, from its first session.
- 30 or more days: restart at Week 1.
- Free workouts, and AI workouts later, do not touch the clock.
- Today shows how many days are left before each reset, and explains any reset that has applied.

### What counts as a program workout

A program workout counts when at least 80% of its planned time was done. A shorter attempt stays in history but does not advance the program or reset the clock.

### The draft program: Foundation 8

**This is a draft for the user to review, not a validated plan.** Three sessions a week for eight weeks, a frequency matching [Helgerud 2007](https://pubmed.ncbi.nlm.nih.gov/17414804/).

| Week | Session 1 | Session 2 | Session 3 |
| --- | --- | --- | --- |
| 1 | Easy 20 min | Easy 20 min | Easy 25 min |
| 2 | Easy 25 min | Easy 25 min | Easy 30 min |
| 3 | Easy 30 min | Easy 30 min | Easy 35 min |
| 4 | Easy 25 min | Easy 25 min | Easy 30 min |
| 5 | Easy 30 min | 4 x 2 min in Zone 4 | Easy 35 min |
| 6 | Easy 30 min | 4 x 3 min in Zone 4 | Easy 35 min |
| 7 | Easy 30 min | 4 x 4 min in Zone 4 | Easy 40 min |
| 8 | Easy 30 min | 4 x 4 min in Zone 5 | Easy 40 min |

Easy sessions are in Zone 2. Interval sessions add a 10-minute warm-up and 3-minute recoveries in Zone 2, and a 5-minute cool-down in Zone 1.

What the evidence does and does not support:

- The 4 x 4 format comes from Helgerud 2007: 4 minutes at 90 to 95% of maximum with 3-minute recoveries at 70%, three days a week for eight weeks. It raised VO2max more than steady running, by 7.2%.
- That study used moderately trained men, not beginners. The easy base weeks and the shorter, gentler intervals are a cautious design choice, not a tested protocol.
- Week 4 is a lighter week by design choice.
- Its volume stays below the 150 minutes a week of moderate activity in common guidance.

### Talk test

The [talk test tracks the first ventilatory threshold](https://pubmed.ncbi.nlm.nih.gov/25010379/) reasonably well, but evidence on its reliability is [limited](https://pubmed.ncbi.nlm.nih.gov/39076925/) and it may not suit high-intensity intervals. The workout screen offers it as an option and does not prompt during intervals.

### Spoken cues

Android's offline text-to-speech announces each step, so a workout can run with the phone in a pocket. It needs no internet permission, and if no speech engine is available the workout still runs silently.

### Pausing

There is no pause in this version. A workout's clock runs until it is finished.
