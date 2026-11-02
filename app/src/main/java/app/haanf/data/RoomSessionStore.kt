package app.haanf.data

import android.util.Log
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.session.SessionStore

class RoomSessionStore(private val dao: HrDao) : SessionStore {
    override suspend fun openSession(
        device: HeartRateDevice,
        watchWorkoutRunning: Boolean,
        startedAtEpochMs: Long,
    ): Long = dao.insertSession(
        HrSessionEntity(
            startedAtMs = startedAtEpochMs,
            deviceId = device.id,
            deviceName = device.name,
            sourceKind = device.sourceKind,
            watchWorkoutRunning = watchWorkoutRunning,
        ),
    )

    override suspend fun closeSession(sessionId: Long, endedAtEpochMs: Long, reason: String) =
        dao.closeSession(sessionId, endedAtEpochMs, reason)

    override suspend fun addSample(sessionId: Long, reading: HeartRateReading) =
        dao.insertSample(
            HrSampleEntity(
                sessionId = sessionId,
                receivedAtMs = reading.receivedAtEpochMs,
                bpm = reading.bpm,
                sensorContact = reading.sensorContact.name,
                rrIntervalsMs = reading.rrIntervalsMs.joinToString(" "),
            ),
        )

    override suspend fun addEvent(sessionId: Long, atEpochMs: Long, type: String, detail: String) {
        Log.i(TAG, "session $sessionId $type: $detail")
        dao.insertEvent(HrEventEntity(sessionId = sessionId, atMs = atEpochMs, type = type, detail = detail))
    }

    private companion object {
        const val TAG = "HaanfSession"
    }
}
