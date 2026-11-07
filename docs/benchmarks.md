# Benchmarks

**Paused on 7 November 2026.** The benchmark is switched off with `Features.BENCHMARK` in `app/src/main/java/app/haanf/Features.kt`. While it is off:

- first run ends after the watch step;
- the program and the coach open without a score;
- Progress has no VO2max card;
- the planner cannot suggest one.

The code, its tests and any saved results stay, and the AI still sees past scores. Turning the switch back on restores everything below.

A benchmark is a fixed, research-based fitness test. The AI will choose when and which, never the protocol. Every user does one right after setup, because it tells Haanf how fit they are before any training starts.

## The flow

1. Setup asks for year of birth, weight and sex, which the test's formula needs.
2. A two-statement health check follows. If either applies, Haanf advises seeing a doctor first, and the user can continue only by confirming their doctor has cleared them. Only the outcome is saved, never which statement applied.
3. The benchmark is the last step of first run, and stays on the Program tab until it scores. The program stays locked until then. Free workouts stay available.
4. A mile cannot be walked during setup, so "right after setup" means required next step, not the same minute.

There are no accounts: setup is local to the phone.

## The test: Rockport 1-mile walk

Walk one mile as fast as possible without running, then read heart rate near the end. The score is an estimated VO2max.

Why this test:

- **Safe for the people Haanf is for.** A brisk walk suits amateurs and people with low fitness. A maximal test on day one would not.
- **Reports VO2max,** which is what the programs aim to raise, so improvement appears in the same units.
- **Needs little.** Heart rate, a stopwatch and a measured mile.

Alternatives rejected:

| Test | Why not |
| --- | --- |
| Cooper 12-minute run | An all-out effort, a poor first day for someone new to cardio |
| YMCA 3-minute step test | Needs a 30 cm (12-inch) step, taller than a household stair, and agreement with measured VO2 was weaker in [one study](https://cuir.car.chula.ac.th/handle/123456789/56594), especially in women |

## The equation

From [Kline 1987](https://pubmed.ncbi.nlm.nih.gov/3600239/), using the equation in the abstract:

```
VO2max (L/min) = 6.9652 + 0.0091 x weight_lb - 0.0257 x age + 0.5955 x sex - 0.2240 x minutes - 0.0115 x hr
```

Sex is 1 for male and 0 for female. `hr` is the heart rate over the fourth quarter of the walk, which Haanf averages from the last quarter of the time. The result is divided by body weight to give mL/kg/min.

The formula needs a binary sex value, so setup words the question as "Sex used by the fitness formula".

**A conflict between sources.** A common form uses 132.853 as the constant and adds 6.315 for males. One site instead gives 139.168 as the female constant. Working Kline's own equation for a 40-year-old, 180 lb man with a 15-minute mile at 150 bpm gives 37.8. The 132.853 form gives 37.4, and the 139.168 reading gives about 43.7. So that site is wrong, and a test pins this example.

## Rules Haanf applies

- A walk under 8 minutes or over 40 minutes is not scored.
- At least 3 heart rate readings are needed in the last quarter of the walk. The Pixel Watch 2 gives about one every 2 seconds, so this is easily met.
- An abandoned walk stays in history but has no score.
- A benchmark never touches the program clock.
- Heart rate and weight used for each score are saved, so a result can always be explained.

## Measuring the mile

- **Outdoors:** the phone's GPS measures the distance, and Haanf ends the walk at 1,609 m. It uses GPS directly, with no Google services. Only the running total is kept, never a route. Poor fixes, impossible jumps and standing still add nothing.
- **Treadmill:** the user taps Finish when the console shows 1 mile or 1.61 km.

GPS distance can be off by a few percent, and a short mile raises the score. A treadmill or a marked track is more exact.

## Comparing the result

The level compares the score with treadmill percentiles from the [FRIEND registry](https://pubmed.ncbi.nlm.nih.gov/26455884/) (Kaminsky 2015), by age decade and sex.

| Level | Percentile |
| --- | --- |
| Well below average | Under the 10th |
| Below average | 10th to 25th |
| Average | 25th to 75th |
| Above average | 75th to 90th |
| Well above average | 90th and over |

**How the table was checked.** The paper's own table could not be read: PubMed Central requires a CAPTCHA, and the publisher blocked the request. The values come from two independent reproductions, [Catalyst Performance](https://catalystperformance.sg/tools/vo2-max-calculator) and [A Faster You](https://afasteryou.com/blog/en/vo2max-table/), which agree on every value. The four medians in the paper's abstract also match. Compare them with the paper's Table 3 before any wider release. A 2022 update to the registry reports lower treadmill medians.

## What the score can and cannot tell you

- **It is an estimate,** not a lab result. In Kline's cross-validation, the standard error was about 0.36 L/min. For a 75 kg person, that is roughly 5 mL/kg/min either way.
- **The original study covered ages 30 to 69.** Haanf also scores younger users, and accuracy there is less certain.
- **It has been weaker in some groups:** [low correlation in women aged 70 to 79](https://periodicos.ufsc.br/index.php/rbcdh/article/view/1980-0037.2009v11n3p364), though repeat results were consistent.
- **Very fit people can outgrow it.** A walk may be too easy for them. A harder test can be added later.
- **The comparison is imperfect.** The score is estimated from a walk, while the norms are measured on treadmills in US adults.
- **Treadmill walks differ slightly from the track walks** the equation was built on.

The most reliable use is comparing a person with themselves over time, in the same place, on the same measured distance.

## Not built yet

- A schedule for repeats. The coach can say a benchmark is due, and the user can start one from Progress, under See benchmarks.
- A harder test for fit users.
- Any change to the program based on the score. It is recorded as a baseline only.
