package app.haanf.ai

import android.util.Log
import app.haanf.ai.CoachTools.int
import app.haanf.ai.CoachTools.text
import app.haanf.core.Clock
import app.haanf.data.TalkAnswer
import app.haanf.talktest.TrainingActivity
import app.haanf.training.MotionNow
import app.haanf.training.TimedBpm
import app.haanf.workout.Announcer
import app.haanf.workout.TimelineKind
import app.haanf.workout.WorkoutController
import app.haanf.workout.WorkoutState
import app.haanf.workout.WorkoutText
import app.haanf.workout.inHardEffort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Why the coach woke up. Only [UserSaid] may lead to a workout being increased. */
sealed interface Wake {
    val reason: String

    data object Started : Wake {
        override val reason = "The workout has just started. Greet them and say what to focus on."
    }

    data object Timer : Wake {
        override val reason = "Your timer ran out. Check in."
    }

    data class StepChanged(val label: String) : Wake {
        override val reason = "A new step just began: $label."
    }

    data object PlanFinished : Wake {
        override val reason = "The planned workout has just ended. They may keep going or finish."
    }

    data object Dropout : Wake {
        override val reason = "Heart rate readings have stopped arriving. Ask them to check the watch."
    }

    data class Spike(val bpm: Int, val top: Int) : Wake {
        override val reason = "Heart rate is $bpm, above this step's target of up to $top."
    }

    data class UserSaid(val text: String) : Wake {
        override val reason = "The athlete just said: \"$text\""
    }

    data class ClimbStarted(val gradePercent: Int) : Wake {
        override val reason = "A climb has just started, about $gradePercent% gradient."
    }
}

data class CoachStatus(
    val active: Boolean = false,
    val lastMessage: String? = null,
    val thinking: Boolean = false,
    val awaitingTalkAnswer: Boolean = false,
    val offline: Boolean = false,
)

/** What the harness needs from the live workout, without knowing about wearables. */
interface CoachWorkoutLink {
    fun bpm(): Int?
    fun inDropout(): Boolean
    fun sessionId(): Long?
    suspend fun samples(sessionId: Long): List<TimedBpm>
    suspend fun saveTalkCheck(workoutId: Long, bpm: Int?, answer: TalkAnswer)
}

/**
 * The background coach. While a coached workout runs it sleeps, wakes on a timer the AI chose
 * or on an event, tells the AI how things are going, and speaks the reply. Nothing here can
 * stop a workout: local cues and safety alerts never depend on it.
 */
