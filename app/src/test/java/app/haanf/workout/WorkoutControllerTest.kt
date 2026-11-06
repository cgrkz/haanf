package app.haanf.workout

import app.haanf.core.Clock
import app.haanf.data.BenchmarkEntity
import app.haanf.data.SplitEntity
import app.haanf.data.WorkoutEventEntity
import app.haanf.training.BikeType
import kotlin.math.pow
import app.haanf.data.WorkoutEntity
import app.haanf.heartrate.HeartRateDevice
import app.haanf.talktest.TrainingActivity
import app.haanf.training.BenchmarkInputs
import app.haanf.training.HeartRateZones
import app.haanf.training.LocationFix
import app.haanf.training.RockportWalkTest
import app.haanf.training.Sex
import app.haanf.training.TimedBpm
import app.haanf.training.WalkOutcome
import app.haanf.training.WorkoutPlan
import app.haanf.training.WorkoutStep
import app.haanf.training.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
    private val gps = FakeGps()
    private val pressure = FakePressure()
    private var sessionId: Long? = null
    private var bpm: Int? = null
    private val controller = WorkoutController(
        scope = scope,
        store = store,
        link = link,
        clock = clock,
        announcer = { spoken += it },
        distance = gps,
        currentSessionId = { sessionId },
        currentBpm = { bpm },
        pressure = pressure,
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
    private val inputs = BenchmarkInputs(ageYears = 40, sex = Sex.MALE, weightKg = 81.65)
    private val treadmillBenchmark = freeRequest.copy(
        title = "1-mile walk benchmark",
        benchmark = BenchmarkRequest(inputs, usesGps = false),
    )
    private val outdoorBenchmark = freeRequest.copy(
        title = "1-mile walk benchmark",
        benchmark = BenchmarkRequest(inputs, usesGps = true),
    )

    private fun steadySamples(seconds: Int, bpm: Int) =
        (0..seconds step 2).map { TimedBpm(START_MS + it * 1000L, bpm) }

    private fun walk(metresPerFix: Int, fixes: Int) {
        repeat(fixes) {
            gps.moveBy(metresPerFix)
            run(2)
        }
    }

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

        runBlocking { controller.recoverInterrupted() }

        val recovered = store.closed.single()
        assertEquals(START_MS + 90_000, recovered.endedAtMs)
        assertEquals("app_process_ended", recovered.endReason)
        assertTrue(recovered.countedForProgram)
        assertEquals(listOf("foundation-8"), store.credited)
    }

    @Test
    fun `a finished benchmark is scored and saved`() {
        sessionId = 9
        store.samples = steadySamples(900, 150)
        start(treadmillBenchmark)
        run(900)
        finish()

        val state = controller.state.value as WorkoutState.Finished
        val outcome = state.benchmark as WalkOutcome.Scored
        assertEquals(37.8, outcome.vo2Max, 0.2)
        assertEquals(WorkoutOrigin.BENCHMARK.name, finished.origin)
        val saved = store.benchmarks.single()
        assertEquals(finished.id, saved.workoutId)
        assertEquals(RockportWalkTest.ID, saved.protocol)
        assertEquals(150, saved.heartRateBpm)
        assertEquals("Benchmark complete.", spoken.last())
    }

    @Test
    fun `an abandoned benchmark keeps the workout but saves no score`() {
        sessionId = 9
        store.samples = steadySamples(900, 150)
        start(treadmillBenchmark)
        run(900)
        controller.finish(mileCompleted = false)
        scheduler.runCurrent()

        assertNull((controller.state.value as WorkoutState.Finished).benchmark)
        assertTrue(store.benchmarks.isEmpty())
        assertEquals(1, store.closed.size)
    }

    @Test
    fun `a benchmark never touches the program clock`() {
        sessionId = 9
        store.samples = steadySamples(900, 150)
        start(treadmillBenchmark.copy(plan = plan, slot = null))
        run(900)
        finish()
        assertTrue(store.credited.isEmpty())
    }

    @Test
    fun `a treadmill benchmark does not use location`() {
        start(treadmillBenchmark)
        assertEquals(false, link.usedLocation.single())
        assertEquals(0, gps.starts)
    }

    @Test
    fun `an outdoor benchmark measures distance and stops itself at the mile`() {
        sessionId = 9
        store.samples = steadySamples(1_200, 140)
        start(outdoorBenchmark)
        assertEquals(true, link.usedLocation.single())
        assertEquals(1, gps.starts)

        walk(metresPerFix = 4, fixes = 200)
        val running = controller.state.value as WorkoutState.Running
        assertEquals(796.0, running.distanceMetres, 10.0)

        walk(metresPerFix = 4, fixes = 220)
        assertTrue(spoken.contains("Halfway."))
        assertEquals("mile_complete", finished.endReason)
        assertEquals(1, gps.stops)
        assertEquals(1, store.benchmarks.size)
    }

    @Test
    fun `speaks halfway only once`() {
        start(outdoorBenchmark)
        walk(metresPerFix = 4, fixes = 300)
        assertEquals(1, spoken.count { it == "Halfway." })
    }

    @Test
    fun `gps stops if the user finishes an outdoor benchmark early`() {
        start(outdoorBenchmark)
        walk(metresPerFix = 4, fixes = 10)
        controller.finish(mileCompleted = false)
        scheduler.runCurrent()
        assertEquals(1, gps.stops)
    }

    @Test
    fun `a double tap on start opens one workout`() {
        controller.start(freeRequest)
        controller.start(freeRequest)
        scheduler.runCurrent()
        assertEquals(1, link.begun.size)
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

    @Test
    fun `with spoken cues off, steps are silent but the safety alert still speaks`() {
        start(programRequest.copy(spokenCues = false))
        run(130)
        assertTrue(spoken.none { it.contains("Warm up") || it.contains("Hard 1") || it.contains("Session complete") })
        bpm = 210
        run(35)
        assertTrue(spoken.contains(HIGH_HEART_RATE_ALERT))
    }

    @Test
    fun `the screen is told while heart rate is too high, then not`() {
        start(freeRequest)
        bpm = 210
        run(35)
        assertTrue((controller.state.value as WorkoutState.Running).highHeartRate)
        bpm = 150
        run(65)
        assertFalse((controller.state.value as WorkoutState.Running).highHeartRate)
    }

    @Test
    fun `saves the heart rate drop in the minute after the last hard step`() {
        sessionId = 9
        store.samples = listOf(
            TimedBpm(START_MS + 115_000, 172),
            TimedBpm(START_MS + 120_000, 170),
            TimedBpm(START_MS + 180_000, 140),
        )
        val withCoolDown = plan.copy(steps = plan.steps + WorkoutStep("Cool down", 120, Zone.EASY))
        start(programRequest.copy(plan = withCoolDown))
        run(200)
        finish()
        assertEquals(31, finished.hrDropBpm)
    }

    @Test
    fun `leaves the drop empty when the minute after was not recorded`() {
        sessionId = 9
        store.samples = listOf(TimedBpm(START_MS + 120_000, 170))
        start(programRequest)
        run(125)
        finish()
        assertEquals(null, finished.hrDropBpm)
    }

    /** Moves [metresEvery2s] every two seconds, at a height set by [heightAt] from the distance so far. */
    private fun travel(metresEvery2s: Int, seconds: Int, heightAt: (Double) -> Double = { 0.0 }) {
        var metres = 0.0
        repeat(seconds / 2) {
            gps.moveBy(metresEvery2s)
            metres += metresEvery2s
            pressure.atHeight(heightAt(metres))
            run(1)
            pressure.atHeight(heightAt(metres))
            run(1)
        }
    }

    @Test
    fun `an outdoor ride measures distance, climb and watts, and saves each kilometre`() {
        val ride = freeRequest.copy(activity = TrainingActivity.CYCLING, usesGps = true, riderKg = 61.0, bike = BikeType.HYBRID)
        start(ride)
        assertEquals(1, gps.starts)
        assertEquals(1, pressure.starts)
        var sawClimbing = false
        var metres = 0.0
        repeat(180) {
            gps.moveBy(12)
            metres += 12
            pressure.atHeight(minOf(metres, 600.0) * 0.06)
            run(1)
            pressure.atHeight(minOf(metres, 600.0) * 0.06)
            run(1)
            if ((controller.state.value as WorkoutState.Running).motion?.climbing == true) sawClimbing = true
        }
        assertTrue(sawClimbing)
        finish()
        val saved = store.closed.last()
        assertEquals(2_160.0, saved.distanceMetres!!, 40.0)
        assertEquals(36.0, saved.climbMetres!!, 3.0)
        assertTrue(saved.avgWatts!! > 0)
        assertEquals("HYBRID", saved.bikeType)
        assertEquals(listOf(1, 2, 3), store.splits.map { it.number })
        assertTrue(store.splits[0].climbMetres > store.splits[1].climbMetres)
        val kinds = store.events.map { it.kind }
        assertTrue(kinds.contains(TimelineKind.CLIMB.name))
        assertTrue(kinds.contains(TimelineKind.SPLIT.name))
        assertEquals(1, gps.stops)
        assertEquals(1, pressure.stops)
    }

    @Test
    fun `a run speaks each kilometre with its pace`() {
        start(freeRequest.copy(activity = TrainingActivity.OUTDOOR_RUN, usesGps = true))
        travel(6, 360)
        assertTrue(spoken.toString(), spoken.any { it.startsWith("1 kilometre.") && it.endsWith("per kilometre.") })
    }

    @Test
    fun `a treadmill workout uses neither GPS nor the barometer`() {
        start(freeRequest)
        run(30)
        finish()
        assertEquals(0, gps.starts)
        assertEquals(0, pressure.starts)
        assertNull(store.closed.last().distanceMetres)
        assertTrue(store.splits.isEmpty())
    }

    @Test
    fun `the timeline records each step with its time`() {
        start(programRequest)
        run(65)
        val lines = controller.timeline.map { it.line() }
        assertTrue(lines.toString(), lines.first().startsWith("[0:00] Step: Warm up"))
        assertTrue(lines.toString(), lines.any { it.startsWith("[1:00] Step: Hard 1 of 1, 1:00, hard") })
        assertEquals(lines.size, store.events.size)
    }

    private class FakePressure : PressureSource {
        override val available = true
        var starts = 0
        var stops = 0
        var onHpa: ((Double) -> Unit)? = null

        override fun start(onHpa: (Double) -> Unit) {
            starts++
            this.onHpa = onHpa
        }

        override fun stop() {
            stops++
            onHpa = null
        }

        fun atHeight(metres: Double) {
            onHpa?.invoke(1_000.0 * (1 - metres / 44_330.0).pow(1 / 0.1903))
        }
    }

    private class FakeGps : DistanceSource {
        private var onFix: ((LocationFix) -> Unit)? = null
        private var latitude = 12.9
        private var elapsedMs = 0L
        var starts = 0
        var stops = 0

        override fun start(onFix: (LocationFix) -> Unit) {
            starts++
            this.onFix = onFix
            onFix(LocationFix(latitude, 77.6, 5f, elapsedMs))
        }

        override fun stop() {
            stops++
            onFix = null
        }

        fun moveBy(metres: Int) {
            latitude += metres / 111_195.0
            elapsedMs += 2_000
            onFix?.invoke(LocationFix(latitude, 77.6, 5f, elapsedMs))
        }
    }

    private class FakeLink : LiveLink {
        val begun = mutableListOf<HeartRateDevice>()
        val usedLocation = mutableListOf<Boolean>()
        var ended = 0

        override fun begin(device: HeartRateDevice, usesLocation: Boolean, usesMicrophone: Boolean) {
            begun += device
            usedLocation += usesLocation
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
        val benchmarks = mutableListOf<BenchmarkEntity>()
        val splits = mutableListOf<SplitEntity>()
        val events = mutableListOf<WorkoutEventEntity>()

        override suspend fun addSplit(split: SplitEntity) {
            splits += split
        }

        override suspend fun addEvent(event: WorkoutEventEntity) {
            events += event
        }

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

        override suspend fun saveBenchmark(benchmark: BenchmarkEntity) {
            benchmarks += benchmark
        }
    }

    private companion object {
        const val START_MS = 1_700_000_000_000
    }
}
