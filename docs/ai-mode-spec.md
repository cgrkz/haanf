# AI mode spec

Agreed with the user on 2 November 2026. Items marked **default** were not asked about. They are choices made during the build, so change them freely.

## Status

Built on 2 November 2026.

| Area | Result | Evidence |
| --- | --- | --- |
| Unit tests | Pass | 208 of 208 |
| Emulator tests | Pass | 33 of 33, including all database migrations and the full loop from plan to coach to review, with a scripted AI |
| Planner, coach, chat and reviews against a real model | Pass (development) | Hinoki with made-up data, over the same Chat Completions path OpenRouter uses. It called the heart rate overview, then spoke, and chose its own next check-in. Its plan passed the rule check, and chat and review text was clean |
| Claude and OpenRouter from Android | Pass, as far as a fake key can show | Real calls from the emulator reached both, and both rejected a fake key, which Haanf reported correctly |
| Upgrade on the real Pixel 6 | Pass (real) | Installed with no crash. Database moved from version 3 to 4, and all sessions, samples, the talk test, the workout and the profile survived |
| Hinoki on Android | Pass (emulator) | A real chat message with made-up data got a good reply through the app |
| Hinoki key on the real Pixel 6 | Pass (real) | Loaded over adb, encrypted, file deleted. Key is not in plain text on the phone, in the APK or in git. No real AI call has run on the phone yet |
| A real OpenRouter or Claude key | Not tested | None was available. Whether a real call succeeds is unproven |
| Claude's tool loop against the real API | Not tested | Tested against a local server only |
| Coach during a real workout with the real watch | Not tested | |
| On-device speech on a real phone | Not tested | The emulator has none, so it showed the quick replies |
| Spoken coach through earbuds or a phone speaker | Not tested | |

## What the AI does

1. **Creates workouts.** You say how much time you have and how you feel. It reads your history and recovery, and proposes a workout.
2. **Guides you during workouts.** A background coach speaks to you while you train.
3. **Reviews workouts** after you finish.
4. **Talks with you.** One ongoing chat that remembers what matters.
5. **Calls for benchmarks.** It says when a repeat is due. The test itself stays fixed. Paused with the benchmark since 7 November 2026; see [benchmarks.md](benchmarks.md).

## Providers and keys

- Bring your own key. Choose OpenRouter or Claude.
- A subscription can replace this later, so providers sit behind one interface.
- Keys are encrypted on the phone with the Android Keystore. They are never in the APK, in git or in backups.
- Without a key, everything else in Haanf works as before.
- Model is a setting. **Default:** Claude Opus 5.5.
- **Hinoki** is a third provider, for the user's own testing. It was first planned as development-only, and the user then asked for it in the app on 3 November 2026. When selected, real profile, health and workout data goes to it. It is a security-focused model, so its coaching quality is unproven.
- A tester's key can be placed on a phone over adb, into private storage. The app encrypts it and deletes the file. This keeps a personal key out of an APK that gets shared with friends. See `docs/install-on-a-phone.md`.

## What the AI receives

The AI receives:
- your profile, injuries and health conditions, and your chat facts;
- benchmarks, and resting heart rate, HRV and sleep from Health Connect;
- past workouts with their metrics: distance, speed, climb, estimated effort and your effort rating;
- today's check-in.

During a workout it also gets current heart rate, speed or pace, gradient and climb, and it can ask for a timed overview of the recent minutes.

Workouts under two minutes are left out of the list, because a start that lasted seconds says nothing about fitness. Abandoned ones are marked, and benchmark walks are called walks.

Never sent: location, height above sea level, device identifiers, or audio. **Default:** setup shows this list and asks for agreement before the first AI call.

This ends Haanf's "no internet" promise. It becomes: data leaves the phone only through your export, or to the AI provider you chose with your own key.

## The background coach

The harness runs while a workout runs, in the foreground service, so it works with the screen off.

1. The workout starts. The coach speaks and chooses when to check in next.
2. It sleeps while heart rate records.
3. When it wakes, it tells the AI:
   - the time into the workout;
   - heart rate, and speed or pace, gradient and climb;
   - everything that happened since the last check-in, each with its time.

   The AI can call a tool for the recent minutes in timed steps.
4. The AI replies. The reply is spoken aloud with the phone's offline voice.
5. It sleeps again.

**Timing:** the AI picks each sleep, within 2 to 10 minutes. These events wake it early: a step change, a heart rate spike, a dropout, the start of a climb, or you speaking.

**Limits (default):** at most 15 calls per workout, and at least 60 seconds between calls. When you speak, it always answers, even past the limit.

**One conversation per workout,** since slice 1.
- "About you" and the plan go at the top, once. Each check-in is added below, so the coach remembers what you told it.
- Earlier turns are sent back unchanged, which a model's thinking requires.
- With the Claude provider, the conversation is cached for an hour, so rereading it costs about 5% of the normal price.
- A workout is at most 15 calls, so the conversation stays small.

**The workout timeline.** Each workout keeps a saved, timed record. It holds:
- what the coach said, what you said, and talk test answers;
- plan changes;
- steps, heart rate alerts, dropouts, kilometres and climbs.

Events between check-ins are delivered at the next one, with their times. The same record appears on the summary as "What you and the coach said", and the review reads it.

**What it speaks:** only the message the AI passes to a `speak` tool, so a model's private reasoning can never be read aloud.

