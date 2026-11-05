package app.haanf.session

import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.HeartRateSourceFactory
import app.haanf.heartrate.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PreviewState(
    val device: HeartRateDevice? = null,
    val connection: SourceState = SourceState.Idle,
    val bpm: Int? = null,
)

/**
 * Connects to the watch before a workout, only so Get ready can show live heart rate. It
 * saves nothing, and must be stopped before the workout's own connection starts.
 */
class HeartRatePreview(
    private val scope: CoroutineScope,
    private val sources: HeartRateSourceFactory,
) {
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()

    private var source: HeartRateSource? = null
    private var job: Job? = null

    fun start(device: HeartRateDevice) {
        if (source?.device == device) return
        stop()
        val active = sources.create(device)
        source = active
        _state.value = PreviewState(device = device)
        job = scope.launch {
            launch { active.state.collect { connection -> _state.update { it.copy(connection = connection) } } }
            launch {
                active.readings.collect { reading ->
                    if (reading.isValid) _state.update { it.copy(bpm = reading.bpm) }
                }
            }
            active.start()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        source?.stop()
        source = null
        _state.value = PreviewState()
    }
}
