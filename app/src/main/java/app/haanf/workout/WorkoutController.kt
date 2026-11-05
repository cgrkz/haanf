package app.haanf.workout

import app.haanf.core.Clock
import app.haanf.data.BenchmarkEntity
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.DistanceTracker
import app.haanf.training.HeartRateRecovery
import app.haanf.training.HeartRateZones
import app.haanf.training.LocationFix
import app.haanf.training.OverMaxMonitor
import app.haanf.training.RockportWalkTest
import app.haanf.training.SafetyRules
import app.haanf.training.TimedBpm
import app.haanf.training.WalkOutcome
import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutSummaries
import app.haanf.training.WorkoutSummary
import app.haanf.training.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class WorkoutOrigin { FREE, PROGRAM, BENCHMARK, AI }

data class ProgramSlot(val programId: String, val week: Int, val session: Int)

data class BenchmarkRequest(
    val inputs: BenchmarkInputs,
    val usesGps: Boolean,
)

/** A workout the AI planned, with what the rule check needs for any later change. */
data class AiWorkoutContext(
    val minutesAvailable: Int,
    val feeling: Int,
)

data class WorkoutRequest(
    val title: String,
    val activity: TrainingActivity,
    val device: HeartRateDevice,
    val zones: HeartRateZones,
    val plan: WorkoutPlan? = null,
    val slot: ProgramSlot? = null,
    val benchmark: BenchmarkRequest? = null,
    val ai: AiWorkoutContext? = null,
    val coached: Boolean = false,
    val plannedHard: Boolean = false,
    val spokenCues: Boolean = true,
    val talkChecks: Boolean = false,
)

sealed interface WorkoutState {
    data object Idle : WorkoutState

    data class Running(
        val workout: WorkoutEntity,
        val request: WorkoutRequest,
        val startedElapsedMs: Long,
        val elapsedSeconds: Int = 0,
        val stepIndex: Int? = null,
        val stepRemainingSeconds: Int? = null,
        val planComplete: Boolean = false,
        val distanceMetres: Double = 0.0,
        /** True for a while after heart rate has stayed above the maximum. */
        val highHeartRate: Boolean = false,
    ) : WorkoutState

    data class Finished(
        val workout: WorkoutEntity,
        val benchmark: WalkOutcome? = null,
    ) : WorkoutState
}

/** Starts and stops the heart rate stream without this class knowing how it works. */
interface LiveLink {
    fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean)
    fun end()
}

/** Reports position while a measured distance is running. Nothing here stores it. */
interface DistanceSource {
    fun start(onFix: (LocationFix) -> Unit)
    fun stop()
}

fun interface Announcer {
    fun say(text: String)
}

private val HALFWAY_METRES = RockportWalkTest.MILE_METRES / 2
private const val ALERT_SHOWN_MS = 60_000L
const val HIGH_HEART_RATE_ALERT = "Your heart rate is above your estimated maximum. Ease off."

interface WorkoutStore {
    suspend fun open(workout: WorkoutEntity): Long
    suspend fun close(workout: WorkoutEntity)
    suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long)
    suspend fun unfinished(): List<WorkoutEntity>
    suspend fun samples(sessionId: Long): List<TimedBpm>
    suspend fun creditProgram(programId: String)
    suspend fun saveBenchmark(benchmark: BenchmarkEntity)
}

/**
 * Runs one workout. It lives outside any screen, so steps and spoken cues keep going
 * with the phone in a pocket. [scope] must be single threaded.
 */
