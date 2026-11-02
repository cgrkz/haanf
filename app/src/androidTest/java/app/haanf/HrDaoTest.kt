package app.haanf

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.haanf.data.HaanfDatabase
import app.haanf.data.RoomSessionStore
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.SensorContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HrDaoTest {
    private lateinit var database: HaanfDatabase
    private val device = HeartRateDevice("id", "Watch", "ble")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HaanfDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun savesSamplesInOrderAndSummarisesTheSession() = runTest {
        val store = RoomSessionStore(database.hrDao())
        val id = store.openSession(device, watchWorkoutRunning = true, startedAtEpochMs = 100)
        store.addSample(id, HeartRateReading(70, 200, 0, SensorContact.DETECTED, listOf(800, 810)))
        store.addSample(id, HeartRateReading(71, 300, 0))
        store.addEvent(id, 250, "dropout_start", "silent_ms=11000")

        assertEquals(listOf(70, 71), database.hrDao().samples(id).map { it.bpm })
        assertEquals("800 810", database.hrDao().samples(id).first().rrIntervalsMs)

        val summary = database.hrDao().observeSessions().first().single()
        assertEquals(2, summary.sampleCount)
        assertEquals(1, summary.dropoutCount)
        assertEquals(true, summary.session.watchWorkoutRunning)
    }

    @Test
    fun closesSessionsAProcessDeathLeftOpenAtTheirLastSample() = runTest {
        val store = RoomSessionStore(database.hrDao())
        val id = store.openSession(device, false, 100)
        store.addSample(id, HeartRateReading(70, 900, 0))

        assertEquals(1, database.hrDao().closeOpenSessions("app_process_ended"))

        val session = database.hrDao().session(id)!!
        assertEquals(900L, session.endedAtMs)
        assertEquals("app_process_ended", session.endReason)
        assertEquals(0, database.hrDao().closeOpenSessions("app_process_ended"))
    }

    @Test
    fun deletingASessionRemovesItsSamplesAndEvents() = runTest {
        val store = RoomSessionStore(database.hrDao())
        val id = store.openSession(device, false, 100)
        store.addSample(id, HeartRateReading(70, 200, 0))
        store.addEvent(id, 200, "connection", "Connected")

        database.hrDao().deleteSession(id)

        assertEquals(0, database.hrDao().samples(id).size)
        assertEquals(0, database.hrDao().events(id).size)
    }
}
