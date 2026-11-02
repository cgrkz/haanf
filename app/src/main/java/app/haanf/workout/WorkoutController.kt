package app.haanf.workout

import app.haanf.core.Clock
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.OverMaxMonitor
import app.haanf.training.SafetyRules
import app.haanf.training.TimedBpm
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

enum class WorkoutOrigin { FREE, PROGRAM }

data class ProgramSlot(val programId: String, val week: Int, val session: Int)

data class WorkoutRequest(
    val title: String,
    val activity: TrainingActivity,
    val device: HeartRateDevice,
    val zones: HeartRateZones,
    val plan: WorkoutPlan? = null,
    val slot: ProgramSlot? = null,
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
    ) : WorkoutState

    data class Finished(val workout: WorkoutEntity) : WorkoutState
}

/** Starts and stops the heart rate stream without this class knowing how it works. */
interface LiveLink {
    fun begin(device: HeartRateDevice)
    fun end()
}

fun interface Announcer {
    fun say(text: String)
}

interface WorkoutStore {
    suspend fun open(workout: WorkoutEntity): Long
    suspend fun close(workout: WorkoutEntity)
    suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long)
    suspend fun unfinished(): List<WorkoutEntity>
    suspend fun samples(sessionId: Long): List<TimedBpm>
    suspend fun creditProgram(programId: String)
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
    private val currentSessionId: () -> Long?,
    private val currentBpm: () -> Int?,
    private val tickMs: Long = 1_000,
) {
    private val _state = MutableStateFlow<WorkoutState>(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private var ticker: Job? = null

    @Volatile
    private var finishing = false

    val isRunning: Boolean get() = _state.value is WorkoutState.Running && !finishing

    fun start(request: WorkoutRequest) {
        if (_state.value is WorkoutState.Running) return
        scope.launch {
            val slot = request.slot
            val draft = WorkoutEntity(
                startedAtMs = clock.nowEpochMs(),
                title = request.title,
                activity = request.activity.name,
                origin = (if (slot != null) WorkoutOrigin.PROGRAM else WorkoutOrigin.FREE).name,
                programId = slot?.programId,
                programWeek = slot?.week,
                programSession = slot?.session,
                plannedSeconds = request.plan?.totalSeconds,
                zoneMaxBpm = request.zones.maxBpm,
                deviceName = request.device.name,
            )
            val workout = draft.copy(id = store.open(draft))
            link.begin(request.device)
            finishing = false
            _state.value = WorkoutState.Running(workout, request, clock.elapsedMs())
            announcer.say(openingLine(request))
            ticker = launch { runTicks(request) }
        }
    }

    fun finish(reason: String = "user_finished") {
        val running = _state.value as? WorkoutState.Running ?: return
        if (finishing) return
        finishing = true
        scope.launch {
            ticker?.cancel()
            val sessionId = currentSessionId() ?: running.workout.hrSessionId
            val endedAt = clock.nowEpochMs()
            val elapsedSeconds = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt()
            link.end()
            val finished = complete(running.workout, sessionId, endedAt, elapsedSeconds, reason)
            _state.value = WorkoutState.Finished(finished)
            announcer.say("Workout saved.")
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

    private suspend fun runTicks(request: WorkoutRequest) {
        val monitor = OverMaxMonitor(request.zones.maxBpm)
        var attachedSession = false
        var announcedStep = request.plan?.positionAt(0)?.index
        while (scope.isActive) {
            val running = _state.value as? WorkoutState.Running ?: return
            val elapsedSeconds = ((clock.elapsedMs() - running.startedElapsedMs) / 1000).toInt()
            val position = request.plan?.positionAt(elapsedSeconds)

            if (!attachedSession) {
                currentSessionId()?.let {
                    store.attachHeartRateSession(running.workout.id, it)
                    attachedSession = true
                }
            }
            if (position != null && position.index != announcedStep) {
                announcedStep = position.index
                announcer.say(WorkoutText.stepLine(position.step))
            }
            val planComplete = request.plan != null && position == null
            if (planComplete && !running.planComplete) {
                announcer.say("Session complete. Finish when you are ready.")
            }
            val bpm = currentBpm()
            if (bpm != null && monitor.onBpm(bpm, clock.elapsedMs())) {
                announcer.say("Your heart rate is above your estimated maximum. Ease off.")
            }

            _state.value = running.copy(
                elapsedSeconds = elapsedSeconds,
                stepIndex = position?.index,
                stepRemainingSeconds = position?.remainingSeconds,
                planComplete = planComplete,
            )
            delay(tickMs)
        }
    }

    private fun openingLine(request: WorkoutRequest): String {
        val first = request.plan?.steps?.firstOrNull() ?: return "Workout started."
        return "${request.title}. ${WorkoutText.stepLine(first)}"
    }
}
