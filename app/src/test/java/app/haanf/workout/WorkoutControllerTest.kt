package app.haanf.workout

import app.haanf.core.Clock
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.HeartRateZones
import app.haanf.training.TimedBpm
import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private val clock = object : Clock {
        override fun nowEpochMs() = START_MS + scheduler.currentTime
        override fun elapsedMs() = scheduler.currentTime
    }
    private val store = FakeStore()
    private val link = FakeLink()
    private val spoken = mutableListOf<String>()
    private var sessionId: Long? = null
    private var bpm: Int? = null
    private val controller = WorkoutController(
        scope = scope,
        store = store,
        link = link,
        clock = clock,
        announcer = { spoken += it },
        currentSessionId = { sessionId },
        currentBpm = { bpm },
    )

    private val device = HeartRateDevice("id", "Watch", "fake")
    private val plan = WorkoutPlan(
        title = "Test plan",
        steps = listOf(
            WorkoutStep("Warm up", 60, Zone.EASY),
            WorkoutStep("Hard 1 of 1", 60, Zone.HARD),
        ),
    )
    private val programRequest = WorkoutRequest(
        title = plan.title,
        activity = TrainingActivity.TREADMILL,
        device = device,
        zones = HeartRateZones(200),
        plan = plan,
        slot = ProgramSlot("foundation-8", 3, 1),
    )
    private val freeRequest = programRequest.copy(title = "Free workout", plan = null, slot = null)

    @After
    fun tearDown() = scope.cancel()

    private fun run(seconds: Long) {
        scheduler.advanceTimeBy(seconds * 1000)
        scheduler.runCurrent()
    }

    private fun start(request: WorkoutRequest) {
        controller.start(request)
        scheduler.runCurrent()
    }

    private fun finish() {
        controller.finish()
        scheduler.runCurrent()
    }

    private val finished get() = (controller.state.value as WorkoutState.Finished).workout

    @Test
    fun `starts the stream and announces the first step`() {
        start(programRequest)
        assertEquals(listOf(device), link.begun)
        assertEquals("Test plan. Warm up. 1 minute.", spoken.single())
        assertTrue(controller.isRunning)
    }

    @Test
    fun `announces each step once and says when the plan is over`() {
        start(programRequest)
        run(61)
        assertEquals("Hard 1 of 1. 1 minute.", spoken[1])
        run(30)
        assertEquals(2, spoken.size)
        run(30)
        assertEquals("Session complete. Finish when you are ready.", spoken[2])
        run(10)
        assertEquals(3, spoken.size)
        assertTrue((controller.state.value as WorkoutState.Running).planComplete)
    }

    @Test
    fun `saves a summary from the heart rate session`() {
        sessionId = 9
        store.samples = listOf(TimedBpm(START_MS + 1_000, 120), TimedBpm(START_MS + 11_000, 150))
        start(freeRequest)
        run(20)
        finish()

        assertEquals(135, finished.avgBpm)
        assertEquals(150, finished.maxBpm)
        assertEquals(10, finished.easySeconds)
        assertEquals(9, finished.hardSeconds)
        assertEquals(9L, finished.hrSessionId)
        assertEquals("user_finished", finished.endReason)
        assertEquals(1, link.ended)
        assertEquals(finished, store.closed.single())
    }

    @Test
    fun `a program workout done in full credits the program`() {
        start(programRequest)
        run(120)
        finish()
        assertTrue(finished.countedForProgram)
        assertEquals(WorkoutOrigin.PROGRAM.name, finished.origin)
        assertEquals(listOf("foundation-8"), store.credited)
    }

    @Test
    fun `a short attempt is kept but does not move the program`() {
        start(programRequest)
        run(30)
        finish()
        assertFalse(finished.countedForProgram)
        assertTrue(store.credited.isEmpty())
        assertEquals(1, store.closed.size)
    }

    @Test
    fun `a free workout never touches the program clock`() {
        start(freeRequest)
        run(3_600)
        finish()
        assertEquals(WorkoutOrigin.FREE.name, finished.origin)
        assertFalse(finished.countedForProgram)
        assertTrue(store.credited.isEmpty())
    }

    @Test
    fun `tells the user to ease off when heart rate stays over the maximum`() {
        start(freeRequest)
        bpm = 210
        run(35)
        assertTrue(spoken.any { "above your estimated maximum" in it })
    }

    @Test
    fun `stays quiet about heart rate when it is within the maximum`() {
        start(freeRequest)
        bpm = 190
        run(120)
        assertFalse(spoken.any { "above your estimated maximum" in it })
    }

    @Test
    fun `links the heart rate session as soon as it exists`() {
        start(freeRequest)
        run(3)
        assertTrue(store.attached.isEmpty())
        sessionId = 4
        run(2)
        assertEquals(listOf(store.nextId - 1 to 4L), store.attached)
    }

    @Test
    fun `recovers a workout left open by a process death at its last reading`() {
        store.open = listOf(
            WorkoutEntity(
                id = 5,
                startedAtMs = START_MS,
                title = "Test plan",
                activity = "TREADMILL",
                origin = "PROGRAM",
                programId = "foundation-8",
                plannedSeconds = 100,
                zoneMaxBpm = 200,
                hrSessionId = 9,
            ),
        )
        store.samples = listOf(TimedBpm(START_MS, 120), TimedBpm(START_MS + 90_000, 130))

        scope.launchRecover()

        val recovered = store.closed.single()
        assertEquals(START_MS + 90_000, recovered.endedAtMs)
        assertEquals("app_process_ended", recovered.endReason)
        assertTrue(recovered.countedForProgram)
        assertEquals(listOf("foundation-8"), store.credited)
    }

    @Test
    fun `a second finish does nothing`() {
        start(freeRequest)
        controller.finish()
        controller.finish()
        scheduler.runCurrent()
        assertEquals(1, store.closed.size)
    }

    @Test
    fun `reports status for the notification only while running`() {
        assertNull(controller.statusLine())
        start(programRequest)
        bpm = 130
        run(65)
        assertEquals("130 bpm · Hard 1 of 1 · 1:05", controller.statusLine())
    }

    @Test
    fun `dismissing the summary returns to idle`() {
        start(freeRequest)
        finish()
        controller.dismissSummary()
        assertEquals(WorkoutState.Idle, controller.state.value)
    }

    private fun CoroutineScope.launchRecover() {
        kotlinx.coroutines.runBlocking { controller.recoverInterrupted() }
    }

    private class FakeLink : LiveLink {
        val begun = mutableListOf<HeartRateDevice>()
        var ended = 0

        override fun begin(device: HeartRateDevice) {
            begun += device
        }

        override fun end() {
            ended++
        }
    }

    private class FakeStore : WorkoutStore {
        var nextId = 1L
        var samples = emptyList<TimedBpm>()
        var open = emptyList<WorkoutEntity>()
        val closed = mutableListOf<WorkoutEntity>()
        val credited = mutableListOf<String>()
        val attached = mutableListOf<Pair<Long, Long>>()

        override suspend fun open(workout: WorkoutEntity): Long = nextId++

        override suspend fun close(workout: WorkoutEntity) {
            closed += workout
        }

        override suspend fun attachHeartRateSession(workoutId: Long, sessionId: Long) {
            attached += workoutId to sessionId
        }

        override suspend fun unfinished(): List<WorkoutEntity> = open

        override suspend fun samples(sessionId: Long): List<TimedBpm> = samples

        override suspend fun creditProgram(programId: String) {
            credited += programId
        }
    }

    private companion object {
        const val START_MS = 1_700_000_000_000
    }
}
