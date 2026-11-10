package app.haanf.workout

import app.haanf.core.Clock
import app.haanf.data.BenchmarkEntity
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEntity
import app.haanf.data.WorkoutEventEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.BikeType
import app.haanf.training.ClimbEvent
import app.haanf.training.HeartRateRecovery
import app.haanf.training.HeartRateZones
import app.haanf.training.LocationFix
import app.haanf.training.MotionEvent
import app.haanf.training.MotionKind
import app.haanf.training.MotionMeter
import app.haanf.training.MotionNow
import app.haanf.training.MotionSample
import app.haanf.training.MotionTotals
import app.haanf.training.OverMaxMonitor
import app.haanf.training.RockportWalkTest
import app.haanf.training.SafetyRules
import app.haanf.training.Split
import app.haanf.training.TimedBpm
import app.haanf.training.WalkOutcome
import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.WorkoutSummaries
import app.haanf.training.WorkoutSummary
import app.haanf.training.Zone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
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
    /** True when the phone's GPS measures this workout. Get ready sets it once location is allowed. */
    val usesGps: Boolean = false,
    /** For estimating watts on a ride. */
    val riderKg: Double? = null,
    val bike: BikeType? = null,
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
        /** Distance, speed and climb, when the phone measures them. */
        val motion: MotionNow? = null,
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
private const val RIDE_CUE_KM = 5
const val HIGH_HEART_RATE_ALERT = "Your heart rate is above your estimated maximum. Ease off."

