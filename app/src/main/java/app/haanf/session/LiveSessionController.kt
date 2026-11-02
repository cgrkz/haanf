package app.haanf.session

import app.haanf.core.Clock
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.HeartRateSourceFactory
import app.haanf.heartrate.SensorContact
import app.haanf.heartrate.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveSnapshot(
    val sessionId: Long? = null,
    val device: HeartRateDevice? = null,
    val connection: SourceState = SourceState.Idle,
    val bpm: Int? = null,
    val lastValidReadingElapsedMs: Long? = null,
    val sensorContact: SensorContact = SensorContact.UNSUPPORTED,
    val sampleCount: Int = 0,
    val dropoutCount: Int = 0,
    val inDropout: Boolean = false,
    val watchWorkoutRunning: Boolean = false,
)

/**
 * Runs one live heart rate session against any [HeartRateSource]. [scope] must be
 * single threaded so that the collectors below never race on the detector.
 */
class LiveSessionController(
    private val scope: CoroutineScope,
    private val store: SessionStore,
    private val sources: HeartRateSourceFactory,
    private val clock: Clock,
    private val environment: String = "",
    private val tickMs: Long = 1_000,
) {
    private val _live = MutableStateFlow(LiveSnapshot())
    val live: StateFlow<LiveSnapshot> = _live.asStateFlow()

    private var job: Job? = null
    private var source: HeartRateSource? = null

    fun start(device: HeartRateDevice, watchWorkoutRunning: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            val startedAt = clock.nowEpochMs()
            val sessionId = store.openSession(device, watchWorkoutRunning, startedAt)
            store.addEvent(
                sessionId,
                startedAt,
                "session_start",
                "device=${device.name ?: device.id}; source=${device.sourceKind}; " +
                    "watch_workout=$watchWorkoutRunning; $environment",
            )

            val active = sources.create(device)
            source = active
            val detector = DropoutDetector()
            _live.value = LiveSnapshot(
                sessionId = sessionId,
                device = device,
                connection = active.state.value,
                watchWorkoutRunning = watchWorkoutRunning,
            )

            launch {
                active.notes.collect { store.addEvent(sessionId, clock.nowEpochMs(), "source_note", it) }
            }
            launch {
                active.state.collect { state ->
                    _live.update { it.copy(connection = state) }
                    store.addEvent(sessionId, clock.nowEpochMs(), "connection", state.label)
                }
            }
            launch {
                active.readings.collect { reading ->
                    store.addSample(sessionId, reading)
                    applyReading(reading)
                    detector.onReading(clock.elapsedMs())?.let { onDropoutEvent(it, sessionId, active) }
                }
            }
            launch {
                while (isActive) {
                    delay(tickMs)
                    val streaming = active.state.value is SourceState.Streaming
                    detector.onTick(clock.elapsedMs(), streaming)?.let { onDropoutEvent(it, sessionId, active) }
                }
            }
            active.start()
        }
    }

    fun stop(reason: String = "user_stopped") {
        val running = job ?: return
        job = null
        scope.launch {
            val sessionId = _live.value.sessionId
            running.cancelAndJoin()
            source?.stop()
            source = null
            withContext(NonCancellable) {
                if (sessionId != null) store.closeSession(sessionId, clock.nowEpochMs(), reason)
            }
            _live.value = LiveSnapshot()
        }
    }

    /** The current heart rate, or null when the newest valid reading is too old to trust. */
    fun freshBpm(maxAgeMs: Long = FRESH_READING_MS): Int? {
        val snapshot = _live.value
        val readAt = snapshot.lastValidReadingElapsedMs ?: return null
        return snapshot.bpm.takeIf { clock.elapsedMs() - readAt <= maxAgeMs }
    }

    private fun applyReading(reading: HeartRateReading) {
        _live.update {
            it.copy(
                bpm = if (reading.isValid) reading.bpm else it.bpm,
                lastValidReadingElapsedMs =
                    if (reading.isValid) reading.receivedAtElapsedMs else it.lastValidReadingElapsedMs,
                sensorContact = reading.sensorContact,
                sampleCount = it.sampleCount + 1,
            )
        }
    }

    private suspend fun onDropoutEvent(
        event: DropoutDetector.Event,
        sessionId: Long,
        active: HeartRateSource,
    ) {
        val now = clock.nowEpochMs()
        when (event) {
            is DropoutDetector.Event.Started -> {
                _live.update { it.copy(inDropout = true, dropoutCount = it.dropoutCount + 1) }
                store.addEvent(sessionId, now, "dropout_start", "silent_ms=${event.silentForMs}")
            }
            is DropoutDetector.Event.Ended -> {
                _live.update { it.copy(inDropout = false) }
                store.addEvent(sessionId, now, "dropout_end", "gap_ms=${event.gapMs}")
            }
            is DropoutDetector.Event.RequestReconnect -> {
                store.addEvent(sessionId, now, "reconnect_requested", "silent_ms=${event.silentForMs}")
                active.reconnect("no readings for ${event.silentForMs / 1000}s")
            }
        }
    }

    companion object {
        const val FRESH_READING_MS = 10_000L
    }
}