**If the AI fails:** the workout carries on. After 3 failures in a row the coach says it is offline and stops. Local cues and safety alerts never depend on the AI.

**Which workouts (default):** AI, program and free workouts can use the coach. It stays silent during benchmarks, because the protocol is fixed. It can be switched off before any workout.

## What the coach may change

| Workout | Ease off, based on metrics | Increase, based on metrics | Increase, because you asked |
| --- | --- | --- | --- |
| Program | No | No | No |
| AI | Yes | No | Yes |

Every change must pass the rule check. Enforced in code, not only in the prompt: an increase is accepted only in a turn triggered by you speaking. Easing off moves hard steps down one zone, no lower than Zone 2. Making it harder moves steps up one zone, never into Zone 5.

## Talking to the coach

- A big button on the workout screen and in the notification. Tap, then speak.
- Speech is transcribed on the phone, and only text goes to the AI. This needs Android 12 or newer.
- **Default:** if a phone has no on-device speech, it shows quick replies instead: Too easy, OK, Too hard.

## Talk test

When "Talk test checks" is on in Get ready, the coach can ask whether you could talk comfortably, never during a hard effort. With planned steps, the step decides what is hard. Without them, heart rate does: Zone 3 and above means no talk test. You answer by voice or with one of three buttons, or skip. No audio leaves the phone. The answer is saved with your heart rate, which builds the data for your talking threshold.

## Chat that remembers

- One ongoing conversation, typed or dictated.
- The AI can propose saving a lasting fact, such as "left knee sore since October". It is saved only after you confirm.
- Facts can be marked resolved, and you can edit them.
- Injuries and conditions can also be entered at setup. **Default:** one optional free-text field.

**Looking up workouts.** Each message carries the recent workouts in summary, each with its id. When a question needs more, chat uses two tools instead of receiving every detail with every message:
- `find_workouts` lists saved workouts by activity, kind and dates, newest first, 20 at most, with their ids. Workouts under two minutes are never listed.
- `open_workouts` opens up to three workouts in full: the same totals, kilometres or five-minute heart rate, and timeline the review reads, plus your effort rating and the review written afterwards. Opening two together is how chat compares them, such as two rides up the same climb.

These tools only read. What they return reaches the AI provider like the rest of the chat, and holds nothing about where you were. Opened workouts are not kept in the chat history, so a later question opens them again.

## Coach tone

Chosen while configuring the coach: warm and encouraging, calm and factual, or tough and pushy.

Whatever the tone, every feature gets the same instruction on how to talk: each sentence is about this athlete today, with a number, a reason or an instruction. Praise not tied to something they did, stock reminders, restating what they said and offers of more help are left out. Chat answers in its first sentence and usually stops within three. The review explains the numbers on the summary rather than reading them back. On 10 November 2026 the old and new wording were compared on Claude Haiku 5.5 with made-up data, in two tones. Chat replies to five questions fell from 44 to 267 words, with bullet lists and bold, to 10 to 90 words of plain text. Live lines fell from about 30 to 40 words to about 20 to 25. It has not been heard in a real workout.

## The rule check

A separate check, with no AI in it, approves every AI workout and every change.

From the brief:

- Hard sessions are rejected the day before or after a hard program session. Program sessions have no fixed dates, so "the day before" is treated as: the next program session is a hard one.

**Defaults, all open to change:**

- Fits the time you said you have, and is at least 5 minutes long.
- At most 2 hard sessions in any 7 days, counting every kind of workout.
- Hard work is any step in Zone 3 or above. Hard sessions start with at least 5 minutes in Zone 1 or 2 and end with at least 3.
- No hard work until 6 workouts are logged, and no Zone 5 work until 12.
- No hard work if you feel 1 or 2 out of 5.
- A Zone 5 step lasts at most 4 minutes, matching the 4 x 4 protocol.
- A benchmark repeat is allowed from 28 days after the last one.

**Injuries do not block anything.** The AI reads them and adapts. This was the user's choice.

**When a workout is rejected:** the AI is told why and tries again, twice at most. After that, Haanf offers a plain easy workout that fits your time.

## Reviews

**Default:** a review runs automatically after each workout if a key is set, and appears on the summary and in History. If it fails, a Retry button appears. Benchmarks are reviewed too.

**What the review reads:**
- the workout's totals;
- each kilometre, or heart rate in five-minute steps without GPS;
- the timeline, word for word.

The workout under review is not also in the workout list. The review is asked for under 90 words, explains heart rate with the terrain, and does not repeat the coach's cues.

**Remembering from a review.** If you mentioned pain or an injury during the workout, the review may propose remembering it. You confirm it in chat, as with any fact.

## Where it lives

The Coach tab, separate from the Program tab, because the brief keeps the two apart. It opens on a check-in: time, feel and activity, with last night's recovery. "Find my workout" runs the planner, and the recommendation offers "Make it easier" and "Something else", which both go back through the planner and the rule check and never add work. Chat and "What the coach knows" are one screen, opened from the top of the Coach tab. Provider, key, model and tone are also in Settings. AI workouts need a first benchmark, since the AI has no baseline without one. While the benchmark is paused, they don't.

## Not in this version

- Analysing talk test audio.
- Subscriptions.
- A second opinion on safety beyond the rule check. The coach gives fitness guidance, not medical advice.