class WorkoutController(
    private val scope: CoroutineScope,
    private val store: WorkoutStore,
    private val link: LiveLink,
    private val clock: Clock,
    private val announcer: Announcer,
    private val distance: DistanceSource,
    private val currentSessionId: () -> Long?,
    private val currentBpm: () -> Int?,
    private val tickMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var tracker: DistanceTracker? = null

    @Volatile
    private var finishing = false

    @Volatile
    private var starting = false

    val isRunning: Boolean get() = _state.value is WorkoutState.Running && !finishing

    fun start(request: WorkoutRequest) {
        if (starting || _state.value is WorkoutState.Running) return
        starting = true
        scope.launch {
            val slot = request.slot
            val draft = WorkoutEntity(
                startedAtMs = clock.nowEpochMs(),
                title = request.title,
                activity = request.activity.name,
                origin = when {
                    request.benchmark != null -> WorkoutOrigin.BENCHMARK
                    request.ai != null -> WorkoutOrigin.AI
                    slot != null -> WorkoutOrigin.PROGRAM
                    else -> WorkoutOrigin.FREE
                }.name,
                programId = slot?.programId,
                programWeek = slot?.week,
                programSession = slot?.session,
                plannedSeconds = request.plan?.totalSeconds,
                zoneMaxBpm = request.zones.maxBpm,
                deviceName = request.device.name,
                plannedHard = request.plannedHard,
                coached = request.coached,
            )
            val workout = draft.copy(id = store.open(draft))
            val usesGps = request.benchmark?.usesGps == true
            link.begin(request.device, usesLocation = usesGps, usesMicrophone = request.coached)
            finishing = false
            _state.value = WorkoutState.Running(workout, request, clock.elapsedMs())
            starting = false
            if (request.spokenCues) announcer.say(openingLine(request))
            ticker = launch { runTicks(request.zones) }
            if (usesGps) {
                tracker = DistanceTracker()
                distance.start { fix -> scope.launch { onFix(fix) } }
            }
        }
    }

    /** [mileCompleted] is false when a benchmark was abandoned, which must not be scored. */
    fun finish(reason: String = "user_finished", mileCompleted: Boolean = true) {
        val running = _state.value as? WorkoutState.Running ?: return
        if (finishing) return
        finishing = true
        scope.launch {
            ticker?.cancel()
            if (tracker != null) distance.stop()
            tracker = null
            val sessionId = currentSessionId() ?: running.workout.hrSessionId
            val endedAt = clock.nowEpochMs()
            val elapsedSeconds = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt()
            link.end()
            val dropped = complete(running.workout, sessionId, endedAt, elapsedSeconds, reason)
            val finished = withHeartRateDrop(dropped, running.request.plan, sessionId)
            val outcome = running.request.benchmark
                ?.takeIf { mileCompleted }
                ?.let { score(finished, it.inputs, sessionId, endedAt) }
            _state.value = WorkoutState.Finished(finished, outcome)
            if (running.request.spokenCues) {
                announcer.say(if (running.request.benchmark != null) "Benchmark complete." else "Workout saved.")
            }
        }
    }

    /** Swaps what is left of a running AI workout, after the rule check has approved it. */
    fun replacePlan(plan: WorkoutPlan) {
        scope.launch {
            val running = _state.value as? WorkoutState.Running ?: return@launch
            _state.value = running.copy(
                request = running.request.copy(plan = plan),
                workout = running.workout.copy(plannedSeconds = plan.totalSeconds),
            )
        }
    }

    fun dismissSummary() {
        if (_state.value is WorkoutState.Finished) _state.value = WorkoutState.Idle
    }

    /** A process death leaves a workout open. Close it at its last reading. */
    suspend fun recoverInterrupted() {
        store.unfinished().forEach { workout ->
            val samples = workout.hrSessionId?.let { store.samples(it) }.orEmpty()
            val endedAt = samples.maxOfOrNull { it.atMs } ?: workout.startedAtMs
            val elapsedSeconds = ((endedAt - workout.startedAtMs) / 1000).toInt()
            complete(workout, workout.hrSessionId, endedAt, elapsedSeconds, "app_process_ended")
        }
    }

    /** The notification line while a workout runs, or null when there is none. */
    fun statusLine(): String? {
        val running = _state.value as? WorkoutState.Running ?: return null
        val step = running.stepIndex?.let { running.request.plan?.steps?.get(it)?.label }
        val where = step ?: running.request.title
        val bpm = currentBpm()?.let { "$it bpm" } ?: "-- bpm"
        return "$bpm · $where · ${WorkoutText.clock(running.elapsedSeconds)}"
    }

    private suspend fun complete(
        workout: WorkoutEntity,
        sessionId: Long?,
        endedAt: Long,
        elapsedSeconds: Int,
        reason: String,
    ): WorkoutEntity {
        val samples = sessionId?.let { store.samples(it) }.orEmpty()
        val zones = workout.zoneMaxBpm?.let(::HeartRateZones)
        val summary = WorkoutSummaries.summarize(samples, workout.startedAtMs, endedAt, zones)
        val planned = workout.plannedSeconds
        val counted = workout.programId != null &&
            planned != null &&
            SafetyRules.countsAsDone(elapsedSeconds, planned)
        val finished = workout.withSummary(summary, sessionId, endedAt, reason, counted)
        store.close(finished)
        if (counted) store.creditProgram(requireNotNull(workout.programId))
        return finished
    }

    /** Adds the one-minute heart rate drop after the last hard step, when samples cover it. */
    private suspend fun withHeartRateDrop(workout: WorkoutEntity, plan: WorkoutPlan?, sessionId: Long?): WorkoutEntity {
        val endSeconds = plan?.let(HeartRateRecovery::lastHardStepEndSeconds) ?: return workout
        val hardEndMs = workout.startedAtMs + endSeconds * 1000L
        val samples = sessionId?.let { store.samples(it) } ?: return workout
        val drop = HeartRateRecovery.oneMinuteDrop(samples, hardEndMs) ?: return workout
        val updated = workout.copy(hrDropBpm = drop)
        store.close(updated)
        return updated
    }

    private suspend fun score(
        workout: WorkoutEntity,
        inputs: BenchmarkInputs,
        sessionId: Long?,
        endedAt: Long,
    ): WalkOutcome {
        val samples = sessionId?.let { store.samples(it) }.orEmpty()
        val outcome = RockportWalkTest.evaluate(inputs, samples, workout.startedAtMs, endedAt)
        if (outcome is WalkOutcome.Scored) {
            store.saveBenchmark(
                BenchmarkEntity(
                    workoutId = workout.id,
                    protocol = RockportWalkTest.ID,
                    takenAtMs = endedAt,
                    timeSeconds = outcome.timeSeconds,
                    heartRateBpm = outcome.heartRateBpm,
                    ageYears = inputs.ageYears,
                    sex = inputs.sex.name,
                    weightKg = inputs.weightKg,
                    vo2Max = outcome.vo2Max,
                ),
            )
        }
        return outcome
    }

    private fun onFix(fix: LocationFix) {
        val running = _state.value as? WorkoutState.Running ?: return
        val total = tracker?.add(fix) ?: return
        if (running.distanceMetres < HALFWAY_METRES && total >= HALFWAY_METRES && running.request.spokenCues) {
            announcer.say("Halfway.")
        }
        _state.value = running.copy(distanceMetres = total)
        if (total >= RockportWalkTest.MILE_METRES) finish(reason = "mile_complete")
    }

    private fun WorkoutEntity.withSummary(
        summary: WorkoutSummary,
        sessionId: Long?,
        endedAt: Long,
        reason: String,
        counted: Boolean,
    ) = copy(
        endedAtMs = endedAt,
        endReason = reason,
        hrSessionId = sessionId,
        avgBpm = summary.avgBpm,
        maxBpm = summary.maxBpm,
        recoverySeconds = summary.zoneSeconds[Zone.RECOVERY] ?: 0,
        easySeconds = summary.zoneSeconds[Zone.EASY] ?: 0,
        hardSeconds = summary.zoneSeconds[Zone.HARD] ?: 0,
        veryHardSeconds = summary.zoneSeconds[Zone.VERY_HARD] ?: 0,
        noReadingSeconds = summary.noReadingSeconds,
        countedForProgram = counted,
    )

    private suspend fun runTicks(zones: HeartRateZones) {
        val monitor = OverMaxMonitor(zones.maxBpm)
        var alertUntilMs = Long.MIN_VALUE
        var attachedSession = false
        var announcedStep = (_state.value as? WorkoutState.Running)?.request?.plan?.positionAt(0)?.index
        while (scope.isActive) {
            val running = _state.value as? WorkoutState.Running ?: return
            val plan = running.request.plan
            val elapsedSeconds = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt()
            val position = plan?.positionAt(elapsedSeconds)

            if (!attachedSession) {
                currentSessionId()?.let {
                    store.attachHeartRateSession(running.workout.id, it)
                    attachedSession = true
                }
            }
            val cues = running.request.spokenCues
            if (position != null && position.index != announcedStep) {
                announcedStep = position.index
                if (cues) announcer.say(WorkoutText.stepLine(position.step))
            }
            val planComplete = plan != null && position == null
            if (planComplete && !running.planComplete && cues) {
                announcer.say("Session complete. Finish when you are ready.")
            }
            // A safety alert, so it is spoken even with cues switched off.
            val bpm = currentBpm()
            if (bpm != null && monitor.onBpm(bpm, clock.elapsedMs())) {
                alertUntilMs = clock.elapsedMs() + ALERT_SHOWN_MS
                announcer.say(HIGH_HEART_RATE_ALERT)
            }

            _state.value = (_state.value as? WorkoutState.Running ?: return).copy(
                elapsedSeconds = elapsedSeconds,
                stepIndex = position?.index,
                stepRemainingSeconds = position?.remainingSeconds,
                planComplete = planComplete,
                highHeartRate = clock.elapsedMs() < alertUntilMs,
            )
            delay(tickMs)
        }
    }

    private fun openingLine(request: WorkoutRequest): String {
        request.benchmark?.let { benchmark ->
            val ending = if (benchmark.usesGps) {
                "Haanf will stop when you reach one mile."
            } else {
                "Tap finish when the treadmill shows one mile."
            }
            return "Walk as fast as you can, without running. $ending"
        }
        val first = request.plan?.steps?.firstOrNull() ?: return "Workout started."
        return "${request.title}. ${WorkoutText.stepLine(first)}"
    }
}