class CoachHarness(
    private val scope: CoroutineScope,
    private val llm: LlmProvider,
    private val workouts: WorkoutController,
    private val data: CoachDataSource,
    private val link: CoachWorkoutLink,
    private val clock: Clock,
    private val announcer: Announcer,
    private val tone: () -> CoachTone,
    private val monitorMs: Long = 5_000,
) {
    private val _status = MutableStateFlow(CoachStatus())
    val status: StateFlow<CoachStatus> = _status.asStateFlow()

    private val wakes = Channel<Wake>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var session: Job? = null
    private var watchedWorkoutId: Long? = null

    /** Watches for coached workouts for as long as the app runs. */
    fun attach() {
        scope.launch {
            workouts.state.collect { state ->
                val running = state as? WorkoutState.Running
                when {
                    running != null && running.request.coached && running.workout.id != watchedWorkoutId -> {
                        watchedWorkoutId = running.workout.id
                        begin(running)
                    }
                    running == null && watchedWorkoutId != null -> end()
                }
            }
        }
    }

    fun userSaid(text: String) {
        if (session?.isActive == true && text.isNotBlank()) {
            workouts.log(TimelineKind.ATHLETE, text.trim(), link.bpm())
            wakes.trySend(Wake.UserSaid(text))
        }
    }

    /** The athlete chose not to answer this talk test. */
    fun skipTalkTest() {
        _status.update { it.copy(awaitingTalkAnswer = false) }
    }

    /** A tapped answer. A spoken one arrives through [userSaid] and the record tool instead. */
    fun answerTalkTest(answer: TalkAnswer) {
        val running = workouts.state.value as? WorkoutState.Running ?: return
        val bpm = link.bpm()
        scope.launch { link.saveTalkCheck(running.workout.id, bpm, answer) }
        workouts.log(TimelineKind.TALK_TEST, answer.summary.replaceFirstChar { it.uppercase() } + ".", bpm)
        _status.update { it.copy(awaitingTalkAnswer = false) }
        wakes.trySend(Wake.UserSaid("Talk test answer: ${answer.summary}."))
    }

    private fun begin(running: WorkoutState.Running) {
        val client = llm.client() ?: return
        var stale = wakes.tryReceive()
        while (stale.isSuccess) stale = wakes.tryReceive()
        _status.value = CoachStatus(active = true)
        session = scope.launch {
            val snapshot = data.snapshot()
            val context = snapshot?.let { CoachContext.render(it) }.orEmpty()
            val turn = CoachTurn(client, context, running)
            launch { watchForEvents(turn) }
            coachLoop(turn)
        }
    }

    private fun end() {
        session?.cancel()
        session = null
        watchedWorkoutId = null
        _status.update { it.copy(active = false, thinking = false, awaitingTalkAnswer = false) }
    }

    private suspend fun coachLoop(turn: CoachTurn) {
        var wake: Wake = Wake.Started
        var nextSleepMs = DEFAULT_SLEEP_MS
        var calls = 0
        var failures = 0
        var lastCallMs = Long.MIN_VALUE / 2
        while (currentCoroutineContext().isActive) {
            val mustAnswer = wake is Wake.UserSaid
            val spare = calls < MAX_CALLS
            if (mustAnswer || spare) {
                val waited = clock.elapsedMs() - lastCallMs
                if (!mustAnswer && waited < MIN_GAP_MS) delay(MIN_GAP_MS - waited)
                lastCallMs = clock.elapsedMs()
                calls++
                val chosen = try {
                    _status.update { it.copy(thinking = true) }
                    turn.run(wake)
                } catch (e: LlmException) {
                    Log.w(TAG, "Coach call failed: ${e.failure}")
                    null
                } finally {
                    _status.update { it.copy(thinking = false) }
                }
                failures = if (chosen == null) failures + 1 else 0
                if (failures >= MAX_FAILURES) {
                    announcer.say("The coach is offline. Your workout carries on.")
                    _status.update { it.copy(active = false, offline = true) }
                    session?.cancel()
                    return
                }
                nextSleepMs = chosen ?: DEFAULT_SLEEP_MS
            }
            wake = withTimeoutOrNull(nextSleepMs) { wakes.receive() } ?: Wake.Timer
        }
    }

    /** Turns what is happening into wake-ups, however long the AI's sleep is. */
    private suspend fun watchForEvents(turn: CoachTurn) {
        var lastStep = turn.state()?.stepIndex
        var planWasDone = false
        var wasInDropout = false
        var overSinceMs: Long? = null
        var lastSpikeMs = Long.MIN_VALUE / 2
        var wasClimbing = false
        while (currentCoroutineContext().isActive) {
            delay(monitorMs)
            val running = turn.state() ?: return
            val now = clock.elapsedMs()
            val climbing = running.motion?.climbing == true
            if (climbing && !wasClimbing) {
                wakes.trySend(Wake.ClimbStarted((running.motion.gradePercent ?: 0.0).roundToInt()))
            }
            wasClimbing = climbing
            if (running.stepIndex != null && running.stepIndex != lastStep) {
                lastStep = running.stepIndex
                val label = running.request.plan?.steps?.get(running.stepIndex)?.label.orEmpty()
                wakes.trySend(Wake.StepChanged(label))
            }
            if (running.planComplete && !planWasDone) {
                planWasDone = true
                wakes.trySend(Wake.PlanFinished)
            }
            val dropout = link.inDropout()
            if (dropout && !wasInDropout) wakes.trySend(Wake.Dropout)
            wasInDropout = dropout

            val top = turn.targetTop(running)
            val bpm = link.bpm()
            if (top != null && bpm != null && bpm > top + SPIKE_MARGIN_BPM) {
                val since = overSinceMs ?: now.also { overSinceMs = it }
                if (now - since >= SPIKE_HOLD_MS && now - lastSpikeMs >= SPIKE_REPEAT_MS) {
                    lastSpikeMs = now
                    wakes.trySend(Wake.Spike(bpm, top))
                }
            } else {
                overSinceMs = null
            }
        }
    }

    /**
     * The coach's conversation for one workout. "About you" and the workout go first, once, and
     * each check-in is added below with the time and what happened since the last one. Nothing
     * earlier is ever changed, so the provider can reuse it and the coach remembers what was said.
     */
    private inner class CoachTurn(
        private val client: LlmClient,
        private val athlete: String,
        private val started: WorkoutState.Running,
    ) {
        private val history = mutableListOf<LlmItem>()
        private var lastCheckInSeconds: Int? = null
        private var timelineSeen = 0

        fun state(): WorkoutState.Running? =
            (workouts.state.value as? WorkoutState.Running)?.takeIf { it.workout.id == started.workout.id }

        fun targetTop(running: WorkoutState.Running): Int? {
            val step = running.stepIndex?.let { running.request.plan?.steps?.get(it) } ?: return null
            return running.request.zones.rangeFor(step.target).last
        }

        /** Returns how long to sleep, or null if the AI said nothing. */
        suspend fun run(wake: Wake): Long? {
            val running = state() ?: return DEFAULT_SLEEP_MS
            var nextMs: Long? = null
            val adjustable = running.request.ai != null && running.request.plan != null
            val tools = buildList {
                add(SPEAK)
                add(HEART_RATE_OVERVIEW)
                add(RECORD_TALK_TEST)
                if (adjustable) add(ADJUST_WORKOUT)
            }
            val checkIn = checkInText(running, wake)
            val asked = if (history.isEmpty()) {
                LlmItem.User(athlete + "\n\n" + workoutText(running) + "\n\n" + checkIn)
            } else {
                LlmItem.User(checkIn)
            }
            // Kept even if the call fails, so nothing reported is lost before the next check-in.
            history += asked
            val request = LlmRequest(
                system = CoachPrompts.coach(tone()),
                items = history.toList(),
                tools = tools,
                effort = LlmEffort.LOW,
                cacheable = true,
            )
            val conversation = runToolConversation(client, request, maxRounds = MAX_ROUNDS, isDone = { nextMs != null }) { call ->
                when (call.name) {
                    SPEAK.name -> {
                        nextMs = speak(call.input)
                        ToolOutcome(call.id, "Spoken at ${WorkoutText.clock(elapsed())}.")
                    }
                    HEART_RATE_OVERVIEW.name -> ToolOutcome(call.id, overview(call.input))
                    RECORD_TALK_TEST.name -> recordTalkTest(call, running)
                    ADJUST_WORKOUT.name -> adjust(call, wake)
                    else -> ToolOutcome(call.id, "Unknown tool.", isError = true)
                }
            }
            history.clear()
            history += conversation.items
            return nextMs
        }

        private fun elapsed(): Int = state()?.elapsedSeconds ?: 0

        private fun speak(input: JsonObject): Long {
            val message = input.text("message").orEmpty().trim().take(MAX_SPOKEN_CHARS)
            if (message.isNotEmpty()) {
                announcer.say(message)
                workouts.log(TimelineKind.COACH, message, link.bpm())
            }
            // Enforced here as well as in the prompt: only when switched on, never in a hard effort.
            val running = state()
            val allowed = running != null && running.request.talkChecks && !running.inHardEffort(link.bpm())
            val asked = allowed && input["ask_talk_test"]?.toString() == "true"
            _status.update { it.copy(lastMessage = message, awaitingTalkAnswer = asked || it.awaitingTalkAnswer) }
            val minutes = (input.int("next_check_in_minutes") ?: DEFAULT_SLEEP_MINUTES)
                .coerceIn(MIN_SLEEP_MINUTES, MAX_SLEEP_MINUTES)
            return minutes * 60_000L
        }

        private suspend fun overview(input: JsonObject): String {
            val running = state() ?: return "The workout has ended."
            val minutes = (input.int("last_minutes") ?: 5).coerceIn(1, 120)
            val samples = link.sessionId()?.let { link.samples(it) }.orEmpty()
            return WorkoutOverview.describe(
                samples = samples,
                motion = workouts.motionSamples(),
                workoutStartMs = running.workout.startedAtMs,
                nowSeconds = running.elapsedSeconds,
                lastMinutes = minutes,
                zones = running.request.zones,
                ride = running.request.activity == TrainingActivity.CYCLING,
            )
        }

        private suspend fun recordTalkTest(call: ToolCall, running: WorkoutState.Running): ToolOutcome {
            val answer = TalkAnswer.entries.firstOrNull { it.name == call.input.text("answer") }
                ?: return ToolOutcome(call.id, "Unknown answer.", isError = true)
            val bpm = link.bpm()
            link.saveTalkCheck(running.workout.id, bpm, answer)
            workouts.log(TimelineKind.TALK_TEST, answer.summary.replaceFirstChar { it.uppercase() } + ".", bpm)
            _status.update { it.copy(awaitingTalkAnswer = false) }
            return ToolOutcome(call.id, "Saved.")
        }

        private suspend fun adjust(call: ToolCall, wake: Wake): ToolOutcome {
            val running = state() ?: return ToolOutcome(call.id, "The workout has ended.", isError = true)
            val plan = running.request.plan
            val ai = running.request.ai
            if (plan == null || ai == null) return ToolOutcome(call.id, "This workout cannot change.", isError = true)
            val change = PlanChange.entries.firstOrNull { it.name == call.input.text("change") }
                ?: return ToolOutcome(call.id, "Unknown change.", isError = true)
            // Enforced here so that no prompt or model can talk its way into an increase.
            if (change.isIncrease && wake !is Wake.UserSaid) {
                return ToolOutcome(call.id, "Rejected: increases need the athlete to have just asked.", isError = true)
            }
            val minutes = (call.input.int("minutes") ?: 0).coerceAtLeast(0)
            val edited = PlanEdits.apply(plan, running.elapsedSeconds, change, minutes)
            val allowance = if (change.isIncrease) ai.minutesAvailable + ASKED_FOR_EXTRA_MINUTES else ai.minutesAvailable
            val problems = AiWorkoutRules.checkChange(
                before = PlanEdits.toProposal(plan, ""),
                after = PlanEdits.toProposal(edited, ""),
                increase = change.isIncrease,
                context = data.ruleContext(allowance, ai.feeling),
            )
            if (problems.isNotEmpty()) {
                return ToolOutcome(call.id, "Rejected by the safety check: ${problems.joinToString(" ")}", isError = true)
            }
            workouts.replacePlan(edited)
            workouts.log(TimelineKind.PLAN_CHANGE, changeText(change, minutes), link.bpm())
            Log.i(TAG, "Plan changed: $change $minutes")
            return ToolOutcome(call.id, "Applied. Now tell them what changed with speak.")
        }

        private fun changeText(change: PlanChange, minutes: Int): String = when (change) {
            PlanChange.SHORTEN -> "The coach cut $minutes minutes from the plan."
            PlanChange.EASE -> "The coach made the rest of the plan easier."
            PlanChange.EXTEND -> "The coach added $minutes minutes, as the athlete asked."
            PlanChange.INTENSIFY -> "The coach made the rest of the plan harder, as the athlete asked."
        }

        /** Said once, at the top of the conversation. */
        private fun workoutText(running: WorkoutState.Running): String = buildString {
            val request = running.request
            val startedAt = TIME_OF_DAY.format(Instant.ofEpochMilli(running.workout.startedAtMs).atZone(ZoneId.systemDefault()))
            append("The workout: ${request.title}, ${request.activity.label.lowercase()}, started at $startedAt. ")
            append("Times in square brackets are minutes and seconds into the workout.\n")
            val plan = request.plan
            if (plan == null) {
                append("No plan: the athlete goes as they like.\n")
            } else {
                append("The plan:\n")
                var at = 0
                plan.steps.forEach { step ->
                    val range = request.zones.rangeFor(step.target)
                    append("[${WorkoutText.clock(at)}] ${step.label}, ${WorkoutText.clock(step.seconds)}, ")
                    append("${step.target.label} ${range.first}-${range.last} bpm\n")
                    at += step.seconds
                }
            }
            if (request.ai == null) append("This workout is fixed: you may not change it.\n")
            when {
                request.activity == TrainingActivity.TREADMILL ->
                    append("Indoors on a treadmill: the phone cannot measure speed, distance or incline.\n")
                request.usesGps || request.benchmark?.usesGps == true -> {
                    append("The phone measures distance and speed by GPS, and climb by its barometer when it has one.")
                    request.bike?.takeIf { request.activity == TrainingActivity.CYCLING }?.let {
                        append(" They ride a ${it.label.lowercase()}. Watts are an estimate from speed, gradient and weight, least accurate in wind.")
                    }
                    append("\n")
                }
                else -> append("The phone is not measuring distance or speed for this workout.\n")
            }
            append(
                if (request.talkChecks) "Talk test checks are on: you may ask during easy efforts, now and then.\n"
                else "Talk test checks are off: do not ask for one.\n",
            )
        }

        /** Each check-in: the time, why, the state now, and what happened since the last one. */
        private fun checkInText(running: WorkoutState.Running, wake: Wake): String = buildString {
            val now = running.elapsedSeconds
            val plan = running.request.plan
            append("[${WorkoutText.clock(now)}] ${wake.reason}\n")
            val bpm = link.bpm()
            if (bpm == null) {
                append("Heart rate now: no recent reading.\n")
            } else {
                append("Heart rate now: $bpm bpm, ${running.request.zones.zoneFor(bpm).label}.\n")
            }
            running.stepIndex?.let { index ->
                val step = requireNotNull(plan).steps[index]
                val range = running.request.zones.rangeFor(step.target)
                append("Current step: ${step.label}, ${WorkoutText.clock(running.stepRemainingSeconds ?: 0)} left, ")
                append("target ${range.first}-${range.last} bpm.\n")
                plan.steps.getOrNull(index + 1)?.let { append("Next step: ${it.label}.\n") }
            }
            if (plan != null && running.planComplete) append("The planned workout is over.\n")
            running.motion?.let { motion -> motionText(motion, running)?.let { append(it).append("\n") } }
            val timeline = workouts.timeline
            val since = timeline.drop(timelineSeen).filter { !it.kind.isSaid }
            timelineSeen = timeline.size
            if (since.isNotEmpty()) {
                val from = lastCheckInSeconds?.let { " at ${WorkoutText.clock(it)}" }.orEmpty()
                append("Since your last check-in$from:\n")
                since.forEach { append(it.line()).append("\n") }
            }
            lastCheckInSeconds = now
            if (running.request.talkChecks) {
                append(
                    if (running.inHardEffort(bpm)) "This is a hard effort: do not ask for a talk test now.\n"
                    else "You may ask for a talk test now, if it has been a while.\n",
                )
            }
        }

        private fun motionText(motion: MotionNow, running: WorkoutState.Running): String? {
            val ride = running.request.activity == TrainingActivity.CYCLING
            val parts = mutableListOf<String>()
            if (motion.hasGps) {
                parts += "Distance ${WorkoutText.km(motion.distanceMetres)} km, moving ${WorkoutText.clock(motion.movingSeconds)}."
                val speed = motion.speedMps
                parts += when {
                    speed == null -> "No GPS speed just now."
                    ride -> "Speed ${WorkoutText.kmh(speed)} km/h."
                    else -> {
                        val gap = motion.gapSpeedMps?.let(WorkoutText::paceOf)?.let { ", grade-adjusted $it /km" }.orEmpty()
                        "Pace ${WorkoutText.paceOf(speed) ?: "standing"} /km$gap."
                    }
                }
            } else if (running.request.usesGps) {
                parts += "GPS has no fix yet."
            }
            if (motion.hasBarometer) {
                motion.gradePercent?.let { parts += "Gradient ${it.roundToInt()}%." }
                if (motion.climbing) {
                    val gain = motion.climbSoFarMetres?.roundToInt() ?: 0
                    parts += "Climbing for ${WorkoutText.clock(motion.climbSeconds ?: 0)}, $gain m up so far."
                }
                parts += "Climbed ${motion.climbMetres.roundToInt()} m and descended ${motion.descentMetres.roundToInt()} m so far."
            }
            motion.watts?.let { parts += "Estimated power $it W." }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }

    companion object {
        private const val TAG = "HaanfCoach"
        const val MAX_CALLS = 15
        const val MIN_GAP_MS = 60_000L
        const val MAX_FAILURES = 3
        const val MIN_SLEEP_MINUTES = 2
        const val MAX_SLEEP_MINUTES = 10
        private const val DEFAULT_SLEEP_MINUTES = 5
        private const val DEFAULT_SLEEP_MS = DEFAULT_SLEEP_MINUTES * 60_000L
        private const val MAX_ROUNDS = 4
        private const val MAX_SPOKEN_CHARS = 300
        private const val SPIKE_MARGIN_BPM = 8
        private const val SPIKE_HOLD_MS = 45_000L
        private const val SPIKE_REPEAT_MS = 180_000L
        private const val ASKED_FOR_EXTRA_MINUTES = 15
        private val TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm")

        val SPEAK = ToolSpec(
            name = "speak",
            description = "Say something to the athlete, and set when to check in next. This is the " +
                "only way to be heard.",
            properties = buildJsonObject {
                put("message", CoachTools.string("One short spoken sentence, or two at most."))
                put(
                    "next_check_in_minutes",
                    CoachTools.integer("Minutes until you check in again, from 2 to 10."),
                )
                putJsonObject("ask_talk_test") {
                    put("type", "boolean")
                    put("description", "True if your message asks whether they could talk comfortably.")
                }
            },
        )

        val HEART_RATE_OVERVIEW = ToolSpec(
            name = "heart_rate_overview",
            description = "Get the recent minutes in timed steps: heart rate, and speed or pace, climb and " +
                "estimated watts when the phone measures them. Ends with averages and time in zones.",
            properties = buildJsonObject {
                put("last_minutes", CoachTools.integer("How many recent minutes to cover."))
            },
        )

        val RECORD_TALK_TEST = ToolSpec(
            name = "record_talk_test",
            description = "Save the athlete's spoken answer to a talk test.",
            properties = buildJsonObject {
                put("answer", CoachTools.choice("How talking felt.", TalkAnswer.entries.map { it.name }))
            },
        )

        val ADJUST_WORKOUT = ToolSpec(
            name = "adjust_workout",
            description = "Change what is left of this workout. SHORTEN and EASE are for when the metrics " +
                "suggest easing off. EXTEND and INTENSIFY are allowed only if the athlete just asked.",
            properties = buildJsonObject {
                put("change", CoachTools.choice("The change.", PlanChange.entries.map { it.name }))
                put("minutes", CoachTools.integer("Minutes to remove or add. Use 0 for EASE and INTENSIFY."))
            },
        )
    }
}
