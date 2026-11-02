package app.haanf.session

import app.haanf.core.Clock
import app.haanf.heartrate.HeartRateDevice
import app.haanf.heartrate.HeartRateReading
import app.haanf.heartrate.HeartRateSource
import app.haanf.heartrate.SensorContact
import app.haanf.heartrate.SourceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives session logic with a fake wearable, which is what proves the boundary holds. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val clock = object : Clock {
        override fun nowEpochMs() = 1_700_000_000_000 + scheduler.currentTime
        override fun elapsedMs() = scheduler.currentTime
    }
    private val device = HeartRateDevice("fake-id", "Fake Watch", "fake")
    private val source = FakeSource(device)
    private val store = FakeStore()
    private val controller = LiveSessionController(scope, store, { source }, clock)

    @After
    fun tearDown() = scope.cancel()

    private fun startSession() {
        controller.start(device, watchWorkoutRunning = true)
        scheduler.runCurrent()
    }

    private fun emit(bpm: Int, contact: SensorContact = SensorContact.UNSUPPORTED) {
        source.readingsFlow.tryEmit(
            HeartRateReading(bpm, clock.nowEpochMs(), clock.elapsedMs(), contact),
        )
        scheduler.runCurrent()
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun `saves readings and exposes the current heart rate`() {
        startSession()
        assertTrue(source.started)
        emit(120)
        emit(124)

        assertEquals(listOf(120, 124), store.samples.map { it.bpm })
        assertEquals(124, controller.live.value.bpm)
        assertEquals(2, controller.live.value.sampleCount)
        assertEquals(true, store.watchWorkout)
    }

    @Test
    fun `keeps the last valid heart rate when a no-contact reading arrives`() {
        startSession()
        emit(120, SensorContact.DETECTED)
        emit(0, SensorContact.NOT_DETECTED)

        assertEquals(120, controller.live.value.bpm)
        assertEquals(SensorContact.NOT_DETECTED, controller.live.value.sensorContact)
        assertEquals(2, store.samples.size)
    }

    @Test
    fun `fresh heart rate expires when readings stop`() {
        startSession()
        source.stateFlow.value = SourceState.Streaming
        emit(130)
        assertEquals(130, controller.freshBpm())
        advance(10_500)
        assertNull(controller.freshBpm())
    }

    @Test
    fun `logs a dropout and its end, and asks the source to reconnect`() {
        startSession()
        source.stateFlow.value = SourceState.Streaming
        scheduler.runCurrent()
        emit(130)

        advance(11_000)
        assertTrue(controller.live.value.inDropout)
        assertEquals(1, controller.live.value.dropoutCount)
        assertTrue(store.events.any { it.first == "dropout_start" })

        advance(20_000)
        assertEquals(1, source.reconnects.size)

        emit(131)
        assertFalse(controller.live.value.inDropout)
        assertNotNull(store.events.firstOrNull { it.first == "dropout_end" })
    }

    @Test
    fun `records connection changes and source notes`() {
        startSession()
        source.stateFlow.value = SourceState.Reconnecting(2, "link lost")
        source.notesFlow.tryEmit("status 8")
        scheduler.runCurrent()

        assertTrue(store.events.any { it.first == "connection" && "link lost" in it.second })
        assertTrue(store.events.any { it.first == "source_note" && it.second == "status 8" })
        assertEquals(SourceState.Reconnecting(2, "link lost"), controller.live.value.connection)
    }

    @Test
    fun `stop closes the session, stops the source and clears the live state`() {
        startSession()
        emit(120)
        controller.stop()
        scheduler.runCurrent()

        assertTrue(source.stopped)
        assertEquals("user_stopped", store.closedReason)
        assertNull(controller.live.value.sessionId)

        val samplesBefore = store.samples.size
        emit(121)
        assertEquals(samplesBefore, store.samples.size)
    }

    @Test
    fun `a second start while running is ignored`() {
        startSession()
        controller.start(device, watchWorkoutRunning = false)
        scheduler.runCurrent()
        assertEquals(1, store.opened)
    }

    private class FakeSource(override val device: HeartRateDevice) : HeartRateSource {
        val stateFlow = MutableStateFlow<SourceState>(SourceState.Idle)
        val readingsFlow = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 16)
        val notesFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val reconnects = mutableListOf<String>()
        var started = false
        var stopped = false

        override val state: StateFlow<SourceState> = stateFlow
        override val readings: Flow<HeartRateReading> = readingsFlow
        override val notes: Flow<String> = notesFlow

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        override fun reconnect(reason: String) {
            reconnects += reason
        }
    }

    private class FakeStore : SessionStore {
        val samples = mutableListOf<HeartRateReading>()
        val events = mutableListOf<Pair<String, String>>()
        var opened = 0
        var watchWorkout: Boolean? = null
        var closedReason: String? = null

        override suspend fun openSession(
            device: HeartRateDevice,
            watchWorkoutRunning: Boolean,
            startedAtEpochMs: Long,
        ): Long {
            opened++
            watchWorkout = watchWorkoutRunning
            return 7
        }

        override suspend fun closeSession(sessionId: Long, endedAtEpochMs: Long, reason: String) {
            closedReason = reason
        }

        override suspend fun addSample(sessionId: Long, reading: HeartRateReading) {
            samples += reading
        }

        override suspend fun addEvent(sessionId: Long, atEpochMs: Long, type: String, detail: String) {
            events += type to detail
        }
    }
}
