# Phase 1 plan

Phase 0 proved that the Pixel Watch 2 streams live heart rate, so the user asked to move on from testing and keep building. No next phase had been defined, so this one comes from the product brief. Redirect it freely.

## Status

Built on 2 November 2026.

| Area | Result | Evidence |
| --- | --- | --- |
| Unit tests | Pass | 99 of 99, from a fresh clone with no cache |
| Emulator tests | Pass | 16 of 16, including the database migration and program saving |
| Full workout flow | Pass (simulated) | First launch, enrol, program session against a simulated strap, background, notification, early finish, history |
| Upgrade on the real Pixel 6 | Pass (real) | Installed over Phase 0 data with no crash. Session 2 with its 221 readings, the talk test and its audio file all survived |
| Spoken cues | Pass on the emulator only | The speech engine ran. Not heard on a real phone |
| Workout with the real watch | Not tested | |
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
- **First benchmark:** added at the user's request. It follows setup and unlocks the program. See [benchmarks.md](benchmarks.md).

## Out of scope, and why

- **AI mode and the AI coach.** Phase 0's limits on AI calls, backends and paid services were not lifted, and they need a decision from the user on provider and cost.
- **Repeat benchmarks chosen by the AI,** and tests beyond the first one.
- **Final design.** The screens stay plain.

The data model leaves room for AI workouts: `Workout.origin` separates them, and only program workouts touch the program clock.

## Decisions

### Zones

Percentages of maximum heart rate, using the American Heart Association's bands for moderate and vigorous activity, plus a top band for intervals.

| Zone | Share of maximum | Basis |
| --- | --- | --- |
| Recovery | Below 50% | Below the moderate band |
| Easy | 50 to 70% | [AHA](https://www.heart.org/en/healthy-living/fitness/fitness-basics/target-heart-rates) moderate activity |
| Hard | 70 to 85% | AHA vigorous activity |
| Very hard | 85% and above | Top of the range, used for the final week's intervals |

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
| 5 | Easy 30 min | 4 x 2 min hard | Easy 35 min |
| 6 | Easy 30 min | 4 x 3 min hard | Easy 35 min |
| 7 | Easy 30 min | 4 x 4 min hard | Easy 40 min |
| 8 | Easy 30 min | 4 x 4 min very hard | Easy 40 min |

Interval sessions add a 10-minute warm-up, 3-minute easy recoveries and a 5-minute cool-down.

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
