package app.haanf.heartrate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * The boundary between wearables and workout logic. Everything outside
 * app.haanf.heartrate.ble depends on these types only, so a new wearable means
 * a new HeartRateSource, with no change to sessions, talk tests or storage.
 */

enum class SensorContact { UNSUPPORTED, NOT_DETECTED, DETECTED }

data class HeartRateReading(
    val bpm: Int,
    val receivedAtEpochMs: Long,
    val receivedAtElapsedMs: Long,
    val sensorContact: SensorContact = SensorContact.UNSUPPORTED,
    val rrIntervalsMs: List<Int> = emptyList(),
) {
    /** A zero or no-contact reading proves the link is alive, not that the heart rate is known. */
    val isValid: Boolean get() = bpm > 0 && sensorContact != SensorContact.NOT_DETECTED
}

/** A wearable as workout logic sees it. [id] is opaque outside the source that issued it. */
data class HeartRateDevice(
    val id: String,
    val name: String?,
    val sourceKind: String,
)

sealed interface SourceState {
    val label: String

    data object Idle : SourceState {
        override val label = "Idle"
    }

    data class Connecting(val attempt: Int) : SourceState {
        override val label = "Connecting (attempt $attempt)"
    }

    data object Pairing : SourceState {
        override val label = "Waiting for pairing, check the watch and phone"
    }

    data object Streaming : SourceState {
        override val label = "Connected, receiving"
    }

    data class Reconnecting(val attempt: Int, val reason: String) : SourceState {
        override val label = "Reconnecting (attempt $attempt): $reason"
    }

    /** The device answered but does not offer heart rate this way. Retrying will not help. */
    data class Unsupported(val reason: String, val offered: List<String>) : SourceState {
        override val label = "Not usable: $reason"
    }

    data class Failed(val reason: String) : SourceState {
        override val label = "Failed: $reason"
    }
}

interface HeartRateSource {
    val device: HeartRateDevice
    val state: StateFlow<SourceState>
    val readings: Flow<HeartRateReading>

    /** Free-text diagnostics saved with the session, for example link status codes. */
    val notes: Flow<String>

    /** Begins connecting and keeps the link alive until [stop]. */
    fun start()

    /** Ends the link for good. A stopped source cannot be started again. */
    fun stop()

    /** Drops the link and reconnects. Sessions call this when readings stop arriving. */
    fun reconnect(reason: String)
}

fun interface HeartRateSourceFactory {
    fun create(device: HeartRateDevice): HeartRateSource
}