interface WorkoutStore {
    suspend fun open(workout: WorkoutEntity): Long
    suspend fun close(workout: WorkoutEntity)
    suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long)
    suspend fun unfinished(): List<WorkoutEntity>
    suspend fun samples(sessionId: Long): List<TimedBpm>
    suspend fun creditProgram(programId: String)
    suspend fun saveBenchmark(benchmark: BenchmarkEntity)
    suspend fun addSplit(split: SplitEntity) = Unit
    suspend fun addEvent(event: WorkoutEventEntity) = Unit
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
    private val pressure: PressureSource = NoPressure,
    private val inDropout: () -> Boolean = { false },
    private val tickMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var meter: MotionMeter? = null
    private var gpsOn = false
    private val entries = CopyOnWriteArrayList<TimelineEntry>()

    /** The current workout's timeline so far, oldest first. */
    val timeline: List<TimelineEntry> get() = entries.toList()

    /** Distance, height and speed second by second, for the coach's overview. Never a position. */
    fun motionSamples(): List<MotionSample> = meter?.samples?.toList().orEmpty()

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
                bikeType = request.bike?.takeIf { request.activity == TrainingActivity.CYCLING }?.name,
            )
            val workout = draft.copy(id = store.open(draft))
            val usesGps = request.usesGps || request.benchmark?.usesGps == true
            link.begin(request.device, usesLocation = usesGps, usesMicrophone = request.coached)
            finishing = false
            entries.clear()
            val outdoors = request.activity != TrainingActivity.TREADMILL
            meter = if (usesGps || (outdoors && pressure.available)) {
                MotionMeter(motionKind(request), request.riderKg, request.bike)
            } else {
                null
            }
            _state.value = WorkoutState.Running(workout, request, clock.elapsedMs(), motion = meter?.now())
            starting = false
            if (request.spokenCues) announcer.say(openingLine(request))
            request.plan?.steps?.firstOrNull()?.let { log(TimelineKind.STEP, stepText(it, request.zones)) }
            ticker = launch { runTicks(request.zones) }
            meter?.let { measuring ->
                if (usesGps) {
                    gpsOn = true
                    distance.start { fix -> scope.launch { onFix(fix) } }
                }
                if (pressure.available) pressure.start { hpa -> scope.launch { measuring.onPressure(hpa) } }
            }
        }
    }

    /**
     * Adds a line to the current workout's timeline, timed from the start, and saves it.
     * Safe to call from any thread.
     */
    fun log(kind: TimelineKind, text: String, bpm: Int? = currentBpm()) {
        val running = _state.value as? WorkoutState.Running ?: return
        val elapsed = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt().coerceAtLeast(0)
        entries += TimelineEntry(elapsed, kind, text, bpm)
        val event = WorkoutEventEntity(
            workoutId = running.workout.id,
            atMs = clock.nowEpochMs(),
            elapsedSeconds = elapsed,
            kind = kind.name,
            text = text,
            bpm = bpm,
        )
        scope.launch { store.addEvent(event) }
    }

    /** [mileCompleted] is false when a benchmark was abandoned, which must not be scored. */
    fun finish(reason: String = "user_finished", mileCompleted: Boolean = true) {
        val running = _state.value as? WorkoutState.Running ?: return
        if (finishing) return
        finishing = true
        scope.launch {
            ticker?.cancel()
            if (gpsOn) distance.stop()
            gpsOn = false
            pressure.stop()
            val sessionId = currentSessionId() ?: running.workout.hrSessionId
            val endedAt = clock.nowEpochMs()
            val elapsedSeconds = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt()
            link.end()
            val measured = meter
            meter = null
            measured?.finish(elapsedSeconds)?.let { saveSplit(running, it) }
            val totals = measured?.totals()?.takeIf { it.distanceMetres > 0 || it.climbMetres > 0 || it.descentMetres > 0 }
            val dropped = complete(running.workout, sessionId, endedAt, elapsedSeconds, reason, totals)
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
        motion: MotionTotals? = null,
    ): WorkoutEntity {
        val samples = sessionId?.let { store.samples(it) }.orEmpty()
        val zones = workout.zoneMaxBpm?.let(::HeartRateZones)
        val summary = WorkoutSummaries.summarize(samples, workout.startedAtMs, endedAt, zones)
        val planned = workout.plannedSeconds
        val counted = workout.programId != null &&
            planned != null &&
            SafetyRules.countsAsDone(elapsedSeconds, planned)
        val finished = workout.withSummary(summary, sessionId, endedAt, reason, counted).withMotion(motion)
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
        val measuring = meter ?: return
        measuring.onFix(fix)
        val total = measuring.distanceMetres
        _state.value = running.copy(distanceMetres = total)
        if (running.request.benchmark == null) return
        if (running.distanceMetres < HALFWAY_METRES && total >= HALFWAY_METRES && running.request.spokenCues) {
            announcer.say("Halfway.")
        }
        if (total >= RockportWalkTest.MILE_METRES) finish(reason = "mile_complete")
    }

    private suspend fun saveSplit(running: WorkoutState.Running, split: Split) {
        store.addSplit(
            SplitEntity(
                workoutId = running.workout.id,
                number = split.number,
                metres = split.metres,
                seconds = split.seconds,
                endElapsedSeconds = split.endElapsedSeconds,
                avgBpm = split.avgBpm,
                climbMetres = split.climbMetres,
                descentMetres = split.descentMetres,
                avgWatts = split.avgWatts,
                gapSecondsPerKm = split.gapSecondsPerKm,
            ),
        )
    }

    private suspend fun onMotion(running: WorkoutState.Running, event: MotionEvent) {
        val ride = running.request.activity == TrainingActivity.CYCLING
        when (event) {
            is MotionEvent.SplitDone -> {
                val split = event.split
                saveSplit(running, split)
                log(TimelineKind.SPLIT, WorkoutText.splitLine(split, ride), split.avgBpm)
                val every = if (ride) RIDE_CUE_KM else 1
                if (running.request.spokenCues && running.request.benchmark == null && split.number % every == 0) {
                    announcer.say(WorkoutText.splitCue(split, ride))
                }
            }
            is MotionEvent.Climb -> when (val climb = event.event) {
                is ClimbEvent.Started -> log(TimelineKind.CLIMB, "Climb started, about ${climb.gradePercent.roundToInt()}% gradient.")
                is ClimbEvent.Ended -> log(
                    TimelineKind.CLIMB,
                    "Climb ended: ${climb.gainMetres.roundToInt()} m up over ${WorkoutText.km(climb.metres)} km " +
                        "in ${WorkoutText.clock(climb.seconds)}, ${climb.averageGradePercent.roundToInt()}% average.",
                )
            }
        }
    }

    private fun motionKind(request: WorkoutRequest): MotionKind = when {
        request.benchmark != null -> MotionKind.WALK
        request.activity == TrainingActivity.CYCLING -> MotionKind.RIDE
        else -> MotionKind.RUN
    }

    private fun stepText(step: WorkoutStep, zones: HeartRateZones): String {
        val range = zones.rangeFor(step.target)
        return "Step: ${step.label}, ${WorkoutText.clock(step.seconds)}, ${step.target.label.lowercase()} " +
            "${range.first}-${range.last} bpm."
    }

    private fun WorkoutEntity.withMotion(motion: MotionTotals?) = if (motion == null) {
        this
    } else {
        copy(
            distanceMetres = motion.distanceMetres.takeIf { it > 0 },
            movingSeconds = motion.movingSeconds.takeIf { motion.distanceMetres > 0 },
            maxSpeedMps = motion.maxSpeedMps.takeIf { it > 0 },
            climbMetres = motion.climbMetres,
            descentMetres = motion.descentMetres,
            avgWatts = motion.avgWatts,
            gapSecondsPerKm = motion.gapSecondsPerKm,
        )
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
        var wasInDropout = false
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
                log(TimelineKind.STEP, stepText(position.step, zones))
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
                log(TimelineKind.ALERT, "Heart rate stayed above the estimated maximum. The alert was spoken.", bpm)
            }
            val dropout = inDropout()
            if (dropout != wasInDropout) {
                wasInDropout = dropout
                if (dropout) log(TimelineKind.DROPOUT, "Heart rate readings stopped.", null) else log(TimelineKind.RECONNECTED, "Heart rate readings are back.")
            }
            val measuring = meter
            measuring?.tick(elapsedSeconds, bpm)?.forEach { onMotion(running, it) }

            _state.value = (_state.value as? WorkoutState.Running ?: return).copy(
                elapsedSeconds = elapsedSeconds,
                stepIndex = position?.index,
                stepRemainingSeconds = position?.remainingSeconds,
                planComplete = planComplete,
                highHeartRate = clock.elapsedMs() < alertUntilMs,
                motion = measuring?.now(),
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
