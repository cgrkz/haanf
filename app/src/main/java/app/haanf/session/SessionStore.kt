package app.haanf.session

import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading

/** Where a live session writes. Kept separate from Room so session logic tests run on the JVM. */
interface SessionStore {
    suspend fun openSession(
        device: HeartRateDevice,
        watchWorkoutRunning: Boolean,
        startedAtEpochMs: Long,
    ): Long

    suspend fun closeSession(sessionId: Long, endedAtEpochMs: Long, reason: String)
    suspend fun addSample(sessionId: Long, reading: HeartRateReading)
    suspend fun addEvent(sessionId: Long, atEpochMs: Long, type: String, detail: String)
}
